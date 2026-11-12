package com.pocketbedrock.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * In-memory ring buffer of the last [capacity] log lines with a batched Compose-facing
 * StateFlow, plus a per-line SharedFlow used by awaitLine() for event-driven matching
 * (backups, boot detection, ban enforcement).
 *
 * Older lines are handed to [RotatingLogWriter]; nothing is ever polled.
 */
class LogStore(
    private val scope: CoroutineScope,
    private val diskDir: File?,
    private val capacity: Int = 2_000,
    private val flushIntervalMs: Long = 150,
    retentionFiles: Int = 5,
    maxFileBytes: Long = 1_000_000,
) {
    private val buffer = ArrayDeque<LogLine>(capacity)
    private var nextSeq = 0L
    private var dirty = false

    private val writer = diskDir?.let { RotatingLogWriter(it, maxFileBytes, retentionFiles, scope) }

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    private val _lineFlow = MutableSharedFlow<LogLine>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val lineFlow: SharedFlow<LogLine> = _lineFlow

    private var flushJob: Job? = null

    fun start() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            // 6-7 housekeeping wake-ups per second at most; zero work when nothing changed.
            while (isActive) {
                if (dirty) {
                    dirty = false
                    _lines.value = snapshot()
                }
                delay(flushIntervalMs)
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
        writer?.close()
    }

    /** Called from the process reader thread. Parsing happens inline; it is O(line). */
    fun onRawLine(raw: String) {
        val seq = synchronized(this) { nextSeq++ }
        val line = LogParser.parse(raw, seq, System.currentTimeMillis())
        synchronized(this) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(line)
            dirty = true
        }
        writer?.append(line)
        _lineFlow.tryEmit(line)
    }

    /** App-generated lines (command echo, warnings) share the same pipeline. */
    fun appendDirect(level: LogLevel, text: String) {
        val seq = synchronized(this) { nextSeq++ }
        val line = LogLine(seq, System.currentTimeMillis(), level, text)
        synchronized(this) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(line)
            dirty = true
        }
        writer?.append(line)
        _lineFlow.tryEmit(line)
    }

    fun snapshot(): List<LogLine> = synchronized(this) { buffer.toList() }

    fun clear() {
        synchronized(this) {
            buffer.clear()
            dirty = true
        }
        _lines.value = emptyList()
    }

    fun copyLast(maxLines: Int = 500): String =
        snapshot().takeLast(maxLines).joinToString("\n") { it.text }

    /**
     * Suspend until a line matching [predicate] arrives (or the timeout elapses).
     * Event-driven: the caller coroutine is parked, nothing polls.
     */
    suspend fun awaitLine(
        predicate: (LogLine) -> Boolean,
        timeoutMs: Long,
    ): LogLine? = withTimeoutOrNull(timeoutMs) {
        _lineFlow.first { predicate(it) }
    }
}
