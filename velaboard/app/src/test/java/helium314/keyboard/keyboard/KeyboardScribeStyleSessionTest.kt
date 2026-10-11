package helium314.keyboard.keyboard

import android.os.Looper
import com.velavoice.sdk.AudioRecorder
import com.velavoice.sdk.ScribeInput
import com.velavoice.sdk.VelaRecordingCallback
import com.velavoice.sdk.VelaTranscriber
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.WhisperEngine
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * #101 review blocker 2: the cached transcriber bakes the per-app Scribe style
 * into its TextCleaner config at build time, while [KeyboardSwitcher.buildScribeInput]
 * handed `overrideStyle = null` ("resolved inside buildVelaTranscriber"). With the
 * cross-session transcriber cache, dictating in app A (Casual) then app B reused
 * app A's style, and a default-style pref change was ignored until teardown.
 *
 * Locks the fix: the style must be resolved per SESSION at the buildScribeInput
 * call site and travel on the ScribeInput, even when the cached transcriber is
 * reused (TextCleaner.clean takes `overrideStyle ?: config.defaultScribeStyle`,
 * so a non-null ScribeInput override wins over the stale baked-in default).
 *
 * The ScribeInput is captured through the existing seam: VelaTranscriber
 * startRecording forwards it to AudioRecorder.start, mocked here (same pattern
 * as KeyboardTranscriberSessionReuseTest's engine factory seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class KeyboardScribeStyleSessionTest {

    private lateinit var latinIME: LatinIME
    private val switcher = KeyboardSwitcher.getInstance()

    private val whisperModelFile: File =
        File.createTempFile("whisper-model", ".bin").apply { deleteOnExit() }
    private val llmModelFile: File =
        File.createTempFile("llm-model", ".bin").apply { deleteOnExit() }

    private val whisperEngine = mock(WhisperEngine::class.java)
    private val audioRecorder = mock(AudioRecorder::class.java)
    private lateinit var builtTranscriber: VelaTranscriber
    private var factoryCreates = 0
    private val capturedScribeInputs = mutableListOf<ScribeInput>()

    /**
     * Kotlin-declared wrapper: Mockito's matchers return Java-generic values the
     * Kotlin compiler null-checks at the call site (checkNotNullExpressionValue),
     * throwing NPE for non-null parameters. Declaring the helper in Kotlin with an
     * unbounded return type suppresses that check. Delegates to `any()` (not
     * `any(Class)`) so null arguments — the transcriber's null TextCleaner, the
     * null initialPrompt — still match.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(type: Class<T>): T = ArgumentMatchers.any() as T

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)
        switcher.releaseVelaTranscriber()
        capturedScribeInputs.clear()

        val prefs = Settings.getInstance().prefs
        prefs.edit()
            .putString(Settings.PREF_VELA_UI_STYLE, "yaps")
            .putString(Settings.PREF_VELA_STREAMING_MODE, "instant")
            .putString(Settings.PREF_VELA_TRANSCRIPTION_MODE, "local")
            .putString(Settings.PREF_VELA_MODEL_PATH, whisperModelFile.absolutePath)
            .putString(Settings.PREF_VELA_LLM_MODEL_PATH, llmModelFile.absolutePath)
            .putBoolean(Settings.PREF_VELA_LLM_TOGGLE, false)
            .putString(Settings.PREF_VELA_LANGUAGE, "en")
            .putInt(Settings.PREF_VELA_THREADS, 4)
            .putBoolean(Settings.PREF_VELA_SCRIBE_ENABLED, true)
            .putString(Settings.PREF_VELA_SCRIBE_STYLE, "Professional")
            .putString(Settings.PREF_VELA_SCRIBE_APP_PREFIX + "com.appa", "Casual")
            .apply()
        prefs.edit().remove(Settings.PREF_VELA_SCRIBE_APP_PREFIX + "com.appb").apply()

        val ctor = VelaTranscriber::class.java.declaredConstructors
            .single { it.parameterTypes.firstOrNull() == WhisperEngine::class.java && it.parameterCount == 4 }
            .apply { isAccessible = true }
        builtTranscriber = ctor.newInstance(whisperEngine, null, audioRecorder, null) as VelaTranscriber
        factoryCreates = 0
        switcher.setVelaTranscriberFactoryForTest { _, _, _, _, _, _, _, _, _, _ ->
            factoryCreates++
            builtTranscriber
        }

        // Capture the ScribeInput every session hands to the (shared) recorder.
        // start(whisper, cleaner, callback, scribeInput, initialPrompt) -> index 3.
        Mockito.doAnswer { inv ->
            capturedScribeInputs += inv.getArgument<ScribeInput>(3)
            null
        }.`when`(audioRecorder).start(
            anyArg(WhisperEngine::class.java),
            anyArg(TextCleaner::class.java),
            anyArg(VelaRecordingCallback::class.java),
            anyArg(ScribeInput::class.java),
            anyArg(String::class.java)
        )
    }

    @AfterTest
    fun tearDown() {
        switcher.setVelaTranscriberFactoryForTest(null)
        switcher.releaseVelaTranscriber()
        ShadowInputMethodService.reset()
    }

    private fun transcriber(): Any? {
        val field = KeyboardSwitcher::class.java.getDeclaredField("velaTranscriber")
        field.isAccessible = true
        return field.get(switcher)
    }

    private fun isLoading(): Boolean {
        val field = KeyboardSwitcher::class.java.getDeclaredField("mIsVelaLoading")
        field.isAccessible = true
        return field.getBoolean(switcher)
    }

    /** One full voice session: start (build-or-reuse), then stop. */
    private fun runSession() {
        switcher.showVelaVoicePane(latinIME)
        val deadline = System.currentTimeMillis() + 5_000
        while ((isLoading() || transcriber() == null) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(transcriber(), "voice session never published a transcriber")
        switcher.stopVelaRecording()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `sessions in different apps carry different scribe styles even when the transcriber is reused`() {
        ShadowInputMethodService.currentPackageName = "com.appa"
        runSession()
        ShadowInputMethodService.currentPackageName = "com.appb"
        runSession()

        assertEquals(1, factoryCreates,
            "config unchanged: the second session must reuse the cached transcriber " +
                "(this is the exact reuse that used to bake app A's style)")
        assertEquals(2, capturedScribeInputs.size,
            "both sessions must have started recording with a captured ScribeInput")

        val first = capturedScribeInputs[0]
        val second = capturedScribeInputs[1]
        assertEquals("com.appa", first.appName, "first session must run in app A")
        assertEquals("Casual", first.overrideStyle,
            "app A's per-app Scribe style must be resolved for the session")
        assertEquals("com.appb", second.appName, "second session must run in app B")
        assertEquals("Professional", second.overrideStyle,
            "a reused transcriber must not serve app A's style to app B — the default style " +
                "must be re-resolved for this session")
    }

    @Test
    fun `a default scribe style pref change applies to the next session despite the cached transcriber`() {
        runSession()

        Settings.getInstance().prefs.edit()
            .putString(Settings.PREF_VELA_SCRIBE_STYLE, "Bullet Points")
            .apply()
        runSession()

        assertEquals(1, factoryCreates,
            "a style pref change alone must not rebuild the transcriber — the style is a " +
                "per-session ScribeInput concern")
        assertEquals(2, capturedScribeInputs.size)
        assertEquals("Professional", capturedScribeInputs[0].overrideStyle)
        assertEquals("Bullet Points", capturedScribeInputs[1].overrideStyle,
            "the changed default style must reach the next session's ScribeInput")
    }
}
