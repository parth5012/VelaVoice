package com.velavoice.sdk

import android.content.ClipData
import android.content.ClipboardManager

/**
 * Check if the primary clip can be safely read from the clipboard manager.
 * Returns false when restricted (e.g. background service without focus) or empty.
 */
fun canReadPrimaryClip(clipboard: ClipboardManager?): Boolean {
    if (clipboard == null) return false
    return try {
        clipboard.hasPrimaryClip() && clipboard.primaryClip != null
    } catch (e: Exception) {
        false
    }
}

/**
 * Execute a clipboard paste action while guaranteeing that prior clipboard
 * content is restored when done (Finding F12).
 *
 * This prevents transcript text inserted via ACTION_PASTE from permanently
 * clobbering the user's prior clipboard (such as copied passwords, URLs, or notes)
 * or lingering indefinitely as readable plain text on the system clipboard.
 *
 * If the prior clip cannot be verified/read (e.g. background service restrictions),
 * this helper refuses to clobber or clear the clipboard and returns false.
 */
inline fun performWithClipboardRestore(
    clipboard: ClipboardManager,
    tempClip: ClipData,
    pasteAction: () -> Unit
): Boolean {
    val priorClip: ClipData? = try {
        if (clipboard.hasPrimaryClip()) clipboard.primaryClip else null
    } catch (e: Exception) {
        null
    }

    if (priorClip == null) {
        // A restricted read or null clip must NOT be treated as confirmed emptiness.
        // Never clear the clipboard; do not proceed with clipboard clobbering if we cannot restore.
        return false
    }

    clipboard.setPrimaryClip(tempClip)
    try {
        pasteAction()
        return true
    } finally {
        try {
            clipboard.setPrimaryClip(priorClip)
        } catch (e: Exception) {
            // Best effort restore
        }
    }
}
