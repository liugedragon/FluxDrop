package com.fluxdrop.app

import android.app.Application
import com.fluxdrop.app.transfer.TransferEngine

class FluxApp : Application() {
    lateinit var engine: TransferEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = TransferEngine(this)
    }

    companion object {
        lateinit var instance: FluxApp
            private set
        val engine: TransferEngine get() = instance.engine
    }
}

/** 界面是否在前台（决定是否弹出「有人给你发文件」通知） */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
