// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoPillManagerTest {

    @Test fun snapshotAndRestore() {
        val m = UndoPillManager(timeoutMs = 5000)
        m.snapshot("before", now = 1000)
        assertTrue(m.isVisible(now = 1000))
        assertEquals("before", m.restore(now = 1000))
        assertNull(m.storedText())
        assertFalse(m.isVisible(now = 1000))
    }

    @Test fun autoDismissAfterTimeout() {
        val m = UndoPillManager(timeoutMs = 5000)
        m.snapshot("before", now = 1000)
        assertFalse(m.isVisible(now = 7000))
        assertNull(m.restore(now = 7000))
    }

    @Test fun keypressDismisses() {
        val m = UndoPillManager(timeoutMs = 5000)
        m.snapshot("before", now = 1000)
        m.onKeyPress()
        assertFalse(m.isVisible(now = 1000))
        assertNull(m.restore(now = 1000))
    }

    @Test fun supersessionReplaces() {
        val m = UndoPillManager(timeoutMs = 5000)
        m.snapshot("first", now = 1000)
        m.onNewCommit("second", now = 2000)
        assertEquals("second", m.storedText())
    }

    @Test fun restoreWhenNotVisibleReturnsNull() {
        val m = UndoPillManager(timeoutMs = 5000)
        assertNull(m.restore(now = 1000))
    }
}
