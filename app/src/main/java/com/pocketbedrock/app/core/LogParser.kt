package com.pocketbedrock.app.core

/**
 * Pure parsing of BDS console output. No Android dependencies so it is fully
 * unit-testable on the JVM.
 */
object LogParser {

    /** CSI ANSI escape sequences plus the BEL terminator. */
    private val ansiRegex = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** BDS lines look like "[2025-01-01 12:00:00:123 INFO] Server started." */
    private val headerRegex = Regex(
        "^\\[(\\d{4}-\\d{2}-\\d{2}[ T][\\d:.]+)\\s+(INFO|WARN|WARNING|ERROR|ERR|FATAL)\\]\\s?(.*)$",
        RegexOption.IGNORE_CASE,
    )

    private val playerConnectRegex = Regex("Player connected: (.+?), xuid: (\\d+)")
    private val playerDisconnectRegex = Regex("Player disconnected: (.+?), xuid: (\\d+)")

    private val versionRegex = Regex("[Vv]ersion:?\\s+v?(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)")

    /** `save query` output: two-space-indented "path: length" lines. */
    private val saveQueryFileRegex = Regex("^\\s{2}(.+?):\\s*(\\d+)\\s*$")

    private val saveQueryDoneRegex = Regex("#Files:\\s*(\\d+),\\s*TotalSize:\\s*(\\d+)")

    fun stripAnsi(raw: String): String =
        raw.replace(ansiRegex, "").replace("\u0007", "")

    fun parse(rawLine: String, seq: Long, wallClockMs: Long): LogLine {
        val clean = stripAnsi(rawLine).trimEnd('\r', '\n')
        val match = headerRegex.find(clean)
        val level = when (match?.groupValues?.get(2)?.uppercase()) {
            "INFO" -> LogLevel.INFO
            "WARN", "WARNING" -> LogLevel.WARN
            "ERROR", "ERR", "FATAL" -> LogLevel.ERROR
            else -> LogLevel.RAW
        }
        val text = match?.groupValues?.get(3) ?: clean
        return LogLine(seq, wallClockMs, level, text)
    }

    data class PlayerEvent(val name: String, val xuid: String, val joined: Boolean)

    fun extractPlayerEvent(text: String): PlayerEvent? {
        playerConnectRegex.find(text)?.let {
            return PlayerEvent(name = it.groupValues[1].trim(), xuid = it.groupValues[2], joined = true)
        }
        playerDisconnectRegex.find(text)?.let {
            return PlayerEvent(name = it.groupValues[1].trim(), xuid = it.groupValues[2], joined = false)
        }
        return null
    }

    fun extractVersion(text: String): String? =
        versionRegex.find(text)?.groupValues?.get(1)

    /** BDS prints exactly "Server started." when the level is loaded and the socket is open. */
    fun isServerStartedLine(text: String): Boolean = text.trimStart().startsWith("Server started")

    /** BDS prints "Stopping server..." / "Quit correctly" on graceful shutdown. */
    fun isGracefulStopLine(text: String): Boolean =
        text.contains("Quit correctly") || text.contains("Stopping server")

    data class SaveFile(val path: String, val sizeBytes: Long)

    fun parseSaveQueryFileLine(text: String): SaveFile? {
        if (text.contains("#Files:")) return null
        val m = saveQueryFileRegex.find(text) ?: return null
        val path = m.groupValues[1].trim()
        if (path.isEmpty() || path.endsWith(":")) return null
        return SaveFile(path, m.groupValues[2].toLongOrNull() ?: return null)
    }

    fun isSaveQueryDoneLine(text: String): Boolean = saveQueryDoneRegex.containsMatchIn(text)
}
