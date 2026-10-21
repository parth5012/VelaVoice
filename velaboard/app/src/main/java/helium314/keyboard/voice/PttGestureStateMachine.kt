// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/**
 * Pure-logic state machine for spacebar push-to-talk (ticket #150).
 *
 * Long-press space >= 350ms starts recording (PTT) when enabled; a horizontal
 * swipe that crosses the cursor threshold first wins and cancels the long-press.
 * When PTT is disabled, long-press keeps the legacy language-switch behaviour
 * (handled by the caller via [State.LONG_PRESS_LANGUAGE]).
 */
class PttGestureStateMachine(
    val pttEnabled: Boolean = true,
    val swipeThresholdPx: Int = 24
) {
    enum class State {
        IDLE, PRESSED, RECORDING, SWIPING, RELEASED
    }

    enum class UpAction {
        INSERT_SPACE, STOP_RECORDING, END_SWIPE, LANGUAGE_SWITCH
    }

    var state: State = State.IDLE
        private set

    var recordingStarted: Boolean = false
        private set

    fun onDown() {
        state = State.PRESSED
        recordingStarted = false
    }

    /**
     * Returns true if the movement was consumed as a swipe (threshold crossed).
     */
    fun onMove(dx: Int, dy: Int): Boolean {
        if (state != State.PRESSED && state != State.RECORDING) return false
        if (kotlin.math.abs(dx) >= swipeThresholdPx || kotlin.math.abs(dy) >= swipeThresholdPx) {
            if (state == State.PRESSED) {
                state = State.SWIPING
            }
            return true
        }
        return false
    }

    /** Called when the 350ms long-press timer fires. */
    fun onLongPressTimeout() {
        if (state != State.PRESSED) return
        if (pttEnabled) {
            state = State.RECORDING
            recordingStarted = true
        } else {
            // legacy: language switch on long press
            state = State.RELEASED
        }
    }

    fun onUp(): UpAction {
        val action = when (state) {
            State.RECORDING -> UpAction.STOP_RECORDING
            State.SWIPING -> UpAction.END_SWIPE
            State.PRESSED -> if (pttEnabled) UpAction.INSERT_SPACE else UpAction.LANGUAGE_SWITCH
            else -> UpAction.INSERT_SPACE
        }
        state = State.RELEASED
        return action
    }

    fun reset() {
        state = State.IDLE
        recordingStarted = false
    }
}
