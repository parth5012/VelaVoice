package helium314.keyboard.keyboard

import android.Manifest
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Map #105 ticket #115: Yaps pane accessibility & i18n.
 *
 * - The AUTO pill and both toolbar icon buttons (cancel/confirm) carry spoken
 *   descriptions — icon-only [ImageButton]s are silent to TalkBack otherwise.
 * - Toolbar focus moves cancel -> confirm so switch-access users reach cancel
 *   while recording (the highest-risk state).
 * - The TRANSCRIBING state label comes from resources, never a literal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class YapsPaneA11yTest {

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
        yaps.init(latinIME, switcher, strip, container)
    }

    @AfterTest
    fun tearDown() {
        switcher.setVelaTranscriberFactoryForTest(null)
        switcher.releaseVelaTranscriber()
        ShadowInputMethodService.reset()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    /** Engine is idle-but-allowed: setUp releases it, so the cancel flag blocks a start. */
    private fun allowEngineStart() {
        val field = KeyboardSwitcher::class.java.getDeclaredField("mIsVelaRecordingCancelled")
        field.isAccessible = true
        field.setBoolean(switcher, false)
    }

    private fun imageButtons(): List<ImageButton> {
        val out = mutableListOf<ImageButton>()
        fun walk(v: View) {
            if (v is ImageButton) out.add(v)
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(container)
        return out
    }

    @Test
    fun `idle pill exposes a spoken description`() {
        yaps.setupIdleState()
        settle()
        val pill = container.getChildAt(container.childCount - 1)
        val cd = pill.contentDescription?.toString().orEmpty()
        assertTrue(
            cd.isNotBlank(),
            "AUTO pill must expose a contentDescription (icon bars + AUTO label misread otherwise)"
        )
    }

    @Test
    fun `toolbar icon buttons expose descriptions and a cancel-first focus chain`() {
        yaps.setupIdleState()
        allowEngineStart()
        assertTrue(yaps.startActiveRecording("EN"))
        settle()
        val buttons = imageButtons()
        assertEquals(2, buttons.size, "recording toolbar must own cancel + confirm buttons")
        for (b in buttons) {
            assertTrue(
                b.contentDescription?.toString().isNullOrBlank().not(),
                "toolbar ImageButton must have a contentDescription (silent to TalkBack otherwise)"
            )
            assertTrue(b.isFocusable, "toolbar button must be focusable")
        }
        val cancel = buttons.single { it.contentDescription.toString().contains("Cancel", ignoreCase = true) }
        val confirm = buttons.single { it.contentDescription.toString().contains("Confirm", ignoreCase = true) }
        assertEquals(
            confirm.id, cancel.nextFocusForwardId,
            "switch-access focus must move cancel -> confirm (cancel reachable while recording)"
        )
    }

    @Test
    fun `transcribing label comes from resources`() {
        val root = findRepoRoot()
        val f = File(root, "velaboard/app/src/main/java/helium314/keyboard/keyboard/YapsUiManager.kt")
        var code = f.readText().replace(Regex("//.*"), "")
        code = code.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            !code.contains("\"TRANSCRIBING\""),
            "TRANSCRIBING label must come from strings.xml, not a literal"
        )
        assertTrue(
            code.contains("announceForAccessibility"),
            "toolbar state announcements must exist (recording start/finish)"
        )
    }

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "velaboard").isDirectory && File(dir, "sdk").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        fail("repo root (containing 'velaboard' + 'sdk') not found above user.dir")
        throw IllegalStateException("unreachable")
    }
}
