package helium314.keyboard.keyboard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
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
import helium314.keyboard.latin.permissions.PermissionsActivity
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

/**
 * Map #97 ticket #103: the Yaps pane (idle pill + recording toolbar) must follow an
 * explicit show/hide lifecycle — no view may stay attached to an input view that was
 * discarded, no recording toolbar may open without a connected input view or a live
 * engine, and finishing/unbinding the input must detach every pane view.
 *
 * Behavioral tests only: every assertion is written against API that already exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class YapsUiManagerPaneLifecycleTest {

    private lateinit var latinIME: LatinIME
    private lateinit var container: FrameLayout
    private lateinit var strip: View
    private lateinit var whisperModelFile: File

    private val yaps = YapsUiManager.getInstance()
    private val switcher = KeyboardSwitcher.getInstance()
    private var factoryCreates = 0

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)
        // Clears leaked session state + the model-path cache (ticket #101).
        switcher.releaseVelaTranscriber()

        whisperModelFile = File.createTempFile("whisper-model", ".bin").apply { deleteOnExit() }
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

        // The engine-alive show guard reads the release flag; production clears it inside
        // showVelaVoicePaneInternal before any recording may start, so tests start healthy.
        // (The dedicated engine-guard test releases again inside the test body.)
        setSwitcherFlag("mIsVelaRecordingCancelled", false)

        val whisperEngine = mock(WhisperEngine::class.java)
        val audioRecorder = mock(AudioRecorder::class.java)
        val ctor = VelaTranscriber::class.java.declaredConstructors
            .single { it.parameterTypes.firstOrNull() == WhisperEngine::class.java && it.parameterCount == 4 }
            .apply { isAccessible = true }
        val builtTranscriber = ctor.newInstance(whisperEngine, null, audioRecorder, null) as VelaTranscriber
        factoryCreates = 0
        switcher.setVelaTranscriberFactoryForTest { _, _, _, _, _, _, _, _, _, _ ->
            factoryCreates++
            builtTranscriber
        }

        bindNewInputView()
    }

    @AfterTest
    fun tearDown() {
        switcher.setVelaTranscriberFactoryForTest(null)
        switcher.releaseVelaTranscriber()
        ShadowInputMethodService.reset()
    }

    /** Simulates onCreateInputView: a fresh suggestion strip inside a fresh input view. */
    private fun bindNewInputView() {
        container = FrameLayout(latinIME)
        strip = View(latinIME)
        container.addView(strip)
        yaps.init(latinIME, switcher, strip, FrameLayout(latinIME))
    }

    private fun childrenExceptStrip(parent: ViewGroup, stripView: View): List<View> =
        (0 until parent.childCount).map { parent.getChildAt(it) }.filter { it !== stripView }

    /** The recording toolbar owns ImageButtons (cancel/confirm); the idle pill never does. */
    private fun toolbars(parent: ViewGroup, stripView: View): List<View> =
        childrenExceptStrip(parent, stripView).filter { view ->
            view is LinearLayout && (0 until view.childCount).any { view.getChildAt(it) is ImageButton }
        }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    private fun permissionReceiver(): BroadcastReceiver {
        val field = LatinIME::class.java.getDeclaredField("mPermissionReceiver")
        field.isAccessible = true
        return field.get(latinIME) as BroadcastReceiver
    }

    private fun setSwitcherFlag(name: String, value: Boolean) {
        val field = KeyboardSwitcher::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(switcher, value)
    }

    @Test
    fun `recreating the input view detaches the old pane from its actual parent`() {
        yaps.setupIdleState()
        assertEquals(1, childrenExceptStrip(container, strip).size, "sanity: the idle pill is attached")

        val oldContainer = container
        val oldStrip = strip
        bindNewInputView()
        yaps.setupIdleState()

        assertEquals(0, childrenExceptStrip(oldContainer, oldStrip).size,
            "the discarded input view must not keep an orphaned Yaps pane")
        assertEquals(1, childrenExceptStrip(container, strip).size,
            "the new input view shows exactly one pill")
    }

    @Test
    fun `a stop teardown pending across a re-init cannot orphan a second recording toolbar`() {
        yaps.setupIdleState()
        yaps.startActiveRecording("EN")
        settle()

        yaps.stopActiveRecording() // toolbar removal is deferred to an animation end action

        bindNewInputView() // input view recreated while that end action is still pending
        yaps.setupIdleState()
        yaps.startActiveRecording("EN")
        settle() // the stale end action fires here and must not touch the live toolbar

        yaps.startActiveRecording("EN")
        settle()

        assertEquals(1, toolbars(container, strip).size,
            "exactly one recording toolbar may be attached to the live input view")
    }

    @Test
    fun `idle setup during an active recording does not reshow the pill`() {
        yaps.setupIdleState()
        val pill = childrenExceptStrip(container, strip).single()
        yaps.startActiveRecording("EN")
        settle()
        assertEquals(View.GONE, pill.visibility, "sanity: the pill is hidden while recording")

        yaps.setupIdleState()

        assertEquals(View.GONE, pill.visibility,
            "the idle pill must not reappear over an active recording toolbar")
    }

    @Test
    fun `finishing the input view detaches every yaps view`() {
        yaps.setupIdleState()
        yaps.startActiveRecording("EN")
        settle()

        latinIME.onFinishInputView(false)
        settle()

        assertEquals(0, childrenExceptStrip(container, strip).size,
            "onFinishInputView must fully reset the Yaps pane (pill and toolbar)")
    }

    @Test
    fun `unbinding the input detaches every yaps view`() {
        yaps.setupIdleState()
        yaps.startActiveRecording("EN")
        settle()

        latinIME.onUnbindInput()
        settle()

        assertEquals(0, childrenExceptStrip(container, strip).size,
            "onUnbindInput must fully reset the Yaps pane (pill and toolbar)")
    }

    @Test
    fun `no recording toolbar opens while the input view is hidden`() {
        ShadowInputMethodService.inputViewShown = false

        switcher.showVelaVoicePane(latinIME)
        settle()

        assertEquals(0, toolbars(container, strip).size,
            "the recording pane must not open when no input view is connected")
        assertEquals(0, factoryCreates,
            "no voice session may start while the input view is hidden")
    }

    @Test
    fun `the recording pane still opens when the input view is shown`() {
        switcher.showVelaVoicePane(latinIME)
        settle()

        assertEquals(1, toolbars(container, strip).size,
            "the happy path must open exactly one recording toolbar")
    }

    @Test
    fun `no recording toolbar opens without a live voice engine`() {
        yaps.setupIdleState()
        switcher.releaseVelaTranscriber() // no engine, no streaming session, no build in flight

        yaps.startActiveRecording("EN")
        settle()

        assertEquals(0, toolbars(container, strip).size,
            "the recording toolbar must not appear without a live voice engine")
    }

    @Test
    fun `the granted-broadcast receiver does not open the pane while the input view is hidden`() {
        ShadowInputMethodService.inputViewShown = false

        permissionReceiver().onReceive(latinIME, Intent(PermissionsActivity.ACTION_RECORD_AUDIO_GRANTED))
        settle()

        assertEquals(0, toolbars(container, strip).size,
            "a grant broadcast must not open the recording pane with no input view connected")
    }

    @Test
    fun `the granted-broadcast receiver opens the pane when the input view is shown`() {
        permissionReceiver().onReceive(latinIME, Intent(PermissionsActivity.ACTION_RECORD_AUDIO_GRANTED))
        settle()

        assertEquals(1, toolbars(container, strip).size,
            "a legitimate grant with a connected input view must open the pane")
    }

    @Test
    fun `show hide show unbind show never renders an orphan pane`() {
        switcher.showVelaVoicePane(latinIME)
        settle()
        assertEquals(1, toolbars(container, strip).size, "first show renders one toolbar")

        yaps.stopActiveRecording()
        settle()
        assertEquals(0, toolbars(container, strip).size, "hide detaches the toolbar")

        switcher.showVelaVoicePane(latinIME)
        settle()
        assertEquals(1, toolbars(container, strip).size, "second show renders one toolbar")

        latinIME.onUnbindInput()
        settle()
        assertEquals(0, childrenExceptStrip(container, strip).size,
            "unbind fully resets the pane")

        switcher.showVelaVoicePane(latinIME)
        settle()
        assertEquals(1, toolbars(container, strip).size,
            "show after unbind must render exactly one pane, not an orphaned duplicate")
    }
}
