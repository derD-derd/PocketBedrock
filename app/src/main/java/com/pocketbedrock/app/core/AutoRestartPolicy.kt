package com.pocketbedrock.app.core

/**
 * Exponential-backoff restart policy: 1 s, 2 s, 4 s, 8 s ... capped at [maxDelayMs].
 * After [maxAttempts] consecutive crashes the supervisor gives up and notifies the user.
 * The counter resets once the server has stayed up for [stabilityMs].
 */
class AutoRestartPolicy(
    private val maxAttempts: Int = 5,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val stabilityMs: Long = 5 * 60_000,
) {
    private var consecutiveCrashes = 0

    fun attemptsUsed(): Int = consecutiveCrashes

    fun hasGivenUp(): Boolean = consecutiveCrashes > maxAttempts

    /** Call when the server has been online longer than [stabilityMs]. */
    fun markStable() {
        consecutiveCrashes = 0
    }

    fun isStable(uptimeMs: Long): Boolean = uptimeMs >= stabilityMs

    /**
     * Register a crash. Returns the delay before the next start attempt, or null when
     * the attempt budget is exhausted and the user must be notified instead.
     */
    fun onCrash(): Long? {
        consecutiveCrashes++
        return if (consecutiveCrashes > maxAttempts) {
            null
        } else {
            val shift = (consecutiveCrashes - 1).coerceAtMost(16)
            minOf(maxDelayMs, baseDelayMs shl shift.toInt())
        }
    }

    fun reset() {
        consecutiveCrashes = 0
    }
}
