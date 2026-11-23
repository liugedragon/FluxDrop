package com.fluxdrop.app.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import com.fluxdrop.app.core.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

data class HotspotInfo(val ssid: String, val password: String, val security: String) {
    /** 标准 Wi-Fi 二维码：闪渡能扫，系统相机 / 微信也能扫 */
    fun qrPayload(): String =
        if (security == "NONE") "WIFI:T:nopass;S:${esc(ssid)};;"
        else "WIFI:T:${if (security == "SAE") "SAE" else "WPA"};S:${esc(ssid)};P:${esc(password)};;"

    private fun esc(s: String) = buildString {
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }
}

/** 出错时界面上可以给用户的「一键修复」 */
enum class FixAction { NONE, LOCATION, WIFI, SYSTEM_HOTSPOT, SYSTEM_CONNECT }

sealed interface HostState {
    data object Idle : HostState
    data class Starting(val attempt: Int) : HostState

    /** ip = 检测到的热点网卡地址；verifying = 正在确认热点网卡是否真的起来了 */
    data class Running(val info: HotspotInfo, val ip: String?, val verifying: Boolean) : HostState
    data class Error(val msg: String, val fix: FixAction = FixAction.NONE) : HostState
}

sealed interface JoinState {
    data object Idle : JoinState
    data class Connecting(val info: HotspotInfo, val viaSystem: Boolean = false) : JoinState
    data class Connected(val ssid: String, val hostIp: String?) : JoinState
    data class Error(val msg: String, val fix: FixAction, val info: HotspotInfo?) : JoinState
}

/**
 * 免流量热点：
 *  - 发起方：startLocalOnlyHotspot 开「本地专用」热点（不共享移动数据），失败自动重试，
 *    启动后检测热点网卡确实出现，运行中持续监测，被系统关掉会明确提示。
 *  - 加入方：扫码后用 WifiNetworkSpecifier 连接；也支持「系统方式」连接。
 *  - 任何没有互联网的 Wi-Fi（对方热点）连上后，自动把本 App 的网络绑定过去，
 *    否则开着移动数据时系统会把流量走 4G/5G，导致连上了热点却传不了文件。
 */
class HotspotManager(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 发起方
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var generation = 0
    private var hostJob: Job? = null
    private var monitorJob: Job? = null

    // 加入方
    private var joinCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var joinedNetwork: Network? = null
    @Volatile private var pendingSystem: HotspotInfo? = null
    private var systemTimeout: Job? = null

    // 自动绑定无互联网的 Wi-Fi
    private var autoCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var autoBound: Network? = null

    private val _host = MutableStateFlow<HostState>(HostState.Idle)
    val hostState: StateFlow<HostState> = _host.asStateFlow()
    private val _join = MutableStateFlow<JoinState>(JoinState.Idle)
    val joinState: StateFlow<JoinState> = _join.asStateFlow()

    /** 诊断日志：把每一步系统返回了什么记下来，界面上可以查看 / 复制 */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _log.value = (_log.value + "$t  $s").takeLast(30)
    }

    fun wifiEnabled(): Boolean = try {
        wifi.isWifiEnabled
    } catch (_: Exception) {
        false
    }

    /** 系统「个人热点」状态：true / false / null（读不到） */
    fun systemHotspotState(): Boolean? = try {
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
    } catch (_: Throwable) {
        null
    }

    /** 网络变化（雷达需要立即重新广播） */
    var onNetworkChanged: (() -> Unit)? = null

    /** 连上对方热点后拿到的网关地址（= 开热点那台手机），用来直接连接对方 */
    var onHostGateway: ((String) -> Unit)? = null

    private fun notifyNetworkChanged(gateway: String? = null, delayMs: Long = 700) {
        main.postDelayed({
            onNetworkChanged?.invoke()
            if (gateway != null) onHostGateway?.invoke(gateway)
        }, delayMs)
    }

    // =====================================================================
    // 发起方：开热点
    // =====================================================================

    fun startHost() {
        val cur = _host.value
        if (cur is HostState.Running || cur is HostState.Starting) return
        leave()
        hostJob?.cancel()
        hostJob = scope.launch { startWithRetry() }
    }

    fun restartHost() {
        stopHost()
        scope.launch {
            delay(800) // 给系统一点时间把旧热点拆掉
            startHost()
        }
    }

    fun stopHost() {
        generation++ // 让旧回调全部失效
        hostJob?.cancel()
        hostJob = null
        monitorJob?.cancel()
        monitorJob = null
        closeReservation()
        if (_host.value !is HostState.Idle) _host.value = HostState.Idle
    }

    private sealed interface Attempt {
        class Started(val res: WifiManager.LocalOnlyHotspotReservation) : Attempt
        class Failed(val msg: String, val retryable: Boolean, val fix: FixAction = FixAction.NONE) : Attempt
    }

    private suspend fun startWithRetry() {
        var last = Attempt.Failed("开启热点失败", true)
        for (attempt in 1..3) {
            _host.value = HostState.Starting(attempt)
            closeReservation()
            val before = withContext(Dispatchers.IO) { NetUtils.localIpv4().map { it.ip }.toSet() }
            log("第 $attempt 次请求开启热点（Wi-Fi 开关=${if (wifiEnabled()) "开" else "关"}，系统热点=${systemHotspotState() ?: "未知"}）")
            when (val r = requestOnce()) {
                is Attempt.Started -> {
                    onStarted(r.res, before)
                    return
                }
                is Attempt.Failed -> {
                    last = r
                    log("失败：${r.msg}")
                    if (!r.retryable) break
                }
            }
            delay(1500L * attempt)
        }
        _host.value = if (isSystemHotspotOn()) {
            HostState.Error("检测到系统「个人热点」正在运行，它会占用无线芯片。请先关闭系统热点再开启。", FixAction.SYSTEM_HOTSPOT)
        } else {
            HostState.Error(last.msg, last.fix)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(): Attempt {
        val token = ++generation
        val result = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Attempt> { cont ->
                val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        if (cont.isActive && token == generation) {
                            cont.resume(Attempt.Started(res))
                        } else {
                            try {
                                res.close()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onStopped() {
                        log("系统回调 onStopped（热点被系统停止）")
                        if (token == generation) onUnexpectedStop()
                    }

                    override fun onFailed(reason: Int) {
                        log("系统回调 onFailed(reason=$reason)")
                        if (cont.isActive) {
                            cont.resume(
                                Attempt.Failed(
                                    reasonText(reason),
                                    retryable = reason != WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED,
                                    fix = if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE) {
                                        FixAction.SYSTEM_HOTSPOT
                                    } else {
                                        FixAction.NONE
                                    },
                                ),
                            )
                        } else if (token == generation) {
                            onUnexpectedStop()
                        }
                    }
                }
                try {
                    wifi.startLocalOnlyHotspot(cb, main)
                    log("已调用 startLocalOnlyHotspot，等待系统回调…")
                } catch (e: SecurityException) {
                    log("SecurityException：${e.message}")
                    if (cont.isActive) cont.resume(Attempt.Failed("缺少权限：请在系统设置里允许闪渡的「附近设备」或「位置信息」权限", false))
                } catch (e: IllegalStateException) {
                    log("IllegalStateException：${e.message}")
                    if (cont.isActive) cont.resume(Attempt.Failed("上一次的热点请求还没结束，请稍后重试", true))
                } catch (e: Exception) {
                    log("${e.javaClass.simpleName}：${e.message}")
                    if (cont.isActive) cont.resume(Attempt.Failed(e.message ?: "开启热点失败", true))
                }
            }
        }
        if (result == null) {
            generation++ // 超时：之后迟到的回调一律作废
            log("超时：15 秒内系统没有任何回调")
        }
        return result ?: Attempt.Failed("系统长时间没有响应开启热点的请求", true)
    }

    private fun onStarted(res: WifiManager.LocalOnlyHotspotReservation, before: Set<String>) {
        reservation = res
        val info = extract(res)
        if (info == null) {
            log("系统回调 onStarted，但读不到热点名称/密码")
            closeReservation()
            _host.value = HostState.Error("无法读取热点名称和密码，请重试")
            return
        }
        log("系统回调 onStarted：名称=${info.ssid}，加密=${info.security}")
        _host.value = HostState.Running(info, null, verifying = true)
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // 1) 确认热点网卡真的起来了（最多等 10 秒）
            var ip: String? = null
            for (i in 0 until 20) {
                ip = findApIp(before)
                if (ip != null) break
                delay(500)
            }
            val r0 = _host.value as? HostState.Running ?: return@launch
            log(if (ip != null) "检测到热点网卡 IP=$ip" else "10 秒内没检测到新的热点网卡")
            _host.value = r0.copy(ip = ip, verifying = false)
            notifyNetworkChanged(delayMs = 0)

            // 2) 运行中持续监测：热点网卡消失 = 被系统关掉了
            var missing = 0
            while (isActive) {
                delay(3000)
                val r = _host.value as? HostState.Running ?: break
                val now = withContext(Dispatchers.IO) { NetUtils.localIpv4() }
                if (r.ip == null) {
                    findApIp(before)?.let { _host.value = r.copy(ip = it) }
                } else if (now.none { it.ip == r.ip }) {
                    val again = findApIp(before)
                    if (again != null) {
                        missing = 0
                        _host.value = r.copy(ip = again)
                    } else if (++missing >= 2) {
                        log("热点网卡 ${r.ip} 消失，判定热点已被关闭")
                        generation++
                        closeReservation()
                        _host.value = HostState.Error("热点已被系统关闭（可能被省电策略或其他应用中断），请重新开启")
                        break
                    }
                } else {
                    missing = 0
                }
            }
        }
    }

    private suspend fun findApIp(before: Set<String>): String? = withContext(Dispatchers.IO) {
        val now = NetUtils.localIpv4()
        now.firstOrNull { it.ip !in before && it.isHotspot }?.ip
            ?: now.firstOrNull { it.ip !in before }?.ip
            ?: now.firstOrNull { it.isHotspot }?.ip
    }

    private fun onUnexpectedStop() {
        monitorJob?.cancel()
        reservation = null
        if (_host.value is HostState.Running) {
            _host.value = HostState.Error("热点被系统关闭了（可能长时间没有设备连接，或被省电策略关闭），请重新开启")
        }
    }

    private fun closeReservation() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
    }

    /** 系统自带的「个人热点」是否开着（隐藏接口，取不到就当没开） */
    private fun isSystemHotspotOn(): Boolean = try {
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    private fun extract(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = res.softApConfiguration
            val ssid: String = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    c.wifiSsid?.toString()?.removeSurrounding("\"")
                } else {
                    c.ssid
                }
                ) ?: return null
            val pass = c.passphrase ?: ""
            val sec = when {
                pass.isEmpty() -> "NONE"
                c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "SAE"
                else -> "WPA"
            }
            return HotspotInfo(ssid, pass, sec)
        }
        val c = res.wifiConfiguration ?: return null
        val pass = c.preSharedKey?.removeSurrounding("\"") ?: ""
        return HotspotInfo(c.SSID.orEmpty().removeSurrounding("\""), pass, if (pass.isEmpty()) "NONE" else "WPA")
    }

    private fun reasonText(r: Int) = when (r) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "没有可用的无线信道，请稍后重试"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "当前无线模式冲突：请先关闭系统「个人热点 / 网络共享」后重试"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "系统禁止开启热点（可能被设备管理策略限制）"
        else -> "开启热点失败（错误码 $r）。可尝试：关闭系统热点、打开 Wi-Fi 开关后重试"
    }

    // =====================================================================
    // 加入方：扫码连接
    // =====================================================================

    private fun locationOn(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @Suppress("DEPRECATION")
    private fun enableWifiLegacy() {
        try {
            wifi.isWifiEnabled = true
        } catch (_: Exception) {
        }
    }

    fun join(info: HotspotInfo) {
        leave()
        if (!wifi.isWifiEnabled) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                enableWifiLegacy()
            } else {
                _join.value = JoinState.Error("请先打开手机的 Wi-Fi 开关（不需要连接任何网络），然后重新扫码", FixAction.WIFI, info)
                return
            }
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2 && !locationOn()) {
            _join.value = JoinState.Error("Android 10～12 连接热点需要打开系统「定位」开关，打开后重新扫码", FixAction.LOCATION, info)
            return
        }
        if (info.security == "SAE" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !wifi.isWpa3SaeSupported) {
            _join.value = JoinState.Error("对方热点使用 WPA3 加密，本机不支持。可尝试「用系统方式连接」", FixAction.SYSTEM_CONNECT, info)
            return
        }
        _join.value = JoinState.Connecting(info)
        log("扫码连接：名称=${info.ssid}，加密=${info.security}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) joinQ(info) else joinLegacy(info)
    }

    private fun joinQ(info: HotspotInfo) {
        try {
            val b = WifiNetworkSpecifier.Builder().setSsid(info.ssid)
            if (info.password.isNotEmpty()) {
                if (info.security == "SAE") b.setWpa3Passphrase(info.password) else b.setWpa2Passphrase(info.password)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(b.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    joinedNetwork = network
                    safeBind(network)
                    val gw = gatewayOf(network)
                    log("已连上热点，对方地址=${gw ?: "未知"}")
                    _join.value = JoinState.Connected(info.ssid, gw)
                    notifyNetworkChanged(gw)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (network != joinedNetwork) return
                    val cur = _join.value as? JoinState.Connected ?: return
                    if (cur.hostIp != null) return
                    val gw = gatewayFrom(linkProperties) ?: return
                    _join.value = cur.copy(hostIp = gw)
                    notifyNetworkChanged(gw)
                }

                override fun onUnavailable() {
                    log("连接失败 onUnavailable（没找到网络或用户取消）")
                    joinCallback = null
                    _join.value = JoinState.Error(
                        "没能连上「${info.ssid}」。请确认对方热点还开着、两台手机靠近一些，或改用「系统方式连接」",
                        FixAction.SYSTEM_CONNECT,
                        info,
                    )
                }

                override fun onLost(network: Network) {
                    if (network != joinedNetwork) return
                    joinedNetwork = null
                    safeBind(autoBound)
                    _join.value = JoinState.Error("与「${info.ssid}」的连接断开了（对方可能关闭了热点）", FixAction.NONE, info)
                    notifyNetworkChanged(delayMs = 0)
                }
            }
            joinCallback = cb
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            _join.value = JoinState.Error(e.message ?: "连接失败", FixAction.SYSTEM_CONNECT, info)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun joinLegacy(info: HotspotInfo) {
        try {
            val conf = WifiConfiguration().apply {
                SSID = "\"${info.ssid}\""
                if (info.password.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"${info.password}\""
                }
            }
            val id = wifi.addNetwork(conf)
            if (id == -1) {
                _join.value = JoinState.Error("无法添加网络，请用系统方式连接「${info.ssid}」", FixAction.SYSTEM_CONNECT, info)
                return
            }
            wifi.disconnect()
            wifi.enableNetwork(id, true)
            wifi.reconnect()
            // 真正连上后由自动绑定逻辑把状态改为「已连接」
            markSystemConnecting(info)
        } catch (e: Exception) {
            _join.value = JoinState.Error(e.message ?: "连接失败", FixAction.SYSTEM_CONNECT, info)
        }
    }

    /** 「用系统方式连接」要启动的界面（Android 11+ 弹系统确认框直接保存并连接） */
    fun systemConnectIntent(info: HotspotInfo): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = WifiNetworkSuggestion.Builder().setSsid(info.ssid)
            if (info.password.isNotEmpty()) {
                if (info.security == "SAE") b.setWpa3Passphrase(info.password) else b.setWpa2Passphrase(info.password)
            }
            return Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(b.build()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return Intent(Settings.Panel.ACTION_WIFI)
        return Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    /** 用户走了系统连接：等系统连上后自动识别 */
    fun markSystemConnecting(info: HotspotInfo) {
        joinCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        joinCallback = null
        pendingSystem = info
        _join.value = JoinState.Connecting(info, viaSystem = true)
        systemTimeout?.cancel()
        systemTimeout = scope.launch {
            delay(60_000)
            if (pendingSystem != null && _join.value is JoinState.Connecting) {
                pendingSystem = null
                _join.value = JoinState.Error("等待超时，没有检测到连上「${info.ssid}」", FixAction.SYSTEM_CONNECT, info)
            }
        }
        // 如果其实已经连着了，立刻识别
        cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let { evaluate(n, it) } }
        autoBound?.let { onSystemJoined(gatewayOf(it)) }
    }

    private fun onSystemJoined(gw: String?) {
        val info = pendingSystem ?: return
        pendingSystem = null
        systemTimeout?.cancel()
        _join.value = JoinState.Connected(info.ssid, gw)
    }

    fun leave() {
        joinCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        joinCallback = null
        joinedNetwork = null
        pendingSystem = null
        systemTimeout?.cancel()
        safeBind(autoBound)
        if (_join.value !is JoinState.Idle) _join.value = JoinState.Idle
    }

    // =====================================================================
    // 自动绑定：连着没有互联网的 Wi-Fi 时，让本 App 的流量走这个 Wi-Fi
    // =====================================================================

    fun startAutoBind() {
        if (autoCallback != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                evaluate(network, networkCapabilities)
            }

            override fun onLost(network: Network) {
                if (network == autoBound) {
                    autoBound = null
                    if (joinedNetwork == null) safeBind(null)
                    notifyNetworkChanged(delayMs = 0)
                }
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            autoCallback = cb
        } catch (_: Exception) {
        }
    }

    fun stopAutoBind() {
        autoCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        autoCallback = null
        if (autoBound != null && joinedNetwork == null) safeBind(null)
        autoBound = null
    }

    private fun evaluate(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isDefault = cm.activeNetwork == network
        if (!hasInternet && pendingSystem != null) onSystemJoined(gatewayOf(network))
        if (joinedNetwork != null) return
        if (!hasInternet && !isDefault) {
            if (autoBound != network) {
                autoBound = network
                safeBind(network)
                notifyNetworkChanged(gatewayOf(network))
            }
        } else if (autoBound == network) {
            autoBound = null
            safeBind(null)
            notifyNetworkChanged(delayMs = 0)
        }
    }

    private fun safeBind(network: Network?) {
        try {
            cm.bindProcessToNetwork(network)
        } catch (_: Exception) {
        }
    }

    private fun gatewayOf(network: Network): String? =
        cm.getLinkProperties(network)?.let { gatewayFrom(it) } ?: dhcpServer()

    private fun gatewayFrom(lp: LinkProperties): String? =
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress

    @Suppress("DEPRECATION")
    private fun dhcpServer(): String? = try {
        val a = wifi.dhcpInfo?.serverAddress ?: 0
        if (a == 0) null
        else "${a and 0xff}.${(a shr 8) and 0xff}.${(a shr 16) and 0xff}.${(a ushr 24) and 0xff}"
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 解析 WIFI:T:WPA;S:xxx;P:yyy;; 格式（支持反斜杠转义） */
        fun parseWifiQr(raw: String): HotspotInfo? {
            if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
            val body = raw.substring(5)
            val fields = HashMap<String, String>()
            val key = StringBuilder()
            val value = StringBuilder()
            var inValue = false
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    (if (inValue) value else key).append(body[i + 1])
                    i += 2
                    continue
                }
                when {
                    !inValue && c == ':' -> inValue = true
                    inValue && c == ';' -> {
                        fields[key.toString().trim().uppercase()] = value.toString()
                        key.clear()
                        value.clear()
                        inValue = false
                    }
                    !inValue && c == ';' -> Unit
                    else -> (if (inValue) value else key).append(c)
                }
                i++
            }
            val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
            val t = fields["T"]?.uppercase().orEmpty()
            val pass = fields["P"].orEmpty()
            val sec = when {
                t.contains("SAE") -> "SAE"
                t == "NOPASS" || pass.isEmpty() -> "NONE"
                else -> "WPA"
            }
            return HotspotInfo(ssid, pass, sec)
        }
    }
}
