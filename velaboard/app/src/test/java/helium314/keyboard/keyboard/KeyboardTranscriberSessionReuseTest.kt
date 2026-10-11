package helium314.keyboard.keyboard

import android.os.Looper
import com.velavoice.sdk.AudioRecorder
import com.velavoice.sdk.VelaTranscriber
import com.velavoice.sdk.whisper.WhisperEngine
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
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
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Map #97 ticket #101: one cached VelaTranscriber/WhisperEngine per IME
 * lifetime, reused across voice sessions, released exactly once on IME
 * teardown; model paths resolved once per pref value, invalidated explicitly
 * on pref change / teardown.
 *
 * The construction seam ([KeyboardSwitcher.setVelaTranscriberFactoryForTest])
 * stands in for the real `buildVelaTranscriber` (which would load the whisper
 * JNI library — impossible under Robolectric). Proven here: the number of
 * times the production build path would run, and that the same engine
 * instance is reused across 20 start/stop cycles. NOT proven: native
 * whisper_init itself (no JNI on the JVM) — Builder.build() constructs
 * exactly one WhisperEngine per VelaTranscriber (VelaTranscriber.kt:70), so
 * transcriber constructions proxy 1:1 for whisper_init loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class KeyboardTranscriberSessionReuseTest {

    private lateinit var latinIME: LatinIME
    private val switcher = KeyboardSwitcher.getInstance()

    private val whisperModelFile: File =
        File.createTempFile("whisper-model", ".bin").apply { deleteOnExit() }
    private val otherModelFile: File =
        File.createTempFile("whisper-model-v2", ".bin").apply { deleteOnExit() }
    private val llmModelFile: File =
        File.createTempFile("llm-model", ".bin").apply { deleteOnExit() }

    private val whisperEngine = mock(WhisperEngine::class.java)
    private val audioRecorder = mock(AudioRecorder::class.java)
    private lateinit var builtTranscriber: VelaTranscriber
    private var factoryCreates = 0

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)
        // Clears leaked session state + the model-path cache (teardown invalidation).
        switcher.releaseVelaTranscriber()

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
            .apply()

        val ctor = VelaTranscriber::class.java.declaredConstructors
            .single { it.parameterTypes.firstOrNull() == WhisperEngine::class.java && it.parameterCount == 4 }
            .apply { isAccessible = true }
        builtTranscriber = ctor.newInstance(whisperEngine, null, audioRecorder, null) as VelaTranscriber
        factoryCreates = 0
        switcher.setVelaTranscriberFactoryForTest { _, _, _, _, _, _, _, _, _, _ ->
            factoryCreates++
            builtTranscriber
        }
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

    private fun resolveCount(field: String): Int {
        val f = KeyboardSwitcher::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.getInt(switcher)
    }

    /** One full voice session: start (build-or-reuse), then stop. */
    private fun runSession() {
        switcher.showVelaVoicePane(latinIME)
        // A first session or a rebuild publishes from a background thread via a
        // main-looper post; a reuse session is synchronous. Wait for any
        // in-flight build to settle before stopping.
        val deadline = System.currentTimeMillis() + 5_000
        while ((isLoading() || transcriber() == null) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(transcriber(), "voice session never published a transcriber")
        switcher.stopVelaRecording()
    }

    @Test
    fun `twenty sessions with unchanged config construct exactly one transcriber`() {
        repeat(20) { runSession() }
        assertEquals(1, factoryCreates,
            "20 start/stop cycles with an unchanged config must construct exactly one " +
                "VelaTranscriber — one whisper_init per IME lifetime, not per session")
        assertSame(builtTranscriber, transcriber(),
            "the cached engine instance must be reused across sessions")
        // Every session actually drove the (mocked) recorder of that one instance.
        verify(audioRecorder, times(20)).stop(false)
    }

    @Test
    fun `ime teardown releases the cached engine exactly once and forces a rebuild`() {
        repeat(3) { runSession() }
        assertEquals(1, factoryCreates)

        switcher.releaseVelaTranscriber() // LatinIME.onDestroy path
        verify(audioRecorder, times(1)).release()
        verify(whisperEngine, times(1)).free()
        assertNull(transcriber(), "teardown must drop the cached engine reference")

        runSession()
        assertEquals(2, factoryCreates,
            "a session after IME teardown must build a fresh engine")
    }

    @Test
    fun `config pref change explicitly invalidates the cached engine`() {
        repeat(2) { runSession() }
        assertEquals(1, factoryCreates)

        Settings.getInstance().prefs.edit()
            .putString(Settings.PREF_VELA_LANGUAGE, "fr")
            .apply()
        runSession()
        assertEquals(2, factoryCreates,
            "a language pref change must rebuild the engine instead of reusing the stale one")
    }

    @Test
    fun `whisper model path resolves once per pref value`() {
        val prefs = Settings.getInstance().prefs
        val before = resolveCount("mWhisperModelResolveCount")

        repeat(20) {
            assertEquals(whisperModelFile.absolutePath, switcher.resolveWhisperModelPath(latinIME, prefs))
        }
        assertEquals(before + 1, resolveCount("mWhisperModelResolveCount"),
            "an unchanged pref must resolve the model path once, not once per session")

        // A pref change is an explicit invalidation.
        prefs.edit().putString(Settings.PREF_VELA_MODEL_PATH, otherModelFile.absolutePath).apply()
        assertEquals(otherModelFile.absolutePath, switcher.resolveWhisperModelPath(latinIME, prefs))
        assertEquals(before + 2, resolveCount("mWhisperModelResolveCount"))

        // IME teardown clears the cache: the next resolve runs once more.
        switcher.releaseVelaTranscriber()
        switcher.resolveWhisperModelPath(latinIME, prefs)
        assertEquals(before + 3, resolveCount("mWhisperModelResolveCount"),
            "IME teardown must invalidate the model-path cache")
    }

    @Test
    fun `llm model path resolves once per pref value`() {
        val prefs = Settings.getInstance().prefs
        val before = resolveCount("mLlmModelResolveCount")

        repeat(20) {
            assertEquals(llmModelFile.absolutePath, switcher.resolveLlmModelPathCached(latinIME, prefs))
        }
        assertEquals(before + 1, resolveCount("mLlmModelResolveCount"),
            "an unchanged pref must resolve the LLM path once, not once per session")

        prefs.edit().putString(Settings.PREF_VELA_LLM_MODEL_PATH, otherModelFile.absolutePath).apply()
        assertEquals(otherModelFile.absolutePath, switcher.resolveLlmModelPathCached(latinIME, prefs))
        assertEquals(before + 2, resolveCount("mLlmModelResolveCount"))
    }
}
