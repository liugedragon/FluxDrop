package com.fluxdrop.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fluxdrop.app.AppVisibility
import com.fluxdrop.app.FluxApp
import com.fluxdrop.app.MainActivity
import com.fluxdrop.app.R
import com.fluxdrop.app.core.Fmt
import com.fluxdrop.app.core.IncomingRequest
import com.fluxdrop.app.core.TaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 前台服务：切到后台、锁屏时也能继续收发，并在通知栏显示实时速度 */
class TransferService : Service() {

    companion object {
        private const val CH_STATUS = "flux_status"
        private const val CH_REQUEST = "flux_request"
        private const val ID_STATUS = 1001
        private const val ID_REQUEST = 1002
        private val stopRequested = MutableStateFlow(false)

        fun start(ctx: Context) {
            stopRequested.value = false
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
            } catch (_: Exception) {
            }
        }

        /** 用户退出界面后：等传输全部结束再关闭服务 */
        fun stopWhenIdle() {
            stopRequested.value = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotify = 0L
    private var observing = false

    private val nm: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, ID_STATUS, buildStatus(FluxApp.engine.taskList.value), type)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLock()
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        val engine = FluxApp.engine
        scope.launch {
            combine(engine.taskList, stopRequested) { list, stop -> list to stop }.collect { (list, stop) ->
                val active = list.count { it.state.active }
                if (stop && active == 0) {
                    shutdown()
                    return@collect
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotify > 1000 || active == 0) {
                    lastNotify = now
                    nm.notify(ID_STATUS, buildStatus(list))
                }
            }
        }
        scope.launch {
            engine.incoming.collect { list ->
                val r = list.lastOrNull()
                if (r == null) nm.cancel(ID_REQUEST)
                else if (!AppVisibility.foreground) nm.notify(ID_REQUEST, buildRequest(r))
            }
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildStatus(list: List<TaskSnapshot>): Notification {
        val active = list.filter { it.state.active }
        val b = NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setColor(0xFF8B5CF6.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
        if (active.isEmpty()) {
            b.setContentTitle("闪渡已就绪")
                .setContentText("附近的设备可以随时给你发送文件")
        } else {
            val total = active.sumOf { it.totalBytes }
            val done = active.sumOf { it.doneBytes }
            val speed = active.sumOf { it.speed }
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            b.setContentTitle("正在传输 ${active.size} 个任务 · ${Fmt.speed(speed)}")
                .setContentText("${Fmt.bytes(done)} / ${Fmt.bytes(total)}")
                .setProgress(100, pct, false)
        }
        return b.build()
    }

    private fun buildRequest(r: IncomingRequest): Notification =
        NotificationCompat.Builder(this, CH_REQUEST)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setColor(0xFF22D3EE.toInt())
            .setContentTitle("${r.senderName} 想发送 ${r.files.size} 个文件")
            .setContentText("共 ${Fmt.bytes(r.totalBytes)}，点击查看并接收")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "传输状态", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REQUEST, "接收请求", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireLock() {
        if (wifiLock != null) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "fluxdrop:transfer").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun shutdown() {
        FluxApp.engine.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        nm.cancel(ID_REQUEST)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopWhenIdle()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdown()
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        super.onDestroy()
    }
}
