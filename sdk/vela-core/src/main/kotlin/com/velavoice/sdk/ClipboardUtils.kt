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
inline fun performWithClipboardRestore(
    clipboard: ClipboardManager,
    tempClip: ClipData,
    pasteAction: () -> Unit
) {
    val priorClip: ClipData? = try {
        if (clipboard.hasPrimaryClip()) clipboard.primaryClip else null
    } catch (e: Exception) {
        null
    }

    clipboard.setPrimaryClip(tempClip)
    try {
        pasteAction()
    } finally {
        try {
            if (priorClip != null) {
                clipboard.setPrimaryClip(priorClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Best effort restore
        }
    }
}
