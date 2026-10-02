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
     * True when the IME editor opts out of personalized learning or uses a password
     * inputType. `null` editor follows the legacy IME semantics (not sensitive);
     * call sites that cannot resolve an editor at all must fail closed themselves.
     */
    @JvmStatic
    fun isPrivacySensitiveEditor(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        if ((editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        return isSensitiveInputType(editorInfo.inputType)
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
