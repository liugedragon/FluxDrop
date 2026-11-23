package com.fluxdrop.app.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("fluxdrop", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("id", null)
        ?: UUID.randomUUID().toString().replace("-", "").also { sp.edit().putString("id", it).apply() }

    var deviceName: String
        get() = sp.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(v) {
            sp.edit().putString("name", v.trim().take(32)).apply()
        }

    var autoAccept: Boolean
        get() = sp.getBoolean("auto_accept", false)
        set(v) {
            sp.edit().putBoolean("auto_accept", v).apply()
        }

    private fun defaultName(): String {
        val n = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        if (!n.isNullOrBlank()) return n.take(32)
        val brand = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return (if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model").trim().take(32)
    }
}
