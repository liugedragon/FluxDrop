package com.fluxdrop.app.transfer

import android.os.SystemClock
import com.fluxdrop.app.core.BUF_SIZE
import com.fluxdrop.app.core.Device
import com.fluxdrop.app.core.DeviceKind
import com.fluxdrop.app.core.Direction
import com.fluxdrop.app.core.FileMeta
import com.fluxdrop.app.core.HTTP_PORT
import com.fluxdrop.app.core.IncomingRequest
import com.fluxdrop.app.core.TaskState
import com.fluxdrop.app.server.HttpRequest
import com.fluxdrop.app.server.HttpResponder
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * HTTP 接口：
 *  GET  /                    电脑浏览器网页
 *  GET  /api/info            设备信息（手动输入 IP 时校验）
 *  POST /api/session         发起传输请求（等待接收方确认）
 *  POST /api/upload          流式上传单个文件
 *  GET  /api/web/poll        浏览器心跳 + 拉取手机推送的文件
 *  GET  /api/download/{id}   浏览器下载手机推送的文件
 */
internal class ApiRoutes(private val e: TransferEngine) {

    @Volatile
    private var indexHtml: ByteArray? = null

    suspend fun handle(req: HttpRequest, res: HttpResponder) {
        val p = req.path
        when {
            req.method == "GET" && (p == "/" || p == "/index.html") -> index(res)
            p == "/favicon.ico" -> res.bytes(204, "image/x-icon", ByteArray(0))
            p == "/api/info" -> res.json(200, e.infoJson())
            req.method == "POST" && p == "/api/session" -> session(req, res)
            req.method == "POST" && p == "/api/upload" -> upload(req, res)
            p == "/api/web/poll" -> webPoll(req, res)
            req.method == "GET" && p.startsWith("/api/download/") -> download(res, p.removePrefix("/api/download/"))
            else -> res.json(404, err("not found"))
        }
    }

    private fun err(msg: String) = JSONObject().put("error", msg)

    private fun index(res: HttpResponder) {
        val bytes = indexHtml ?: e.app.assets.open("web/index.html").use { it.readBytes() }.also { indexHtml = it }
        res.bytes(200, "text/html; charset=utf-8", bytes)
    }

    // ---------------------------------------------------------------- 接收

    private suspend fun session(req: HttpRequest, res: HttpResponder) {
        val j = try {
            JSONObject(req.readBodyText())
        } catch (ex: Exception) {
            res.json(400, err("bad json"))
            return
        }
        val arr = j.optJSONArray("files") ?: JSONArray()
        val files = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                FileMeta(o.optString("name").ifBlank { "file$i" }, o.optLong("size", -1L), o.optString("mime"))
            }
        }.filter { it.size >= 0 }
        if (files.isEmpty()) {
            res.json(400, err("no files"))
            return
        }
        val sender = j.optString("senderName").ifBlank { "未知设备" }.take(40)
        val kind = DeviceKind.parse(j.optString("senderKind"))
        val id = e.newId()
        val request = IncomingRequest(id, sender, kind, files, files.sumOf { it.size }, System.currentTimeMillis())
        val accepted = if (e.autoAccept.value) true else e.awaitDecision(request)
        if (!accepted) {
            res.json(403, err("rejected"))
            return
        }
        val t = Task(id, Direction.IN, sender, kind, files)
        t.state = TaskState.TRANSFERRING
        e.addTask(t)
        e.sessions[id] = InSession(id, files, t)
        res.json(200, JSONObject().put("session", id))
    }

    private fun upload(req: HttpRequest, res: HttpResponder) {
        val s = e.sessions[req.query["session"].orEmpty()] ?: run {
            res.json(404, err("no session"))
            return
        }
        val idx = req.query["index"]?.toIntOrNull() ?: -1
        val meta = s.files.getOrNull(idx) ?: run {
            res.json(400, err("bad index"))
            return
        }
        if (req.contentLength != meta.size) {
            res.json(400, err("size mismatch"))
            return
        }
        val t = s.task
        if (!t.state.active) {
            res.json(409, err("canceled"))
            return
        }
        t.currentIndex = idx
        try {
            val uri = e.store.save(meta.name, meta.mime) { os ->
                val buf = ByteArray(BUF_SIZE)
                var left = meta.size
                while (left > 0) {
                    if (t.cancelRequested) throw IOException("已取消")
                    val n = req.body.read(buf, 0, if (left < BUF_SIZE) left.toInt() else BUF_SIZE)
                    if (n < 0) throw IOException("连接中断")
                    os.write(buf, 0, n)
                    left -= n
                    t.done.addAndGet(n.toLong())
                }
            }
            t.savedUris += uri
            if (s.received.incrementAndGet() >= s.files.size) {
                t.finish(TaskState.DONE)
                e.sessions.remove(s.id)
                e.events.tryEmit("已收到来自 ${t.peerName} 的 ${s.files.size} 个文件")
            }
            res.json(200, JSONObject().put("ok", true))
        } catch (ex: Exception) {
            if (!t.cancelRequested) t.finish(TaskState.FAILED, ex.message ?: "接收失败")
            e.sessions.remove(s.id)
            try {
                if (!res.sent) res.json(500, err(ex.message ?: "error"))
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- 浏览器

    private fun webPoll(req: HttpRequest, res: HttpResponder) {
        val cid = req.query["cid"].orEmpty().filter { it.isLetterOrDigit() }.take(40)
        if (cid.isEmpty()) {
            res.json(400, err("cid"))
            return
        }
        val name = req.query["name"].orEmpty().ifBlank { "浏览器" }.take(40)
        val os = req.query["os"].orEmpty()
        val kind = when (os) {
            "Windows", "Mac", "Linux", "ChromeOS" -> DeviceKind.PC
            "Android", "iOS" -> DeviceKind.PHONE
            else -> DeviceKind.WEB
        }
        val devId = "web-$cid"
        e.webSeen[devId] = SystemClock.elapsedRealtime()
        val dev = Device(devId, name, os, req.remoteIp, HTTP_PORT, kind, viaWeb = true)
        e.webDevices.update { if (it[devId] == dev) it else it + (devId to dev) }

        val items = JSONArray()
        val from = e.deviceName.value
        e.pushItems.values
            .filter { it.cid == cid && it.task.state.active }
            .sortedBy { it.createdAt }
            .forEach { p ->
                items.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.file.name)
                        .put("size", p.file.size)
                        .put("mime", p.file.mime)
                        .put("from", from),
                )
            }
        res.json(200, JSONObject().put("me", e.infoJson()).put("items", items))
    }

    private fun download(res: HttpResponder, id: String) {
        val p = e.pushItems[id] ?: run {
            res.json(404, err("expired"))
            return
        }
        val t = p.task
        if (!t.state.active) {
            res.json(410, err("canceled"))
            return
        }
        val f = p.file
        val count = !p.delivered
        if (count) {
            t.state = TaskState.TRANSFERRING
            t.message = null
            t.currentIndex = p.index
        }
        var sent = 0L
        try {
            val input = e.app.contentResolver.openInputStream(f.uri) ?: throw IOException("文件不可读")
            input.use { ins ->
                res.stream(
                    200,
                    f.mime.ifBlank { "application/octet-stream" },
                    f.size,
                    mapOf("Content-Disposition" to disposition(f.name)),
                ) { os ->
                    val buf = ByteArray(BUF_SIZE)
                    var left = f.size
                    while (left > 0) {
                        if (t.cancelRequested) throw IOException("已取消")
                        val n = ins.read(buf, 0, if (left < BUF_SIZE) left.toInt() else BUF_SIZE)
                        if (n < 0) throw IOException("读取中断")
                        os.write(buf, 0, n)
                        left -= n
                        sent += n
                        if (count) t.done.addAndGet(n.toLong())
                    }
                }
            }
            if (count) {
                p.delivered = true
                if (t.finishedParts.incrementAndGet() >= t.files.size) {
                    t.finish(TaskState.DONE)
                    e.events.tryEmit("${t.peerName} 已接收全部文件")
                }
            }
        } catch (ex: Exception) {
            if (count) {
                t.done.addAndGet(-sent)
                if (t.state.active && !t.cancelRequested) t.message = "电脑下载中断，可在网页上重试"
            }
            if (!res.sent) res.json(500, err(ex.message ?: "error"))
        }
    }

    private fun disposition(name: String): String {
        val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$enc"
    }
}
