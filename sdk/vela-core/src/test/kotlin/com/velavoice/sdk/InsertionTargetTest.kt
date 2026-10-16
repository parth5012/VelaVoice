package com.velavoice.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #109: insertion must re-validate the target node id
 * immediately before acting — stale/disappeared nodes are a clean no-op —
 * so dictation can never spray text into a different app's field when focus
 * moved mid-capture.
 *
 * [shouldInsertIntoTarget] is the pure, unit-testable verdict over the
 * node identity captured at session start vs the identity resolved at
 * insert time. The VAS insert paths capture via `captureSessionTarget()` and
 * gate every write on this verdict (plus [AccessibilityNodeInfo.refresh()]
 * freshness — services cannot be instantiated in unit tests, so the refresh
 * wiring is pinned by the source-contract test alongside this one).
 */
class InsertionTargetTest {

    private val startTarget = InsertionTarget(
        windowId = 1,
        viewIdResourceName = "com.other:id/input",
        packageName = "com.other"
    )

    @Test
    fun `same node id inserts`() {
        assertTrue(shouldInsertIntoTarget(startTarget, startTarget))
    }

    @Test
    fun `stale node id - focus moved to another app - performs no action`() {
        val otherApp = startTarget.copy(windowId = 2, packageName = "com.evil")
        assertFalse(shouldInsertIntoTarget(startTarget, otherApp))
    }

    @Test
    fun `stale node id - focus moved to another field - performs no action`() {
        val otherField = startTarget.copy(viewIdResourceName = "com.other:id/other")
        assertFalse(shouldInsertIntoTarget(startTarget, otherField))
    }

    @Test
    fun `disappeared node - nothing focused at insert time - performs no action`() {
        assertFalse(shouldInsertIntoTarget(startTarget, null))
    }

    @Test
    fun `unknown start - no focus captured - falls back to the fresh node`() {
        // Fail-open is deliberate and documented: with no start identity
        // there is nothing to prove stale, so the refresh() freshness check
        // at the write site is the guard. A KNOWN start that no longer
        // matches is always a no-op (cases above).
        assertTrue(shouldInsertIntoTarget(null, startTarget))
        assertFalse(shouldInsertIntoTarget(null, null))
    }
}
