package com.pocketbedrock.app

import com.pocketbedrock.app.core.LogLevel
import com.pocketbedrock.app.core.LogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogParserTest {

    @Test
    fun `strips ANSI escape sequences`() {
        assertEquals(
            "Player connected: Steve",
            LogParser.stripAnsi("\u001B[32mPlayer connected: Steve\u001B[0m"),
        )
        assertEquals("no escapes", LogParser.stripAnsi("no escapes"))
        assertEquals("bel stripped", LogParser.stripAnsi("bel\u0007 stripped"))
    }

    @Test
    fun `parses INFO WARN ERROR from BDS header`() {
        val info = LogParser.parse("[2025-11-01 12:00:00:000 INFO] Server started.", 1, 0)
        assertEquals(LogLevel.INFO, info.level)
        assertEquals("Server started.", info.text)

        val warn = LogParser.parse("[2025-11-01 12:00:00:000 WARN] Disk almost full", 2, 0)
        assertEquals(LogLevel.WARN, warn.level)

        val error = LogParser.parse("[2025-11-01 12:00:00:000 ERROR] World failed", 3, 0)
        assertEquals(LogLevel.ERROR, error.level)
    }

    @Test
    fun `lines without header fall back to RAW with full text`() {
        val line = LogParser.parse("libbox64: dynarec initialized", 4, 0)
        assertEquals(LogLevel.RAW, line.level)
        assertEquals("libbox64: dynarec initialized", line.text)
    }

    @Test
    fun `extracts player connect and disconnect events`() {
        val joined = LogParser.extractPlayerEvent("Player connected: Steve, xuid: 1234567890123456")
        assertEquals("Steve", joined?.name)
        assertEquals("1234567890123456", joined?.xuid)
        assertEquals(true, joined?.joined)

        val left = LogParser.extractPlayerEvent("Player disconnected: Alex, xuid: 42")
        assertEquals("Alex", left?.name)
        assertEquals("42", left?.xuid)
        assertEquals(false, left?.joined)

        assertNull(LogParser.extractPlayerEvent("Player Spawned: Steve xuid: 42"))
    }

    @Test
    fun `extracts version lines`() {
        assertEquals("1.21.50.10", LogParser.extractVersion("Loading GameServer version 1.21.50.10"))
        assertEquals("1.21.50", LogParser.extractVersion("Version: 1.21.50"))
        assertNull(LogParser.extractVersion("No version here"))
    }

    @Test
    fun `detects server started and graceful stop lines`() {
        assertTrue(LogParser.isServerStartedLine("Server started."))
        assertFalse(LogParser.isServerStartedLine("Starting Server..."))
        assertTrue(LogParser.isGracefulStopLine("Quit correctly"))
        assertFalse(LogParser.isGracefulStopLine("Server started."))
    }

    @Test
    fun `parses save query output lines`() {
        val file = LogParser.parseSaveQueryFileLine("  db/000001.log: 4096")
        assertEquals("db/000001.log", file?.path)
        assertEquals(4096L, file?.sizeBytes)

        assertTrue(LogParser.isSaveQueryDoneLine("#Files: 45, TotalSize: 67890"))
        assertNull(LogParser.parseSaveQueryFileLine("#Files: 45, TotalSize: 67890"))
        assertNull(LogParser.parseSaveQueryFileLine("Saving complete."))
    }
}
