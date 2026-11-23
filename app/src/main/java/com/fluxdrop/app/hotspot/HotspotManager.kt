package com.fluxdrop.app.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HotspotInfo(val ssid: String, val password: String, val security: String) {
    /** 标准 Wi-Fi 二维码格式：闪渡能扫，系统相机也能直接扫码连接 */
    fun qrPayload(): String {
        val t = when (security) {
            "SAE" -> "SAE"
            "NONE" -> "nopass"
            else -> "WPA"
        }
        return if (security == "NONE") "WIFI:T:nopass;S:${esc(ssid)};;"
        else "WIFI:T:$t;S:${esc(ssid)};P:${esc(password)};;"
    }

    private fun esc(s: String) = buildString {
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }
}

sealed interface HostState {
    data object Idle : HostState
    data object Starting : HostState
    data class Running(val info: HotspotInfo) : HostState
    data class Error(val msg: String) : HostState
}

sealed interface JoinState {
    data object Idle : JoinState
    data class Connecting(val ssid: String) : JoinState
    data class Connected(val ssid: String) : JoinState
    data class Error(val msg: String) : JoinState
}

/**
 * 免流量热点：
 *  - 发起方用 startLocalOnlyHotspot 开一个「本地专用」热点（不共享移动数据，不耗流量）
 *  - 接收方扫码后用 WifiNetworkSpecifier 连上，并把本 App 的网络绑定到这个热点
 */
class HotspotManager(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var joinCallback: ConnectivityManager.NetworkCallback? = null

    private val _host = MutableStateFlow<HostState>(HostState.Idle)
    val hostState: StateFlow<HostState> = _host.asStateFlow()
    private val _join = MutableStateFlow<JoinState>(JoinState.Idle)
    val joinState: StateFlow<JoinState> = _join.asStateFlow()

    /** 网络变化时通知雷达立刻重新广播 */
    var onNetworkChanged: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startHost() {
        val cur = _host.value
        if (cur is HostState.Running || cur is HostState.Starting) return
        leave()
        _host.value = HostState.Starting
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val info = extract(res)
                    _host.value = if (info != null) HostState.Running(info) else HostState.Error("无法读取热点信息")
                    main.postDelayed({ onNetworkChanged?.invoke() }, 1200)
                }

                override fun onStopped() {
                    reservation = null
                    _host.value = HostState.Idle
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    _host.value = HostState.Error(reasonText(reason))
                }
            }, main)
        } catch (e: SecurityException) {
            _host.value = HostState.Error("缺少权限：请允许「附近设备」或「位置信息」权限")
        } catch (e: IllegalStateException) {
            _host.value = HostState.Error("热点正被其他应用使用，请先关闭系统热点后重试")
        } catch (e: Exception) {
            _host.value = HostState.Error(e.message ?: "开启热点失败")
        }
    }

    fun stopHost() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
        if (_host.value !is HostState.Idle) _host.value = HostState.Idle
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
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "没有可用的信道，请稍后重试"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "当前模式不兼容，请先关闭系统热点/网络共享"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "系统禁止开启热点（可能被管控策略限制）"
        else -> "开启热点失败（错误码 $r），请关闭系统热点后重试"
    }

    // ---------------------- 扫码加入 ----------------------

    fun join(info: HotspotInfo) {
        leave()
        _join.value = JoinState.Connecting(info.ssid)
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
                    // 让本 App 的所有 socket 都走这个热点
                    cm.bindProcessToNetwork(network)
                    _join.value = JoinState.Connected(info.ssid)
                    main.postDelayed({ onNetworkChanged?.invoke() }, 800)
                }

                override fun onUnavailable() {
                    _join.value = JoinState.Error("没能连上「${info.ssid}」，请靠近对方后重试")
                    joinCallback = null
                }

                override fun onLost(network: Network) {
                    cm.bindProcessToNetwork(null)
                    _join.value = JoinState.Error("与「${info.ssid}」的连接已断开")
                }
            }
            joinCallback = cb
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            _join.value = JoinState.Error(e.message ?: "连接失败")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun joinLegacy(info: HotspotInfo) {
        try {
            if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
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
                _join.value = JoinState.Error("无法添加网络，请在系统 Wi-Fi 设置里手动连接「${info.ssid}」")
                return
            }
            wifi.disconnect()
            wifi.enableNetwork(id, true)
            wifi.reconnect()
            _join.value = JoinState.Connected(info.ssid)
            main.postDelayed({ onNetworkChanged?.invoke() }, 3000)
        } catch (e: Exception) {
            _join.value = JoinState.Error(e.message ?: "连接失败")
        }
    }

    fun leave() {
        joinCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        joinCallback = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        if (_join.value !is JoinState.Idle) _join.value = JoinState.Idle
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
