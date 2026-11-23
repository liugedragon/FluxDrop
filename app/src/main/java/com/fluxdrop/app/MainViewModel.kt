package com.fluxdrop.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fluxdrop.app.core.BUF_SIZE
import com.fluxdrop.app.core.Device
import com.fluxdrop.app.core.PickedFile
import com.fluxdrop.app.hotspot.HotspotInfo
import com.fluxdrop.app.hotspot.HotspotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val engine = FluxApp.engine

    val devices = engine.devices
    val tasks = engine.taskList
    val incoming = engine.incoming
    val deviceName = engine.deviceName
    val autoAccept = engine.autoAccept
    val addresses = engine.addresses
    val serverOn = engine.serverOn
    val activeCount = engine.activeCount
    val hostState = engine.hotspot.hostState
    val joinState = engine.hotspot.joinState
    val events = engine.events

    private val _picked = MutableStateFlow<List<PickedFile>>(emptyList())
    val picked: StateFlow<List<PickedFile>> = _picked.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** 切换底部标签页 */
    val nav = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private fun toast(s: String) {
        toasts.tryEmit(s)
    }

    // ------------------------------------------------------------ 选文件

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { resolve(it) }
            if (files.size < uris.size) toast("有 ${uris.size - files.size} 个文件无法读取")
            _picked.update { (it + files).distinctBy { f -> f.uri } }
        }
    }

    private fun resolve(uri: Uri): PickedFile? {
        val app = getApplication<Application>()
        val cr = app.contentResolver
        return try {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            var name: String? = null
            var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val finalName = name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "file_${System.currentTimeMillis()}"
            if (size >= 0) return PickedFile(uri, finalName, size, mime)

            // 大小未知（少数应用分享时不提供）：先复制到缓存，拿到准确大小
            val dir = File(app.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "${System.nanoTime()}_${engine.store.sanitize(finalName)}")
            val ins = cr.openInputStream(uri) ?: return null
            ins.use { input -> f.outputStream().use { input.copyTo(it, BUF_SIZE) } }
            PickedFile(Uri.fromFile(f), finalName, f.length(), mime)
        } catch (e: Exception) {
            null
        }
    }

    fun removeFile(f: PickedFile) {
        _picked.update { it - f }
    }

    fun clearFiles() {
        _picked.value = emptyList()
    }

    // ------------------------------------------------------------ 选设备 & 发送

    fun toggle(d: Device) {
        _selected.update { if (d.id in it) it - d.id else it + d.id }
    }

    fun refreshRadar() {
        engine.radar.refresh()
        toast("正在扫描附近设备…")
    }

    fun send() {
        val sel = _selected.value
        val targets = devices.value.filter { it.id in sel }
        val files = _picked.value
        if (targets.isEmpty() || files.isEmpty()) return
        engine.send(targets, files)
        _selected.value = emptySet()
        _picked.value = emptyList()
        nav.tryEmit(1)
    }

    fun probe(ip: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = engine.probe(ip)
            r.onSuccess { d ->
                _selected.update { it + d.id }
                toast("已添加并选中「${d.name}」")
            }.onFailure { toast(it.message ?: "连接失败") }
            onDone(r.isSuccess)
        }
    }

    // ------------------------------------------------------------ 任务

    fun decide(id: String, ok: Boolean) = engine.decide(id, ok)
    fun cancel(id: String) = engine.cancelTask(id)
    fun clearFinished() = engine.clearFinished()

    // ------------------------------------------------------------ 设置

    fun setName(n: String) = engine.setDeviceName(n)
    fun setAutoAccept(v: Boolean) = engine.setAutoAccept(v)

    // ------------------------------------------------------------ 热点

    fun startHotspot() = engine.hotspot.startHost()
    fun stopHotspot() = engine.hotspot.stopHost()
    fun leaveHotspot() = engine.hotspot.leave()
    fun restartHotspot() = engine.hotspot.restartHost()
    fun systemConnectIntent(info: HotspotInfo): Intent = engine.hotspot.systemConnectIntent(info)
    fun markSystemConnecting(info: HotspotInfo) = engine.hotspot.markSystemConnecting(info)
    fun retryJoin(info: HotspotInfo) = engine.hotspot.join(info)

    fun onScanResult(text: String?) {
        if (text.isNullOrBlank()) return
        val info = HotspotManager.parseWifiQr(text)
        when {
            info != null -> engine.hotspot.join(info)
            text.startsWith("http://", ignoreCase = true) -> probe(text)
            else -> toast("无法识别的二维码")
        }
    }
}
