package com.velavoice.sdk

/**
 * Single-gate recording session state machine for the IME/VAS services
 * (wayfinder map #97 ticket #98).
 *
 * [State.IDLE] is the only state from which a session may start; a start
 * claimed anywhere else is a no-op returning false, so a double-tap on the mic
 * or an `onStartInputView` re-entry can never open a second AudioRecord over a
 * live one. [State.RECORDING] is the only state that may stop (into
 * [State.PROCESSING], where the transcript is still being produced) or cancel
 * (straight back to [State.IDLE], audio discarded). Stop and cancel are
 * therefore idempotent: a second invoke in any order is a safe no-op, and
 * teardown runs at most once per session.
 */
class RecordingSessionGate {

    enum class State { IDLE, RECORDING, PROCESSING }

    @Volatile
    var state: State = State.IDLE
        private set

    fun isRecording(): Boolean = state == State.RECORDING

    /** True while a session may still be recording (privacy/lifecycle rechecks). */
    fun isSessionActive(): Boolean = state == State.RECORDING

    /** IDLE -> RECORDING. False (no-op) in any other state. */
    @Synchronized
    fun tryStart(): Boolean {
        if (state != State.IDLE) return false
        state = State.RECORDING
        return true
    }

    /** RECORDING -> PROCESSING. False (no-op) in any other state. */
    @Synchronized
    fun tryStop(): Boolean {
        if (state != State.RECORDING) return false
        state = State.PROCESSING
        return true
    }

    /** RECORDING -> IDLE (discard audio). False (no-op) in any other state. */
    @Synchronized
    fun tryCancel(): Boolean {
        if (state != State.RECORDING) return false
        state = State.IDLE
        return true
    }

    /** RECORDING or PROCESSING -> IDLE (session end via callback). */
    @Synchronized
    fun finish(): Boolean {
        if (state == State.IDLE) return false
        state = State.IDLE
        return true
    }

    /** Force IDLE (service teardown). */
    @Synchronized
    fun reset() {
        state = State.IDLE
    }
}
