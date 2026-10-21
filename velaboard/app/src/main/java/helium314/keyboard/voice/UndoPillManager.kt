// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/**
 * Single-level undo pill logic (ticket #152).
 *
 * Stores the pre-commit text of a region so a tap on the "↶ Undo" chip can
 * restore it. Auto-dismisses on next keypress, supersession, or 5s.
 */
class UndoPillManager(val timeoutMs: Long = 5000L) {

    private var storedText: String? = null
    private var expiresAt: Long = 0L
    private var visible: Boolean = false

    fun snapshot(preCommitText: String, now: Long = System.currentTimeMillis()) {
        storedText = preCommitText
        expiresAt = now + timeoutMs
        visible = true
    }

    fun storedText(): String? = storedText

    fun isVisible(now: Long = System.currentTimeMillis()): Boolean {
        if (!visible) return false
        if (now >= expiresAt) {
            dismiss()
            return false
        }
        return true
    }

    /** Tap on pill: returns stored text and clears. */
    fun restore(now: Long = System.currentTimeMillis()): String? {
        if (!isVisible(now)) return null
        val t = storedText
        dismiss()
        return t
    }

    /** Next keypress / supersession / manual dismiss. */
    fun dismiss() {
        storedText = null
        visible = false
        expiresAt = 0L
    }

    /** Call on every keypress; dismisses if visible. */
    fun onKeyPress() {
        if (visible) dismiss()
    }

    /** Supersession: a new commit replaces the pill. */
    fun onNewCommit(preCommitText: String, now: Long = System.currentTimeMillis()) {
        // replace
        snapshot(preCommitText, now)
    }
}
