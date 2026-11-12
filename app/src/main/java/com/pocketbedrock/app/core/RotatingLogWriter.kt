package com.pocketbedrock.app.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotating on-disk log writer. The in-memory buffer keeps the last 2,000 lines; every
 * line is additionally streamed to hourly, size-capped files, keeping the newest
 * [maxFiles] files (oldest are deleted).
 *
 * Writes happen on a single dedicated coroutine fed by a Channel, so the process reader
 * thread never touches disk I/O scheduling.
 */
class RotatingLogWriter(
    dir: File,
    private val maxFileBytes: Long = 1_000_000,
    private val maxFiles: Int = 5,
    scope: CoroutineScope? = null,
) {
    private val channel = Channel<LogLine>(Channel.UNLIMITED)
    private val timestampFormat = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    private val dir = dir.apply { mkdirs() }
    private var currentFile: File? = null
    private var currentOut: FileOutputStream? = null
    private var currentBytes = 0L

    init {
        scope?.launch(Dispatchers.IO) { consumeForever() }
    }

    /** Called from the reader thread; enqueueing is lock-free and never blocks. */
    fun append(line: LogLine) {
        channel.trySend(line)
    }

    fun close() {
        channel.close()
        synchronized(this) { closeCurrent() }
    }

    private suspend fun consumeForever() {
        for (line in channel) {
            write(line)
        }
        synchronized(this) { closeCurrent() }
    }

    private fun write(line: LogLine) {
        synchronized(this) {
            try {
                if (currentFile == null || currentBytes >= maxFileBytes) rotate()
                val out = currentOut ?: return
                val ts = timestampFormat.format(Date(line.timestampMs))
                val levelTag = when (line.level) {
                    LogLevel.INFO -> "I"
                    LogLevel.WARN -> "W"
                    LogLevel.ERROR -> "E"
                    LogLevel.RAW -> "-"
                }
                val encoded = "[$ts $levelTag] ${line.text}\n".toByteArray(Charsets.UTF_8)
                out.write(encoded)
                currentBytes += encoded.size
            } catch (_: Exception) {
                // Disk full / IO error: drop the file log rather than ever blocking the server.
                closeCurrent()
            }
        }
    }

    private fun rotate() {
        closeCurrent()
        val file = File(dir, "server-${timestampFormat.format(Date())}.log")
        currentFile = file
        currentOut = try {
            FileOutputStream(file, true)
        } catch (_: Exception) {
            null
        }
        currentBytes = 0
        pruneOldFiles()
    }

    private fun pruneOldFiles() {
        val files = dir.listFiles { f -> f.name.startsWith("server-") && f.name.endsWith(".log") } ?: return
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxFiles)
            .forEach { it.delete() }
    }

    private fun closeCurrent() {
        try {
            currentOut?.close()
        } catch (_: Exception) {
        }
        currentOut = null
        currentFile = null
        currentBytes = 0
    }
}
