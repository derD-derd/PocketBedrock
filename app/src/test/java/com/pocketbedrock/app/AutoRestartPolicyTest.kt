package com.pocketbedrock.app

import com.pocketbedrock.app.core.AutoRestartPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRestartPolicyTest {

    @Test
    fun `exponential backoff doubles then gives up after five attempts`() {
        val policy = AutoRestartPolicy(maxAttempts = 5, baseDelayMs = 1_000, maxDelayMs = 60_000)
        assertEquals(1_000L, policy.onCrash())
        assertEquals(2_000L, policy.onCrash())
        assertEquals(4_000L, policy.onCrash())
        assertEquals(8_000L, policy.onCrash())
        assertEquals(16_000L, policy.onCrash())
        assertNull(policy.onCrash()) // budget exhausted -> supervisor notifies the user
        assertTrue(policy.hasGivenUp())
    }

    @Test
    fun `delays are capped at maxDelay`() {
        val policy = AutoRestartPolicy(maxAttempts = 99, baseDelayMs = 1_000, maxDelayMs = 60_000)
        repeat(20) {
            val delay = policy.onCrash()!!
            assertTrue(delay <= 60_000)
        }
    }

    @Test
    fun `reset clears the counter`() {
        val policy = AutoRestartPolicy()
        repeat(5) { policy.onCrash() }
        policy.reset()
        assertEquals(0, policy.attemptsUsed())
        assertFalse(policy.hasGivenUp())
        assertEquals(1_000L, policy.onCrash())
    }

    @Test
    fun `stability window regenerates the budget`() {
        val policy = AutoRestartPolicy(maxAttempts = 5)
        repeat(5) { policy.onCrash() }
        policy.markStable()
        assertEquals(1_000L, policy.onCrash())
        assertTrue(policy.isStable(6 * 60_000))
        assertFalse(policy.isStable(60_000))
    }
}
