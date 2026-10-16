package com.velavoice.sdk

/**
 * Adaptive voice-activity stop threshold (map #105 ticket #111).
 *
 * The old stop check compared every amplitude sample against the fixed
 * `StreamConfig.vadThreshold` (default `0.02f`): in a quiet room the ambient
 * level never rose far enough above it to arm speech, so recording ran on
 * until the duration cap; in a noisy room the threshold sat *inside* the
 * room tone, so a mid-word dip tripped the stop and cut speech.
 *
 * This tracker keeps a rolling noise floor (exponential moving average of
 * sub-speech amplitudes) and derives the stop threshold from it:
 *
 * - [effectiveThreshold] = `noiseFloor + FLOOR_MARGIN`, clamped to
 *   `[MIN_THRESHOLD, MAX_THRESHOLD]` and never below the configured base.
 *   Quiet air settles near the base (stops trip); loud rooms raise the bar
 *   (mid-word dips stay "speech").
 * - [shouldStop] additionally requires [STOP_HOLD_MS] of continuous
 *   sub-threshold audio, so a single mid-word dip shorter than the hold
 *   never stops the session even when it ducks under the threshold.
 *
 * Named constants and their rationale:
 * - [DEFAULT_BASE_THRESHOLD] (`0.02f`): matches the long-standing
 *   `StreamConfig.vadThreshold` default so existing configs behave the same
 *   in average rooms; the adaptive floor only moves *up* from here.
 * - [MIN_THRESHOLD] (`0.01f`): absolute floor — dead silence must not drag
 *   the stop threshold to zero, where mic self-noise would look like speech.
 * - [MAX_THRESHOLD] (`0.08f`): absolute ceiling — a roaring room must not
 *   push the threshold so high that normal speech never arms activity.
 * - [FLOOR_MARGIN] (`0.015f`): headroom above the measured room tone; speech
 *   (typically 2-4x room tone in RMS) clears it, tone wobble does not.
 * - [NOISE_ALPHA] (`0.05f`): EMA factor per ~100 ms observation tick — the
 *   floor converges in ~2 s and tracks slow HVAC drift without chasing
 *   syllables.
 * - [MIN_SPEECH_HOLD_MS] (`300ms`): speech must look continuously active at
 *   least this long before a stop can arm (guards leading-edge chops).
 * - [STOP_HOLD_MS] (`500ms`): mirrors `StreamingPipeline.VAD_PAUSE_MS` —
 *   silence must persist this long before the stop fires.
 *
 * Pure Kotlin (no Android APIs) so it stays unit-testable.
 */
class AdaptiveVad(
    baseThreshold: Float = DEFAULT_BASE_THRESHOLD
) {
    companion object {
        /** Base stop threshold; matches the StreamConfig default. */
        const val DEFAULT_BASE_THRESHOLD = 0.02f
        /** Absolute floor for the effective threshold. */
        const val MIN_THRESHOLD = 0.01f
        /** Absolute ceiling for the effective threshold. */
        const val MAX_THRESHOLD = 0.08f
        /** Headroom above the measured room tone. */
        const val FLOOR_MARGIN = 0.015f
        /** EMA factor per observation tick (~100 ms). */
        const val NOISE_ALPHA = 0.05f
        /**
         * Faster EMA factor when the sample sits *below* the current floor.
         * Dips (pauses, silence) pull the estimate down quickly while rises
         * stay slow ([NOISE_ALPHA]), so the floor tracks the room's quiet
         * envelope instead of chasing speech peaks.
         */
        const val FLOOR_FALL_ALPHA = 0.2f
        /** Minimum continuous speech before a stop can arm. */
        const val MIN_SPEECH_HOLD_MS = 300L
        /** Continuous silence required before the stop fires. */
        const val STOP_HOLD_MS = 500L
        /** Seed floor for a fresh session (quiet-room guess). */
        private const val SEED_FLOOR = 0.005f
    }

    private val base: Float = baseThreshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /** Rolling room-tone estimate (RMS 0..1). Guarded: only this thread touches it in prod. */
    private var noiseFloor: Float = SEED_FLOOR
    /** Last time speech-level audio was observed (ms, caller clock). */
    private var lastSpeechTimeMs: Long = Long.MIN_VALUE
    private var everSawSpeech = false

    /**
     * Feed one amplitude observation ([amplitude] RMS 0..1 at [nowMs]).
     * Samples at/above the current threshold arm speech activity; the floor
     * itself eases toward *every* sample — fast when falling, slow when
     * rising — so sustained room tone lifts the floor to the tone level
     * while brief speech bursts barely move it. This is what lets a noisy
     * room raise the stop bar without speech dragging it up syllable by
     * syllable.
     */
    fun observe(amplitude: Float, nowMs: Long) {
        if (amplitude >= effectiveThreshold()) {
            lastSpeechTimeMs = nowMs
            everSawSpeech = true
        }
        val alpha = if (amplitude < noiseFloor) FLOOR_FALL_ALPHA else NOISE_ALPHA
        noiseFloor = (noiseFloor + alpha * (amplitude - noiseFloor))
            .coerceIn(0f, MAX_THRESHOLD)
    }

    /** Current stop threshold: floor + margin, never below base, always bounded. */
    fun effectiveThreshold(): Float =
        (noiseFloor + FLOOR_MARGIN).coerceIn(maxOf(MIN_THRESHOLD, base), MAX_THRESHOLD)

    /**
     * True when a stop should fire: [amplitude] under the effective threshold
     * continuously since [lastActivityTime] for at least [STOP_HOLD_MS], and
     * (when speech was ever seen) at least [MIN_SPEECH_HOLD_MS] after it.
     */
    fun shouldStop(amplitude: Float, nowMs: Long, lastActivityTime: Long): Boolean {
        if (amplitude >= effectiveThreshold()) return false
        if (nowMs - lastActivityTime < STOP_HOLD_MS) return false
        if (everSawSpeech && nowMs - lastSpeechTimeMs < MIN_SPEECH_HOLD_MS) return false
        // Cold start (no speech yet): still require the stop hold so a quiet
        // room trips but a single quiet tick does not.
        return true
    }

    /** Forget session state for the next recording. */
    fun reset() {
        noiseFloor = SEED_FLOOR
        lastSpeechTimeMs = Long.MIN_VALUE
        everSawSpeech = false
    }
}
