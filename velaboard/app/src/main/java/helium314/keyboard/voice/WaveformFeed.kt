// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/**
 * WaveformFeed (ticket #160) — pure amplitude smoothing + UI-rate shaping for
 * the voice recording pane's [com.velavoice.sdk.ui.WaveformView].
 *
 * - EMA smoothing (ease-in alpha) absorbs mic jitter into a stable bar curve.
 * - Rate gate: delivers at most one value per [minIntervalMs] (~30fps cap) so
 *   the audio callback thread cannot flood recomposition/draw work — adds no
 *   measurable audio-path latency because feeding stays non-blocking.
 * - Visual only: consumes the recorder/transcriber's existing amplitude
 *   callbacks; no second recorder, no haptics (Batch 1 #151 owns those).
 *
 * Pure logic: time is passed in by the caller ([feed]), so the class is
 * JVM-testable without Android.
 *
 * @param emaAlpha smoothing factor; higher = snappier, lower = smoother
 * @param minIntervalMs minimum gap between delivered values (33ms ≈ 30fps)
 */
class WaveformFeed(
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
    private val minIntervalMs: Long = 33L
) {
    private var smooth = 0f
    private var lastDeliveredMs = Long.MIN_VALUE + 1

    /**
     * Feed one raw normalized amplitude at time [nowMs].
     * Returns the smoothed value when a draw is due, or null when the rate
     * gate says this sample is folded into the EMA but not delivered.
     */
    fun feed(amplitude: Float, nowMs: Long): Float? {
        val raw = amplitude.coerceIn(0f, 1f)
        smooth = emaAlpha * raw + (1f - emaAlpha) * smooth
        if (nowMs - lastDeliveredMs < minIntervalMs) return null
        lastDeliveredMs = nowMs
        return smooth
    }

    /** Clear EMA history when a new recording starts. */
    fun reset() {
        smooth = 0f
        lastDeliveredMs = Long.MIN_VALUE + 1
    }

    companion object {
        const val DEFAULT_EMA_ALPHA = 0.30f
    }
}
