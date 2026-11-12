package com.pocketbedrock.app

import com.pocketbedrock.app.core.LogLevel
import com.pocketbedrock.app.core.LogStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LogStoreTest {

    @Test
    fun `ring buffer keeps the newest 2000 lines`() = runTest {
        val store = LogStore(backgroundScope, diskDir = null, capacity = 2_000)
        store.start()
        repeat(2_500) { store.appendDirect(LogLevel.INFO, "line $it") }
        val snapshot = store.snapshot()
        assertEquals(2_000, snapshot.size)
        assertEquals("line 500", snapshot.first().text)
        assertEquals("line 2499", snapshot.last().text)
        store.stop()
    }

    @Test
    fun `awaitLine wakes on a matching line - event driven`() = runTest {
        val store = LogStore(backgroundScope, diskDir = null)
        store.start()

        var result: com.pocketbedrock.app.core.LogLine? = null
        val job = launch {
            result = store.awaitLine({ it.level == LogLevel.WARN }, timeoutMs = 60_000)
        }
        testScheduler.runCurrent() // let the collector subscribe first
        store.appendDirect(LogLevel.INFO, "not this one")
        store.appendDirect(LogLevel.WARN, "exactly this one")
        job.join()

        assertNotNull(result)
        assertEquals("exactly this one", result?.text)
        store.stop()
    }

    @Test
    fun `awaitLine returns null on timeout`() = runTest {
        val store = LogStore(backgroundScope, diskDir = null)
        store.start()
        val line = store.awaitLine({ false }, timeoutMs = 500)
        assertEquals(null, line)
        store.stop()
    }
}
