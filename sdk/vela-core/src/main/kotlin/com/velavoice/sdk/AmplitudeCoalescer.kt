package com.velavoice.sdk

/**
 * Coalesces per-buffer amplitude callbacks into ~15 Hz main-thread posts
 * (map #105 ticket #113).
 *
 * The mic capture threads ([AudioRecorder], [StreamingPipeline]) invoke
 * `onAmplitude` per read buffer (~50/s). Posting each sample to the main
 * thread allocates one runnable + message per buffer. This throttle keeps the
 * latest sample and grants at most one main-thread post per [intervalMs]:
 * the owner posts a SINGLE reusable runnable (guarded by [pending]) that
 * drains the latest value via [poll].
 *
 * Contract:
 * - [offer] runs on the capture thread: always stores the latest sample,
 *   returns true only when the caller must (re)queue its shared runnable.
 *   At most one grant is outstanding at a time ([pending]), so bursts
 *   coalesce into the already-queued runnable instead of stacking messages.
 * - Hidden panes ([visible] == false) grant nothing — the post is dropped,
 *   not just the redraw (the views' own `isShown` gate from #112 stays as
 *   the second line of defence). The latest sample is still stored so the
 *   next visible offer (or [poll]) picks it up.
 * - The VAD path is untouched: [StreamingPipeline] calls
 *   `adaptiveVad.observe()` per tick BEFORE/around this throttle (#111) —
 *   coalescing applies ONLY to the main-thread UI post, never to detection.
 * - Call [reset] at session start so a new session's first bar is not
 *   throttled by the previous session's [lastEmitMs].
 *
 * Pure Kotlin (no Android APIs) so it stays unit-testable.
 */
class AmplitudeCoalescer(
    val intervalMs: Long = UI_INTERVAL_MS
) {
    companion object {
        /** ~15 Hz UI refresh ceiling for the waveform meter. */
        const val UI_INTERVAL_MS = 66L
    }

    private val lock = Any()

    /** Latest coalesced sample (capture-thread writes, main-thread reads). */
    @Volatile
    var latest: Float = 0f
        private set

    @Volatile
    private var lastEmitMs: Long = Long.MIN_VALUE

    @Volatile
    private var pending: Boolean = false

    /**
     * Capture-thread entry. Stores [amplitude] and returns true when the
     * caller must queue its single shared runnable on the main thread.
     * Returns false when the post coalesces into an already-queued runnable,
     * is throttled by [intervalMs], or the pane is hidden ([visible]).
     */
    fun offer(amplitude: Float, nowMs: Long, visible: Boolean): Boolean {
        latest = amplitude.coerceIn(0f, 1f)
        if (!visible) return false
        synchronized(lock) {
            if (pending) return false
            val last = lastEmitMs
            if (last != Long.MIN_VALUE && nowMs - last < intervalMs) return false
            pending = true
            return true
        }
    }

    /**
     * Main-thread drain for the shared runnable: clears [pending], stamps
     * [lastEmitMs], and returns the latest sample — or null when nothing was
     * granted (a stale/duplicate runnable firing must draw nothing).
     */
    fun poll(nowMs: Long): Float? {
        synchronized(lock) {
            if (!pending) return null
            pending = false
            lastEmitMs = nowMs
        }
        return latest
    }

    /** Forget session state so the next session's first bar posts immediately. */
    fun reset() {
        synchronized(lock) {
            pending = false
            lastEmitMs = Long.MIN_VALUE
        }
        latest = 0f
    }
}
