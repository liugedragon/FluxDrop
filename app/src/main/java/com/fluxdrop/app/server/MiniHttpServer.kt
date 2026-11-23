package com.fluxdrop.app.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 嵌在手机里的微型 Web 服务（HTTP/1.1，一次请求一个连接）。
 * 请求体以流的形式交给处理器，所以多大的文件都不会占内存。
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long,
    val remoteIp: String,
) {
    fun readBodyText(max: Int = 1 shl 20): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("body too large")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

class HttpResponder(private val out: OutputStream) {
    var sent = false
        private set

    private fun head(status: Int, type: String, length: Long, extra: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        sb.append("Content-Type: ").append(type).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Server: FluxDrop\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        sent = true
    }

    fun bytes(status: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        head(status, type, body.size.toLong(), extra)
        out.write(body)
        out.flush()
    }

    fun json(status: Int, obj: JSONObject) =
        bytes(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    /** 流式响应：先写头，再把内容边读边写出去 */
    fun stream(
        status: Int,
        type: String,
        length: Long,
        extra: Map<String, String> = emptyMap(),
        body: (OutputStream) -> Unit,
    ) {
        head(status, type, length, extra)
        body(out)
        out.flush()
    }

    private fun reason(s: Int) = when (s) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        410 -> "Gone"
        else -> "Internal Server Error"
    }
}

/** 只读 Content-Length 个字节，防止读穿到下一个请求 */
class LimitedInputStream(private val src: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = src.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(src.available().toLong(), remaining).toInt()
}

class MiniHttpServer(
    private val port: Int,
    private val scope: CoroutineScope,
    private val handler: suspend (HttpRequest, HttpResponder) -> Unit,
) {
    @Volatile
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.receiveBufferSize = 1 shl 20
            ss.bind(InetSocketAddress(port), 128)
            server = ss
            acceptJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = try {
                        ss.accept()
                    } catch (e: IOException) {
                        break
                    }
                    // 每个连接一个协程，互不阻塞 —— 多台设备可以同时传
                    launch { serve(client) }
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private suspend fun serve(socket: Socket) {
        try {
            socket.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 60_000
                s.sendBufferSize = 1 shl 20
                val input = BufferedInputStream(s.getInputStream(), BUF)
                val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val req = parse(input, s.inetAddress?.hostAddress ?: "") ?: return
                val res = HttpResponder(output)
                try {
                    handler(req, res)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!res.sent) res.json(500, JSONObject().put("error", e.message ?: "error"))
                }
                if (!res.sent) res.json(404, JSONObject().put("error", "not found"))
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.size() == 0) null else String(sb.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 16 * 1024) throw IOException("header too long")
        }
        return String(sb.toByteArray(), Charsets.UTF_8)
    }

    private fun parse(input: InputStream, ip: String): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            if (++count > 100) return null
        }
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        val len = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return HttpRequest(method, path, query, headers, LimitedInputStream(input, len), len, ip)
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) decode(it) to "" else decode(it.substring(0, i)) to decode(it.substring(i + 1))
        }

    private fun decode(s: String) = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private companion object {
        const val BUF = 256 * 1024
    }
}
