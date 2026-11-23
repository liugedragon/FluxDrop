package com.fluxdrop.app.core

import android.net.Uri

const val HTTP_PORT = 47820
const val UDP_PORT = 47821
const val APP_MAGIC = "fluxdrop"
const val BUF_SIZE = 256 * 1024

enum class DeviceKind {
    PHONE, TABLET, PC, WEB;

    companion object {
        fun parse(s: String?): DeviceKind =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: PHONE
    }
}

/** 雷达上的一台设备。viaWeb=true 表示它是打开了网页版的浏览器（电脑）。 */
data class Device(
    val id: String,
    val name: String,
    val model: String,
    val ip: String,
    val port: Int,
    val kind: DeviceKind,
    val viaWeb: Boolean = false,
    val manual: Boolean = false,
)

data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
)

data class FileMeta(val name: String, val size: Long, val mime: String)

enum class Direction { OUT, IN }

enum class TaskState {
    WAITING, TRANSFERRING, DONE, REJECTED, FAILED, CANCELED;

    val active: Boolean get() = this == WAITING || this == TRANSFERRING
}

/** UI 读取的传输任务快照（不可变，便于 Compose 比较） */
data class TaskSnapshot(
    val id: String,
    val direction: Direction,
    val peerName: String,
    val peerKind: DeviceKind,
    val fileCount: Int,
    val firstName: String,
    val firstMime: String,
    val currentName: String,
    val currentIndex: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val speed: Double,
    val state: TaskState,
    val message: String?,
    val savedUris: List<Uri>,
) {
    val progress: Float
        get() = when {
            state == TaskState.DONE -> 1f
            totalBytes <= 0L -> 0f
            else -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        }
}

data class IncomingRequest(
    val id: String,
    val senderName: String,
    val senderKind: DeviceKind,
    val files: List<FileMeta>,
    val totalBytes: Long,
    val createdAt: Long,
)

data class Identity(
    val id: String,
    val name: String,
    val model: String,
    val kind: DeviceKind,
    val port: Int,
)
