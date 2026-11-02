package com.velavoice.sdk

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build

/**
 * Execute a clipboard paste action while guaranteeing that prior clipboard
 * content is restored (or cleared if previously empty) when done (Finding F12).
 *
 * This prevents transcript text inserted via ACTION_PASTE from permanently
 * clobbering the user's prior clipboard (such as copied passwords, URLs, or notes)
 * or lingering indefinitely as readable plain text on the system clipboard.
 */
/**
 * Check if the primary clip can be safely read from the clipboard manager.
 * Returns false when restricted (e.g. background service without focus).
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
 * content is restored (or cleared if previously confirmed empty) when done (Finding F12).
 *
 * This prevents transcript text inserted via ACTION_PASTE from permanently
 * clobbering the user's prior clipboard (such as copied passwords, URLs, or notes)
 * or lingering indefinitely as readable plain text on the system clipboard.
 */
inline fun performWithClipboardRestore(
    clipboard: ClipboardManager,
    tempClip: ClipData,
    pasteAction: () -> Unit
) {
    var readSuccess = false
    val priorClip: ClipData? = try {
        if (clipboard.hasPrimaryClip()) {
            val clip = clipboard.primaryClip
            readSuccess = true
            clip
        } else {
            readSuccess = true
            null
        }
    } catch (e: Exception) {
        readSuccess = false
        null
    }

    clipboard.setPrimaryClip(tempClip)
    try {
        pasteAction()
    } finally {
        try {
            if (priorClip != null) {
                clipboard.setPrimaryClip(priorClip)
            } else if (readSuccess) {
                // Only clear if reading the clipboard succeeded and confirmed it was empty
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        } catch (e: Exception) {
            // Best effort restore
        }
    }
}
