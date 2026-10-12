package helium314.keyboard.keyboard

import android.Manifest
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import com.velavoice.sdk.AudioRecorder
import com.velavoice.sdk.VelaTranscriber
import com.velavoice.sdk.whisper.WhisperEngine
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Map #97 ticket #103: contract of the explicit Yaps pane state machine
 * (HIDDEN / SHOWN / RECORDING / PROCESSING) and of the two show guards:
 * a connected input view and a live voice engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class YapsUiManagerStateMachineTest {

    private lateinit var latinIME: LatinIME
    private lateinit var container: FrameLayout
    private lateinit var strip: View

    private val yaps = YapsUiManager.getInstance()
    private val switcher = KeyboardSwitcher.getInstance()

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)
        switcher.releaseVelaTranscriber()

        val whisperModelFile = File.createTempFile("whisper-model", ".bin").apply { deleteOnExit() }
        val llmModelFile = File.createTempFile("llm-model", ".bin").apply { deleteOnExit() }
        Settings.getInstance().prefs.edit()
            .putString(Settings.PREF_VELA_UI_STYLE, "yaps")
            .putString(Settings.PREF_VELA_STREAMING_MODE, "instant")
            .putString(Settings.PREF_VELA_TRANSCRIPTION_MODE, "local")
            .putString(Settings.PREF_VELA_MODEL_PATH, whisperModelFile.absolutePath)
            .putString(Settings.PREF_VELA_LLM_MODEL_PATH, llmModelFile.absolutePath)
            .putBoolean(Settings.PREF_VELA_LLM_TOGGLE, false)
            .putString(Settings.PREF_VELA_LANGUAGE, "en")
            .putInt(Settings.PREF_VELA_THREADS, 4)
            .apply()

        shadowOf(latinIME.application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val whisperEngine = mock(WhisperEngine::class.java)
        val audioRecorder = mock(AudioRecorder::class.java)
        val ctor = VelaTranscriber::class.java.declaredConstructors
            .single { it.parameterTypes.firstOrNull() == WhisperEngine::class.java && it.parameterCount == 4 }
            .apply { isAccessible = true }
        val builtTranscriber = ctor.newInstance(whisperEngine, null, audioRecorder, null) as VelaTranscriber
        switcher.setVelaTranscriberFactoryForTest { _, _, _, _, _, _, _, _, _, _ -> builtTranscriber }

        container = FrameLayout(latinIME)
        strip = View(latinIME)
        container.addView(strip)
        yaps.init(latinIME, switcher, strip, FrameLayout(latinIME))
    }

    @AfterTest
    fun tearDown() {
        switcher.setVelaTranscriberFactoryForTest(null)
        switcher.releaseVelaTranscriber()
        ShadowInputMethodService.reset()
    }

    private fun setSwitcherFlag(name: String, value: Boolean) {
        val field = KeyboardSwitcher::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(switcher, value)
    }

    private fun childrenExceptStrip(): List<View> =
        (0 until container.childCount).map { container.getChildAt(it) }.filter { it !== strip }

    /** The recording toolbar owns ImageButtons (cancel/confirm); the idle pill never does. */
    private fun toolbars(): List<View> =
        childrenExceptStrip().filter { view ->
            view is LinearLayout && (0 until view.childCount).any { view.getChildAt(it) is ImageButton }
        }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    /** Engine is idle-but-allowed: released by setUp, so the cancel flag still blocks a start. */
    private fun allowEngineStart() = setSwitcherFlag("mIsVelaRecordingCancelled", false)

    @Test
    fun `init leaves the pane hidden`() {
        assertEquals(YapsUiManager.State.HIDDEN, yaps.state())
        assertEquals(0, childrenExceptStrip().size)
    }

    @Test
    fun `idle setup moves the pane to shown`() {
        yaps.setupIdleState()

        assertEquals(YapsUiManager.State.SHOWN, yaps.state())
        assertEquals(1, childrenExceptStrip().size, "the idle pill is the only pane view")
    }

    @Test
    fun `starting a recording reports success and moves to recording`() {
        yaps.setupIdleState()
        allowEngineStart()

        assertTrue(yaps.startActiveRecording("EN"),
            "a connected input view with a live engine must be allowed to start")

        assertEquals(YapsUiManager.State.RECORDING, yaps.state())
        assertEquals(1, toolbars().size, "the recording toolbar is attached")
    }

    @Test
    fun `starting a recording refuses while the input view is hidden`() {
        yaps.setupIdleState()
        allowEngineStart()
        ShadowInputMethodService.inputViewShown = false

        assertFalse(yaps.startActiveRecording("EN"),
            "no recording may start without a connected input view")

        assertEquals(YapsUiManager.State.SHOWN, yaps.state(), "a refused start must not change state")
        assertEquals(0, toolbars().size, "a refused start must not attach a toolbar")
    }

    @Test
    fun `starting a recording refuses while no engine is alive`() {
        yaps.setupIdleState()
        setSwitcherFlag("mIsVelaRecordingCancelled", true) // released engine, no build in flight

        assertFalse(yaps.startActiveRecording("EN"),
            "no recording may start without a live voice engine")

        assertEquals(YapsUiManager.State.SHOWN, yaps.state(), "a refused start must not change state")
        assertEquals(0, toolbars().size, "a refused start must not attach a toolbar")
    }

    @Test
    fun `stopping twice is idempotent and returns to shown`() {
        yaps.setupIdleState()
        allowEngineStart()
        assertTrue(yaps.startActiveRecording("EN"))

        yaps.stopActiveRecording()
        yaps.stopActiveRecording()
        settle()

        assertEquals(YapsUiManager.State.SHOWN, yaps.state())
        assertEquals(1, childrenExceptStrip().size, "only the idle pill remains attached")
    }

    @Test
    fun `stopping an idle pane leaves the state untouched`() {
        yaps.stopActiveRecording()
        settle()

        assertEquals(YapsUiManager.State.HIDDEN, yaps.state())
        assertEquals(0, childrenExceptStrip().size)
    }

    @Test
    fun `confirm moves recording to processing and stop returns to shown`() {
        yaps.setupIdleState()
        allowEngineStart()
        assertTrue(yaps.startActiveRecording("EN"))

        val toolbar = toolbars().single() as ViewGroup
        (toolbar.getChildAt(2) as ImageButton).performClick()

        assertEquals(YapsUiManager.State.PROCESSING, yaps.state())

        yaps.stopActiveRecording()
        settle()

        assertEquals(YapsUiManager.State.SHOWN, yaps.state())
        assertEquals(1, childrenExceptStrip().size, "only the idle pill remains attached")
    }

    @Test
    fun `reset to hidden detaches every view and restores the strip`() {
        yaps.setupIdleState()
        allowEngineStart()
        assertTrue(yaps.startActiveRecording("EN"))
        settle()

        yaps.resetToHidden()

        assertEquals(YapsUiManager.State.HIDDEN, yaps.state())
        assertEquals(0, childrenExceptStrip().size, "reset must detach pill and toolbar")
        assertEquals(View.VISIBLE, strip.visibility, "reset must restore the suggestion strip")
        assertTrue(strip.alpha > 0.99f, "reset must restore full strip opacity")

        // A full reset must leave a pane that can be rebuilt from scratch.
        yaps.setupIdleState()
        assertEquals(YapsUiManager.State.SHOWN, yaps.state())
        assertEquals(1, childrenExceptStrip().size, "the idle pill is recreated")
    }

    @Test
    fun `engine liveness follows the engine lifetime`() {
        switcher.releaseVelaTranscriber()
        assertFalse(switcher.isVelaEngineAlive(), "a released engine must not read as alive")

        setSwitcherFlag("mIsVelaRecordingCancelled", false)
        assertTrue(switcher.isVelaEngineAlive(),
            "an idle switcher with no release is ready to build and must read as alive")
    }
}
