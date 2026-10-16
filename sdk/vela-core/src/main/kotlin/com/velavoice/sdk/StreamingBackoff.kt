package com.velavoice.sdk

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential reconnect backoff with jitter and a max-attempt cap
 * (map #105 ticket #111).
 *
 * A hard-down network must not hot-loop reconnect attempts inside the IME
 * process: each attempt waits longer than the last, attempts stop after
 * [MAX_ATTEMPTS], and exhaustion surfaces as a user-visible error instead
 * of silence (see `CloudStreamingTranscriber`).
 *
 * Named constants and their rationale:
 * - [BASE_DELAY_MS] (`1000ms`): first retry waits a beat so a transient
 *   blip (captive portal, tunnel flap) can clear without hammering it.
 * - [MAX_DELAY_MS] (`30000ms`): caps the exponential so a long outage
 *   retries at most every 30 s — kind to radio/battery, still responsive.
 * - [MAX_ATTEMPTS] (`5`): total reconnect tries before giving up; ~1+2+4+8+16
 *   s of spaced attempts is enough signal without an infinite loop.
 * - [JITTER_FRACTION] (`0.2`): ±20% uniform jitter so a fleet of devices
 *   does not retry in lockstep (thundering herd) after a server outage.
 *
 * Pure Kotlin (no Android APIs) so the sequence is unit-testable. Pass an
 * explicit [jitterUnit] in `[-1, 1]` for deterministic tests; production
 * callers use the `Random` overload.
 */
object StreamingBackoff {
    /** First-retry delay. */
    const val BASE_DELAY_MS = 1000L
    /** Cap for any single retry delay. */
    const val MAX_DELAY_MS = 30000L
    /** Total reconnect tries before surfacing the error. */
    const val MAX_ATTEMPTS = 5
    /** Symmetric jitter fraction applied to every delay. */
    const val JITTER_FRACTION = 0.2

    /**
     * Delay before reconnect attempt [attempt] (1-based). Deterministic in
     * [jitterUnit]: `-1` = full negative, `0` = center, `+1` = full positive.
     */
    fun delayForAttempt(attempt: Int, jitterUnit: Double = 0.0): Long {
        val n = attempt.coerceAtLeast(1)
        val center = (BASE_DELAY_MS.toDouble() * 2.0.pow((n - 1).toDouble()))
            .coerceAtMost(MAX_DELAY_MS.toDouble())
        val j = jitterUnit.coerceIn(-1.0, 1.0) * JITTER_FRACTION * center
        return (center + j).toLong().coerceIn(0L, MAX_DELAY_MS)
    }

    /** Delay before reconnect attempt [attempt] with [random] jitter. */
    fun delayForAttempt(attempt: Int, random: Random): Long =
        delayForAttempt(attempt, jitterUnit = random.nextDouble(-1.0, 1.0))

    /** True while reconnect attempt [attempt] (1-based) is still allowed. */
    fun shouldRetry(attempt: Int): Boolean = attempt in 1..MAX_ATTEMPTS
}
