package com.velavoice.sdk

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Shared privacy detection used by both native services (voice IME +
 * accessibility service) and the keyboard IME — map #72, ticket #76.
 * Single source of truth so password detection cannot drift between apps.
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyGuardTest {

    private fun editor(inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0) =
        EditorInfo().apply {
            this.inputType = inputType
            this.imeOptions = imeOptions
        }

    @Test
    fun `password input variations are sensitive`() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ).forEach { inputType ->
            assertTrue(
                "inputType $inputType must be sensitive",
                PrivacyGuard.isSensitiveInputType(inputType)
            )
        }
    }

    @Test
    fun `variation values that collide across input classes are not sensitive`() {
        // TYPE_NUMBER_VARIATION_PASSWORD (0x10) == TYPE_TEXT_VARIATION_URI (0x10):
        // classification must check the class mask too, not just the variation.
        assertFalse(
            "URI fields must not be treated as password fields",
            PrivacyGuard.isSensitiveInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
    }

    @Test
    fun `normal input types are not sensitive`() {
        assertFalse(
            PrivacyGuard.isSensitiveInputType(InputType.TYPE_CLASS_TEXT)
        )
        assertFalse(
            PrivacyGuard.isSensitiveInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertFalse(
            PrivacyGuard.isSensitiveInputType(InputType.TYPE_CLASS_NUMBER)
        )
    }

    @Test
    fun `editor with password variation is sensitive`() {
        assertTrue(
            PrivacyGuard.isPrivacySensitiveEditor(
                editor(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
        )
    }

    @Test
    fun `editor with IME_FLAG_NO_PERSONALIZED_LEARNING is sensitive`() {
        assertTrue(
            PrivacyGuard.isPrivacySensitiveEditor(
                editor(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            )
        )
    }

    @Test
    fun `plain editor is not sensitive`() {
        assertFalse(PrivacyGuard.isPrivacySensitiveEditor(editor()))
    }

    @Test
    fun `null editor follows legacy IME semantics and is not sensitive`() {
        assertFalse(PrivacyGuard.isPrivacySensitiveEditor(null))
    }

    @Test
    fun `null accessibility node fails closed to sensitive`() {
        assertTrue(PrivacyGuard.isSensitiveAccessibilityNode(null))
    }

    @Test
    fun `plain accessibility node is not sensitive`() {
        assertFalse(
            PrivacyGuard.isSensitiveAccessibilityNode(
                android.view.accessibility.AccessibilityNodeInfo.obtain()
            )
        )
    }

    @Test
    fun `password hint text is sensitive`() {
        assertTrue(
            "fields hinting a password must be classified sensitive",
            PrivacyGuard.isPrivacySensitiveEditor(editor().apply { hintText = "Password" })
        )
        assertTrue(
            PrivacyGuard.isPrivacySensitiveEditor(editor().apply { hintText = "Enter your password" })
        )
        assertFalse(
            PrivacyGuard.isPrivacySensitiveEditor(editor().apply { hintText = "Search contacts" })
        )
    }

    @Test
    fun `no-suggestions text flag is sensitive`() {
        val editor = editor(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        assertTrue(PrivacyGuard.isPrivacySensitiveEditor(editor))
    }

    @Test
    fun `unset inputType (TYPE_NULL) fails closed to sensitive`() {
        assertTrue(PrivacyGuard.isPrivacySensitiveEditor(editor(inputType = InputType.TYPE_NULL)))
    }

    @Test
    fun `null editor fails closed only when the caller is in a session re-check`() {
        assertTrue(
            PrivacyGuard.isPrivacySensitiveEditor(null, failClosedWhenUnknown = true)
        )
        assertFalse(
            "pre-session classification keeps legacy null => not sensitive semantics",
            PrivacyGuard.isPrivacySensitiveEditor(null, failClosedWhenUnknown = false)
        )
    }

    @Test
    fun `resolveTranscriptionMode falls back to local for sensitive input when local model exists`() {
        assertEqualsLocal(PrivacyGuard.resolveTranscriptionMode("gemini", true, true))
        assertEqualsLocal(PrivacyGuard.resolveTranscriptionMode("groq", true, true))
        assertEqualsLocal(PrivacyGuard.resolveTranscriptionMode("openai", true, true))
    }

    @Test
    fun `resolveTranscriptionMode aborts sensitive cloud transcription without local model`() {
        assertNull(PrivacyGuard.resolveTranscriptionMode("gemini", true, false))
        assertNull(PrivacyGuard.resolveTranscriptionMode("custom", true, false))
    }

    @Test
    fun `resolveTranscriptionMode keeps mode for non-sensitive input`() {
        assertTrue(
            PrivacyGuard.resolveTranscriptionMode("gemini", false, false) == "gemini"
        )
        assertTrue(
            PrivacyGuard.resolveTranscriptionMode("local", false, false) == "local"
        )
    }

    @Test
    fun `resolveTranscriptionMode keeps local mode when sensitive`() {
        assertTrue(
            PrivacyGuard.resolveTranscriptionMode("local", true, false) == "local"
        )
    }

    private fun assertEqualsLocal(value: String?) {
        assertTrue("expected local fallback, got $value", value == "local")
    }
}
