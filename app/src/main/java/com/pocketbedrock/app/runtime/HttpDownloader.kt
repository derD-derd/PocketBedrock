package com.pocketbedrock.app.runtime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Blocking HTTP helper (all callers run on Dispatchers.IO). Streams to disk with
 * progress callbacks; partial files are removed on failure so a retry starts clean.
 */
class HttpDownloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun fetchText(url: String): String? = try {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use { body ->
            if (!body.isSuccessful) null else body.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    fun headExists(url: String): Boolean = try {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /**
     * Download [url] into [dest]. Returns the number of bytes written.
     * @throws IOException on network failure (partial file is deleted first).
     */
    fun downloadToFile(
        url: String,
        dest: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Long {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty body for $url")
            val total = body.contentLength().let { if (it >= 0) it else null }
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            val tmp = File(dest.parentFile, dest.name + ".part")
            var read = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            tmp.renameTo(dest)
            return read
        } catch (e: Exception) {
            File(dest.parentFile, dest.name + ".part").delete()
            throw e
        } finally {
            response.close()
        }
    }
}
