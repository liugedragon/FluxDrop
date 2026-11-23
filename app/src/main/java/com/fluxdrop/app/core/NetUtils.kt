package com.fluxdrop.app.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object NetUtils {

    data class LocalAddr(val ip: String, val broadcast: String?, val iface: String) {
        /** 是否是本机开出来的热点网卡 */
        val isHotspot: Boolean
            get() = iface.startsWith("ap") || iface.startsWith("swlan") ||
                iface.contains("softap") || iface == "wlan1" || iface == "wlan2"
    }

    /** 列出本机所有可用于局域网的 IPv4 地址（排除蜂窝、VPN、回环） */
    fun localIpv4(): List<LocalAddr> {
        val out = mutableListOf<LocalAddr>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: ""
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("dummy") || name.startsWith("tun") ||
                    name.startsWith("ppp") || name.startsWith("v4-")
                ) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        val bc = ia.broadcast?.hostAddress ?: calcBroadcast(a, ia.networkPrefixLength.toInt())
                        out += LocalAddr(ip, bc, name)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinctBy { it.ip }.sortedBy { priority(it.iface) }
    }

    private fun calcBroadcast(a: Inet4Address, prefix: Int): String? {
        if (prefix !in 1..30) return null
        val b = a.address
        val ip = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        val mask = -1 shl (32 - prefix)
        val bc = (ip and mask) or mask.inv()
        return "${(bc ushr 24) and 0xff}.${(bc ushr 16) and 0xff}.${(bc ushr 8) and 0xff}.${bc and 0xff}"
    }

    private fun priority(n: String) = when {
        n == "wlan0" -> 0
        n.startsWith("wlan") -> 1
        n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap") -> 2
        n.startsWith("eth") -> 3
        else -> 4
    }
}

object Fmt {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        val pattern = when {
            v >= 100 -> "%.0f %s"
            v >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(Locale.US, pattern, v, units[i])
    }

    fun speed(bps: Double): String = bytes(bps.toLong().coerceAtLeast(0)) + "/s"

    fun eta(sec: Long): String = when {
        sec < 0 -> "--"
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}时${(sec % 3600) / 60}分"
    }
}
