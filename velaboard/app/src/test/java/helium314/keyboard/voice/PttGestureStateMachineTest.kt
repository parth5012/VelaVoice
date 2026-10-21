// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PttGestureStateMachineTest {

    @Test fun pttDisabledShortPressInsertsSpace() {
        val sm = PttGestureStateMachine(pttEnabled = false)
        sm.onDown()
        assertEquals(PttGestureStateMachine.State.PRESSED, sm.state)
        val a = sm.onUp()
        assertEquals(PttGestureStateMachine.UpAction.LANGUAGE_SWITCH, a)
    }

    @Test fun pttEnabledLongPressStartsRecording() {
        val sm = PttGestureStateMachine(pttEnabled = true)
        sm.onDown()
        sm.onLongPressTimeout()
        assertEquals(PttGestureStateMachine.State.RECORDING, sm.state)
        assertTrue(sm.recordingStarted)
        val a = sm.onUp()
        assertEquals(PttGestureStateMachine.UpAction.STOP_RECORDING, a)
    }

    @Test fun swipeWinsBeforeLongPress() {
        val sm = PttGestureStateMachine(pttEnabled = true)
        sm.onDown()
        val consumed = sm.onMove(30, 0)
        assertTrue(consumed)
        assertEquals(PttGestureStateMachine.State.SWIPING, sm.state)
        // long-press timeout after swipe does nothing
        sm.onLongPressTimeout()
        assertEquals(PttGestureStateMachine.State.SWIPING, sm.state)
        assertFalse(sm.recordingStarted)
        val a = sm.onUp()
        assertEquals(PttGestureStateMachine.UpAction.END_SWIPE, a)
    }

    @Test fun shortPressInsertsSpaceWhenPttEnabled() {
        val sm = PttGestureStateMachine(pttEnabled = true)
        sm.onDown()
        val a = sm.onUp()
        assertEquals(PttGestureStateMachine.UpAction.INSERT_SPACE, a)
    }

    @Test fun resetReturnsIdle() {
        val sm = PttGestureStateMachine(pttEnabled = true)
        sm.onDown()
        sm.onLongPressTimeout()
        sm.reset()
        assertEquals(PttGestureStateMachine.State.IDLE, sm.state)
        assertFalse(sm.recordingStarted)
    }

    @Test fun smallMoveDoesNotTriggerSwipe() {
        val sm = PttGestureStateMachine(pttEnabled = true)
        sm.onDown()
        val consumed = sm.onMove(5, 0)
        assertFalse(consumed)
        assertEquals(PttGestureStateMachine.State.PRESSED, sm.state)
    }
}
