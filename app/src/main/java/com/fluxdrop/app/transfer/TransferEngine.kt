package com.fluxdrop.app.transfer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.fluxdrop.app.core.APP_MAGIC
import com.fluxdrop.app.core.BUF_SIZE
import com.fluxdrop.app.core.Device
import com.fluxdrop.app.core.DeviceKind
import com.fluxdrop.app.core.Direction
import com.fluxdrop.app.core.FileMeta
import com.fluxdrop.app.core.HTTP_PORT
import com.fluxdrop.app.core.Identity
import com.fluxdrop.app.core.IncomingRequest
import com.fluxdrop.app.core.NetUtils
import com.fluxdrop.app.core.PickedFile
import com.fluxdrop.app.core.Prefs
import com.fluxdrop.app.core.TaskSnapshot
import com.fluxdrop.app.core.TaskState
import com.fluxdrop.app.discovery.RadarDiscovery
import com.fluxdrop.app.hotspot.HotspotManager
import com.fluxdrop.app.server.MiniHttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 整个应用的核心：
 *  - 微型 Web 服务（接收文件 / 给电脑浏览器提供网页）
 *  - UDP 雷达（自动发现设备）
 *  - 一对多并发发送（每台目标设备一个协程，同时进行）
 */
class TransferEngine(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    val store = FileStore(app)
    val hotspot = HotspotManager(app)

    private val selfKind =
        if (app.resources.configuration.smallestScreenWidthDp >= 600) DeviceKind.TABLET else DeviceKind.PHONE

    private val _name = MutableStateFlow(prefs.deviceName)
    val deviceName: StateFlow<String> = _name.asStateFlow()
    private val _autoAccept = MutableStateFlow(prefs.autoAccept)
    val autoAccept: StateFlow<Boolean> = _autoAccept.asStateFlow()
    private val _addresses = MutableStateFlow(NetUtils.localIpv4())
    val addresses: StateFlow<List<NetUtils.LocalAddr>> = _addresses.asStateFlow()
    private val _serverOn = MutableStateFlow(false)
    val serverOn: StateFlow<Boolean> = _serverOn.asStateFlow()

    fun identity() = Identity(prefs.deviceId, _name.value, Build.MODEL.orEmpty(), selfKind, HTTP_PORT)

    val radar = RadarDiscovery(app, scope) { identity() }
    private val routes = ApiRoutes(this)
    private val server = MiniHttpServer(HTTP_PORT, scope) { req, res -> routes.handle(req, res) }

    internal val manual = MutableStateFlow<Map<String, Device>>(emptyMap())
    internal val webDevices = MutableStateFlow<Map<String, Device>>(emptyMap())
    internal val webSeen = ConcurrentHashMap<String, Long>()

    /** 雷达上显示的所有设备：局域网 App + 手动添加 + 打开网页的浏览器 */
    val devices: StateFlow<List<Device>> = combine(radar.devices, manual, webDevices) { lan, man, web ->
        val merged = LinkedHashMap<String, Device>()
        man.values.forEach { merged[it.id] = it }
        lan.values.forEach { merged[it.id] = it }
        web.values.forEach { merged[it.id] = it }
        merged.values.sortedWith(compareBy<Device>({ it.viaWeb }, { it.name.lowercase() }))
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val tasks = LinkedHashMap<String, Task>()
    private val _tasks = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    val taskList: StateFlow<List<TaskSnapshot>> = _tasks.asStateFlow()
    val activeCount: StateFlow<Int> =
        _tasks.map { l -> l.count { it.state.active } }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val _incoming = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val incoming: StateFlow<List<IncomingRequest>> = _incoming.asStateFlow()
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    internal val sessions = ConcurrentHashMap<String, InSession>()
    internal val pushItems = ConcurrentHashMap<String, PushItem>()

    /** 一次性提示（Toast） */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    @Volatile
    private var started = false
    private var tickJob: Job? = null

    init {
        hotspot.onNetworkChanged = {
            _addresses.value = NetUtils.localIpv4()
            radar.refresh()
        }
    }

    @Synchronized
    fun start() {
        if (started) {
            if (!server.running) _serverOn.value = server.start()
            return
        }
        started = true
        _serverOn.value = server.start()
        radar.start()
        tickJob = scope.launch { tickLoop() }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        tickJob?.cancel()
        tickJob = null
        allTasks().filter { it.state.active }.forEach { cancelTask(it.id) }
        radar.stop()
        server.stop()
        hotspot.stopHost()
        hotspot.leave()
        _serverOn.value = false
    }

    // ------------------------------------------------------------------ 设置

    fun setDeviceName(n: String) {
        val v = n.trim().take(32)
        if (v.isEmpty()) return
        prefs.deviceName = v
        _name.value = v
        radar.refresh()
    }

    fun setAutoAccept(v: Boolean) {
        prefs.autoAccept = v
        _autoAccept.value = v
    }

    fun infoJson(): JSONObject {
        val me = identity()
        return JSONObject()
            .put("app", APP_MAGIC)
            .put("id", me.id)
            .put("name", me.name)
            .put("model", me.model)
            .put("kind", me.kind.name)
            .put("port", me.port)
    }

    internal fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(20)

    // ------------------------------------------------------------------ 任务表

    internal fun addTask(t: Task) {
        synchronized(tasks) { tasks[t.id] = t }
    }

    private fun allTasks(): List<Task> = synchronized(tasks) { tasks.values.toList() }

    fun clearFinished() {
        synchronized(tasks) { tasks.values.removeAll { !it.state.active } }
    }

    fun cancelTask(id: String) {
        val t = synchronized(tasks) { tasks[id] } ?: return
        if (!t.state.active) return
        t.cancelRequested = true
        t.finish(TaskState.CANCELED, "已取消")
        scope.launch(Dispatchers.IO) {
            try {
                t.conn?.disconnect()
            } catch (_: Exception) {
            }
        }
        t.job?.cancel()
        sessions.remove(id)
        pushItems.values.removeAll { it.task === t }
    }

    // ------------------------------------------------------------------ 接收确认

    internal suspend fun awaitDecision(r: IncomingRequest): Boolean {
        val d = CompletableDeferred<Boolean>()
        decisions[r.id] = d
        _incoming.update { it + r }
        return try {
            withTimeoutOrNull(60_000) { d.await() } ?: false
        } finally {
            decisions.remove(r.id)
            _incoming.update { l -> l.filterNot { it.id == r.id } }
        }
    }

    fun decide(id: String, accept: Boolean) {
        decisions[id]?.complete(accept)
    }

    // ------------------------------------------------------------------ 发送（一对多并发）

    fun send(targets: List<Device>, files: List<PickedFile>) {
        if (targets.isEmpty() || files.isEmpty()) return
        val metas = files.map { FileMeta(it.name, it.size, it.mime) }
        for (d in targets) {
            val t = Task(newId(), Direction.OUT, d.name, d.kind, metas)
            addTask(t)
            if (d.viaWeb) {
                offerToWeb(d, t, files)
            } else {
                // 每台设备一个独立协程：N 台设备同时传，互不等待
                t.job = scope.launch(Dispatchers.IO) { runSend(t, d, files) }
            }
        }
    }

    private suspend fun runSend(t: Task, d: Device, files: List<PickedFile>) {
        val base = "http://${d.ip}:${d.port}"
        try {
            t.message = "等待对方确认…"
            val me = identity()
            val arr = JSONArray()
            files.forEach { f -> arr.put(JSONObject().put("name", f.name).put("size", f.size).put("mime", f.mime)) }
            val payload = JSONObject()
                .put("senderId", me.id)
                .put("senderName", me.name)
                .put("senderKind", me.kind.name)
                .put("files", arr)
            val (code, body) = postJson("$base/api/session", payload.toString(), 80_000, t)
            if (t.cancelRequested) return
            when (code) {
                200 -> Unit
                403 -> {
                    t.finish(TaskState.REJECTED, "对方拒绝了接收")
                    return
                }
                else -> throw IOException("对方返回错误（$code）")
            }
            val session = JSONObject(body).getString("session")
            t.state = TaskState.TRANSFERRING
            t.message = null
            for ((i, f) in files.withIndex()) {
                if (t.cancelRequested || !currentCoroutineContext().isActive) throw IOException("已取消")
                t.currentIndex = i
                upload("$base/api/upload?session=$session&index=$i", f, t)
            }
            t.finish(TaskState.DONE)
            events.tryEmit("已发送给 ${d.name}")
        } catch (e: Exception) {
            if (t.cancelRequested) t.finish(TaskState.CANCELED, "已取消")
            else t.finish(TaskState.FAILED, friendlyError(e))
        } finally {
            t.conn = null
        }
    }

    /** 以固定长度流式上传：边读边发，不缓存整个文件 */
    private fun upload(url: String, f: PickedFile, t: Task) {
        val c = URL(url).openConnection() as HttpURLConnection
        t.conn = c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.connectTimeout = 6000
            c.readTimeout = 60_000
            c.setFixedLengthStreamingMode(f.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val input = app.contentResolver.openInputStream(f.uri) ?: throw IOException("无法读取 ${f.name}")
            input.use { ins ->
                c.outputStream.use { os ->
                    val buf = ByteArray(BUF_SIZE)
                    var left = f.size
                    while (left > 0) {
                        if (t.cancelRequested) throw IOException("已取消")
                        val n = ins.read(buf, 0, if (left < BUF_SIZE) left.toInt() else BUF_SIZE)
                        if (n < 0) throw IOException("文件读取不完整：${f.name}")
                        os.write(buf, 0, n)
                        left -= n
                        t.done.addAndGet(n.toLong())
                    }
                }
            }
            val code = c.responseCode
            if (code != 200) throw IOException("对方写入失败（$code）")
        } finally {
            c.disconnect()
            t.conn = null
        }
    }

    private fun postJson(url: String, json: String, readTimeout: Int, t: Task?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        t?.conn = c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.connectTimeout = 5000
            c.readTimeout = readTimeout
            val bytes = json.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            c.disconnect()
            t?.conn = null
        }
    }

    private fun httpGet(url: String, timeout: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.useCaches = false
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ConnectException -> "无法连接到对方"
        is SocketTimeoutException -> "连接超时"
        is NoRouteToHostException -> "对方不在同一网络"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "传输中断"
    }

    // ------------------------------------------------------------------ 推送给电脑浏览器

    private fun offerToWeb(d: Device, t: Task, files: List<PickedFile>) {
        val cid = d.id.removePrefix("web-")
        t.message = "等待电脑浏览器接收…"
        files.forEachIndexed { i, f ->
            val id = newId()
            pushItems[id] = PushItem(id, cid, f, t, i)
        }
    }

    // ------------------------------------------------------------------ 手动输入 IP

    suspend fun probe(input: String): Result<Device> = withContext(Dispatchers.IO) {
        runCatching {
            val s = input.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
            val host = s.substringBefore(':').trim()
            val port = s.substringAfter(':', "").toIntOrNull() ?: HTTP_PORT
            require(host.isNotBlank()) { "地址无效" }
            val (code, body) = try {
                httpGet("http://$host:$port/api/info", 3500)
            } catch (e: Exception) {
                throw IOException("连接失败：请确认对方已打开闪渡且在同一网络")
            }
            if (code != 200) throw IOException("对方未运行闪渡")
            val j = JSONObject(body)
            if (j.optString("app") != APP_MAGIC) throw IOException("对方未运行闪渡")
            val id = j.getString("id")
            if (id == prefs.deviceId) throw IOException("这是本机的地址")
            val dev = Device(
                id = id,
                name = j.optString("name", host),
                model = j.optString("model"),
                ip = host,
                port = port,
                kind = DeviceKind.parse(j.optString("kind")),
                manual = true,
            )
            manual.update { it + (dev.id to dev) }
            dev
        }
    }

    // ------------------------------------------------------------------ 定时器：测速 / 清理

    private suspend fun tickLoop() {
        var n = 0
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val list = allTasks()
            for (t in list) {
                val d = t.done.get()
                val dt = now - t.lastTick
                if (dt >= 450) {
                    val inst = (d - t.lastBytes) * 1000.0 / dt
                    t.speed = if (t.speed == 0.0) inst else t.speed * 0.55 + inst * 0.45
                    if (d != t.lastBytes) t.lastProgressAt = now
                    t.lastBytes = d
                    t.lastTick = now
                }
                if (!t.state.active) t.speed = 0.0
                if (t.direction == Direction.IN && t.state == TaskState.TRANSFERRING &&
                    now - t.lastProgressAt > 120_000
                ) {
                    t.finish(TaskState.FAILED, "连接超时")
                    sessions.remove(t.id)
                }
            }
            _tasks.value = list.asReversed().map { it.snapshot() }

            if (n % 8 == 0) {
                _addresses.value = NetUtils.localIpv4()
                val stale = webSeen.filterValues { now - it > 6000 }.keys
                if (stale.isNotEmpty()) {
                    stale.forEach { webSeen.remove(it) }
                    webDevices.update { it - stale }
                }
                // 30 分钟没被电脑领取的推送自动过期
                pushItems.values.filter { now - it.createdAt > 30 * 60_000L }.forEach { p ->
                    pushItems.remove(p.id)
                    if (p.task.state == TaskState.WAITING) p.task.finish(TaskState.FAILED, "电脑未接收，已过期")
                }
                if (!server.running) _serverOn.value = server.start()
            }
            n++
            delay(250)
        }
    }
}
