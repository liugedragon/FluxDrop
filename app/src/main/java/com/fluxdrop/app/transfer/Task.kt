package com.fluxdrop.app.transfer

import android.net.Uri
import android.os.SystemClock
import com.fluxdrop.app.core.DeviceKind
import com.fluxdrop.app.core.Direction
import com.fluxdrop.app.core.FileMeta
import com.fluxdrop.app.core.PickedFile
import com.fluxdrop.app.core.TaskSnapshot
import com.fluxdrop.app.core.TaskState
import kotlinx.coroutines.Job
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一次传输任务（一台设备 × 一批文件），内部可变，UI 只读快照 */
class Task(
    val id: String,
    val direction: Direction,
    val peerName: String,
    val peerKind: DeviceKind,
    val files: List<FileMeta>,
) {
    val total: Long = files.sumOf { it.size }
    val done = AtomicLong(0)

    @Volatile var state: TaskState = TaskState.WAITING
    @Volatile var message: String? = null
    @Volatile var currentIndex: Int = 0
    @Volatile var cancelRequested = false
    @Volatile var conn: HttpURLConnection? = null
    @Volatile var job: Job? = null

    val savedUris = CopyOnWriteArrayList<Uri>()
    val finishedParts = AtomicInteger(0)

    // 测速（只在 ticker 线程里读写）
    var lastBytes = 0L
    var lastTick = SystemClock.elapsedRealtime()
    var lastProgressAt = SystemClock.elapsedRealtime()
    var speed = 0.0

    /** 只允许从进行中的状态转到结束状态，避免「已取消」被「失败」覆盖 */
    @Synchronized
    fun finish(s: TaskState, msg: String? = null) {
        if (state.active) {
            state = s
            message = msg
        }
    }

    fun snapshot(): TaskSnapshot {
        val idx = currentIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
        return TaskSnapshot(
            id = id,
            direction = direction,
            peerName = peerName,
            peerKind = peerKind,
            fileCount = files.size,
            firstName = files.firstOrNull()?.name ?: "",
            firstMime = files.firstOrNull()?.mime ?: "",
            currentName = files.getOrNull(idx)?.name ?: "",
            currentIndex = idx,
            totalBytes = total,
            doneBytes = done.get().coerceIn(0L, total),
            speed = if (state == TaskState.TRANSFERRING) speed else 0.0,
            state = state,
            message = message,
            savedUris = savedUris.toList(),
        )
    }
}

/** 接收端会话：对方经过确认后拿到 session，再逐个上传文件 */
internal class InSession(val id: String, val files: List<FileMeta>, val task: Task) {
    val received = AtomicInteger(0)
}

/** 推送给浏览器的文件：浏览器轮询到后自己来下载 */
internal class PushItem(
    val id: String,
    val cid: String,
    val file: PickedFile,
    val task: Task,
    val index: Int,
) {
    val createdAt = SystemClock.elapsedRealtime()
    @Volatile var delivered = false
}
