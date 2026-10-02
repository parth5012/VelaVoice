package com.velavoice.sdk

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shared privacy detection for every Vela voice entry point: the keyboard IME
 * ([KeyboardSwitcher]-equivalent logic), the companion voice IME service and the
 * companion accessibility service (map #72, ticket #76).
 *
 * Lives in vela-core so both apps resolve the SAME implementation — password
 * classification must not be able to drift between modules.
 */
object PrivacyGuard {

    /** True for password-style inputType variations (text/visible/web/number password). */
    @JvmStatic
    fun isSensitiveInputType(inputType: Int): Boolean {
        // Variation codes collide across classes (e.g. TYPE_NUMBER_VARIATION_PASSWORD
        // == TYPE_TEXT_VARIATION_URI == 0x10), so the class mask must match too.
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * True when the focused editor must be treated as privacy-sensitive.
     *
     * Detected signals (map #72, ticket #78):
     * - [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] opts-out flag
     * - password inputType variations (class-mask aware)
     * - [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS] (private/code fields commonly set it)
     * - `inputType == 0` ([InputType.TYPE_NULL], no declared type — fail closed)
     * - a password `hintText` ("Password", "Enter your password", ...) — the only
     *   hint channel an IME receives; `EditorInfo` has no `autofillHints` field
     *   (verified against the API 35 SDK), so view autofill hints are invisible here
     *
     * [failClosedWhenUnknown] only applies to a `null` editor: session re-checks
     * (focus lost mid-voice-session) must fail closed, while pre-session
     * classification keeps the legacy IME semantics (null => not sensitive).
     */
    @JvmStatic
    @JvmOverloads
    fun isPrivacySensitiveEditor(editorInfo: EditorInfo?, failClosedWhenUnknown: Boolean = false): Boolean {
        if (editorInfo == null) return failClosedWhenUnknown
        if ((editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        if (editorInfo.inputType == InputType.TYPE_NULL) return true
        if ((editorInfo.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return true
        if (isSensitiveInputType(editorInfo.inputType)) return true
        val hint = editorInfo.hintText
        if (hint != null && hint.toString().contains("password", ignoreCase = true)) return true
        return false
    }

    /**
     * Accessibility-service variant: inspects the focused node. Fails CLOSED when
     * the node is null (focus unknown → treat as sensitive) or reports password.
     */
    @JvmStatic
    fun isSensitiveAccessibilityNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return true
        @Suppress("DEPRECATION")
        if (node.isPassword) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isSensitiveInputType(node.inputType)) {
            return true
        }
        return false
    }

    /**
     * Cloud-gate helper: when [privacySensitive], audio must never be sent to a cloud
     * transcription service. Falls back to local transcription when a local model is
     * available, otherwise returns null — the caller must abort transcription
     * (no audio leaves the device) and surface an error.
     */
    @JvmStatic
    fun resolveTranscriptionMode(mode: String, privacySensitive: Boolean, hasLocalModel: Boolean): String? {
        if (!privacySensitive || mode == "local") return mode
        return if (hasLocalModel) "local" else null
    }
}
