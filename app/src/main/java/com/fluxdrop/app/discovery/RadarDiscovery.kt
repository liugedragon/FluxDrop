package com.fluxdrop.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.fluxdrop.app.core.APP_MAGIC
import com.fluxdrop.app.core.Device
import com.fluxdrop.app.core.DeviceKind
import com.fluxdrop.app.core.HTTP_PORT
import com.fluxdrop.app.core.Identity
import com.fluxdrop.app.core.NetUtils
import com.fluxdrop.app.core.UDP_PORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 雷达：每 2 秒向所在的每个网段发一次 UDP 广播「我在这」，
 * 同时监听别人的广播；7 秒没有心跳的设备自动从雷达上消失。
 */
class RadarDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    private val identity: () -> Identity,
) {
    private val appContext = context.applicationContext
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

    private val seen = ConcurrentHashMap<String, Long>()
    private val jobs = mutableListOf<Job>()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("fluxdrop-radar").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        jobs += scope.launch(Dispatchers.IO) { listenLoop() }
        jobs += scope.launch(Dispatchers.IO) {
            var burst = 5
            while (isActive) {
                broadcast("hello")
                val wait = if (burst-- > 0) 600L else 2000L
                withTimeoutOrNull(wait) { kick.receive() }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(1500)
                val now = SystemClock.elapsedRealtime()
                val stale = seen.filterValues { now - it > 7000 }.keys
                if (stale.isNotEmpty()) {
                    stale.forEach { seen.remove(it) }
                    _devices.update { it - stale }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (jobs.isEmpty()) return
        scope.launch(Dispatchers.IO) { broadcast("bye") }
        jobs.forEach { it.cancel() }
        jobs.clear()
        socket?.close()
        socket = null
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
        seen.clear()
        _devices.value = emptyMap()
    }

    /** 立即再广播一次（网络切换、改名、手动刷新时调用） */
    fun refresh() {
        kick.trySend(Unit)
    }

    /** 网络切换后重建监听 socket（关闭后 listenLoop 会自动重新创建） */
    fun resetSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private suspend fun listenLoop() {
        while (currentCoroutineContext().isActive) {
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                }
            } catch (e: Exception) {
                delay(3000)
                continue
            }
            socket = s
            val buf = ByteArray(4096)
            try {
                while (currentCoroutineContext().isActive) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    handle(String(p.data, p.offset, p.length, Charsets.UTF_8), p.address)
                }
            } catch (_: Exception) {
            } finally {
                s.close()
            }
            if (!currentCoroutineContext().isActive) break
            delay(500)
        }
    }

    private fun handle(text: String, from: InetAddress) {
        val j = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        if (j.optString("app") != APP_MAGIC) return
        val me = identity()
        val id = j.optString("id")
        if (id.isEmpty() || id == me.id) return
        val ip = from.hostAddress ?: return
        val op = j.optString("op")
        if (op == "bye") {
            seen.remove(id)
            _devices.update { it - id }
            return
        }
        seen[id] = SystemClock.elapsedRealtime()
        val dev = Device(
            id = id,
            name = j.optString("name").ifBlank { "未知设备" },
            model = j.optString("model"),
            ip = ip,
            port = j.optInt("port", HTTP_PORT),
            kind = DeviceKind.parse(j.optString("kind")),
        )
        _devices.update { if (it[id] == dev) it else it + (id to dev) }
        // 回一个单播，让新上线的设备马上看到我们
        if (op == "hello") scope.launch(Dispatchers.IO) { sendTo(from, "reply") }
    }

    private fun packet(op: String): ByteArray {
        val me = identity()
        return JSONObject()
            .put("app", APP_MAGIC)
            .put("v", 1)
            .put("op", op)
            .put("id", me.id)
            .put("name", me.name)
            .put("model", me.model)
            .put("kind", me.kind.name)
            .put("port", me.port)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun broadcast(op: String) {
        val data = packet(op)
        val targets = LinkedHashSet<String>()
        // 每个网卡（Wi-Fi、自己开的热点…）各自的定向广播地址
        NetUtils.localIpv4().forEach { a -> a.broadcast?.let { targets += it } }
        targets += "255.255.255.255"
        try {
            DatagramSocket().use { ds ->
                ds.broadcast = true
                for (t in targets) {
                    try {
                        ds.send(DatagramPacket(data, data.size, InetAddress.getByName(t), UDP_PORT))
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sendTo(addr: InetAddress, op: String) {
        val data = packet(op)
        try {
            DatagramSocket().use { it.send(DatagramPacket(data, data.size, addr, UDP_PORT)) }
        } catch (_: Exception) {
        }
    }
}
