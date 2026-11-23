package com.fluxdrop.app.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** 收到的文件统一存到 手机存储/Download/FluxDrop */
class FileStore(private val context: Context) {

    companion object {
        const val FOLDER = "FluxDrop"
        private const val BUF = 1024 * 1024
    }

    fun save(name: String, mimeHint: String, writer: (OutputStream) -> Unit): Uri {
        val safe = sanitize(name)
        val mime = guessMime(safe, mimeHint)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(safe, mime, writer)
        else saveLegacy(safe, writer)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveQ(name: String, mime: String, writer: (OutputStream) -> Unit): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建文件")
        try {
            val os = cr.openOutputStream(uri) ?: throw IOException("无法写入文件")
            BufferedOutputStream(os, BUF).use { writer(it) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            cr.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            try {
                cr.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(name: String, writer: (OutputStream) -> Unit): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录")
        val file = unique(dir, name)
        try {
            BufferedOutputStream(FileOutputStream(file), BUF).use { writer(it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    fun sanitize(n: String): String {
        val s = n.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim()
            .trimStart('.')
        return s.ifBlank { "file_${System.currentTimeMillis()}" }.take(200)
    }

    private fun guessMime(name: String, hint: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        return m ?: hint.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "application/octet-stream"
    }
}
