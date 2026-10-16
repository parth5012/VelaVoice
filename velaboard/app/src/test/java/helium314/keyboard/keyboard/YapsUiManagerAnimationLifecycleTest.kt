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
import kotlin.test.fail

/**
 * Map #105 ticket #114: animation lifecycle — never-ending animators, leaked listeners.
 *
 * Done when:
 * - one animator per property; starting a new transition cancels the in-flight one first;
 * - every listener registered has a matching removal on the hide/detach path (audited below);
 * - no animator survives `onFinishInputView` (all cancelled, values reset);
 * - rapid show/hide/show ends in the correct visible state with zero running animators.
 *
 * Sequences AFTER #112 (YapsWaveformView / TranscribingDotsView frame-loop controls):
 * the frame-loop cancel semantics asserted there are lock-ins here, not re-fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class YapsUiManagerAnimationLifecycleTest {

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
        setSwitcherFlag("mIsVelaRecordingCancelled", false)

        val whisperEngine = mock(WhisperEngine::class.java)
        val audioRecorder = mock(AudioRecorder::class.java)
        val ctor = VelaTranscriber::class.java.declaredConstructors
            .single { it.parameterTypes.firstOrNull() == WhisperEngine::class.java && it.parameterCount == 4 }
            .apply { isAccessible = true }
        val builtTranscriber = ctor.newInstance(whisperEngine, null, audioRecorder, null) as VelaTranscriber
        switcher.setVelaTranscriberFactoryForTest { _, _, _, _, _, _, _, _, _, _ -> builtTranscriber }

        bindNewInputView()
    }

    @AfterTest
    fun tearDown() {
        switcher.setVelaTranscriberFactoryForTest(null)
        switcher.releaseVelaTranscriber()
        ShadowInputMethodService.reset()
    }

    private fun bindNewInputView() {
        container = FrameLayout(latinIME)
        strip = View(latinIME)
        container.addView(strip)
        yaps.init(latinIME, switcher, strip, FrameLayout(latinIME))
    }

    private fun setSwitcherFlag(name: String, value: Boolean) {
        val field = KeyboardSwitcher::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(switcher, value)
    }

    private fun childrenExceptStrip(parent: ViewGroup, stripView: View): List<View> =
        (0 until parent.childCount).map { parent.getChildAt(it) }.filter { it !== stripView }

    private fun toolbars(parent: ViewGroup, stripView: View): List<View> =
        childrenExceptStrip(parent, stripView).filter { view ->
            view is LinearLayout && (0 until view.childCount).any { view.getChildAt(it) is ImageButton }
        }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    private fun assertNoRunningAnimators(vararg views: View) {
        views.forEach {
            // NOTE (#114): hasTransientState() is a plain method, not a bean getter,
            // so Kotlin requires explicit call syntax (property access does not compile).
            assertFalse(it.hasTransientState(),
                "view $it must not hold a running animator after settle")
        }
    }

    // ── Behavioral: rapid toggle ─────────────────────────────────────────────

    @Test
    fun `rapid show hide show ends in the correct visible state with zero running animators`() {
        yaps.setupIdleState()
        val pill = childrenExceptStrip(container, strip).single()

        // No settle between transitions: show and hide animators overlap by design.
        yaps.startActiveRecording("EN")
        yaps.stopActiveRecording()
        yaps.startActiveRecording("EN")
        settle()

        assertEquals(YapsUiManager.State.RECORDING, yaps.state(),
            "rapid toggle must end recording")
        assertEquals(1, toolbars(container, strip).size,
            "rapid toggle must leave exactly one recording toolbar")
        val toolbar = toolbars(container, strip).single()
        assertEquals(View.VISIBLE, toolbar.visibility, "toolbar must be visible")
        assertEquals(1f, toolbar.alpha, "toolbar fade-in must have won, not a stale fade-out")
        assertEquals(View.GONE, pill.visibility, "pill must be hidden while recording")
        assertEquals(View.INVISIBLE, strip.visibility, "strip must be hidden while recording")
        assertNoRunningAnimators(toolbar, pill, strip)
    }

    @Test
    fun `hide during a running show leaves zero running animators and restores standby`() {
        yaps.setupIdleState()
        val pill = childrenExceptStrip(container, strip).single()

        yaps.startActiveRecording("EN")
        yaps.stopActiveRecording() // hide while every show fade is still in flight
        settle()

        assertEquals(YapsUiManager.State.SHOWN, yaps.state())
        assertEquals(0, toolbars(container, strip).size, "hide must detach the toolbar")
        assertEquals(View.VISIBLE, pill.visibility, "pill must be restored")
        assertEquals(1f, pill.alpha, "pill fade-in must have won, not the cancelled fade-out")
        assertEquals(View.VISIBLE, strip.visibility, "strip must be restored")
        assertEquals(1f, strip.alpha, "strip fade-in must have won, not the cancelled fade-out")
        assertNoRunningAnimators(pill, strip)
    }

    @Test
    fun `onFinishInputView leaves zero running animators and reset values`() {
        yaps.setupIdleState()
        yaps.startActiveRecording("EN")
        // Finish mid-transition: show fades are still in flight here.
        latinIME.onFinishInputView(false)
        settle()

        assertEquals(YapsUiManager.State.HIDDEN, yaps.state())
        assertEquals(0, childrenExceptStrip(container, strip).size,
            "onFinishInputView must detach every pane view")
        assertEquals(View.VISIBLE, strip.visibility, "strip must be restored visible")
        assertEquals(1f, strip.alpha, "strip alpha must be reset to 1")
        assertNoRunningAnimators(strip)
    }

    // ── Source contracts: cancel-before-start + listener pairing ─────────────

    private fun repoRoot(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "velaboard").isDirectory && File(dir, "sdk").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        fail("repo root (containing 'velaboard' + 'sdk') not found above user.dir")
        throw IllegalStateException("unreachable")
    }

    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    private fun yapsSource(): String {
        val f = File(repoRoot(),
            "velaboard/app/src/main/java/helium314/keyboard/keyboard/YapsUiManager.kt")
        if (!f.isFile) fail("YapsUiManager.kt not found under ${repoRoot()}")
        return codeOnly(f.readText())
    }

    private fun sessionSource(): String {
        val f = File(repoRoot(),
            "velaboard/app/src/main/java/helium314/keyboard/keyboard/VelaStreamingSession.java")
        if (!f.isFile) fail("VelaStreamingSession.java not found under ${repoRoot()}")
        return codeOnly(f.readText())
    }

    private fun paneSource(): String {
        val f = File(repoRoot(),
            "sdk/vela-voice-ui/src/main/kotlin/com/velavoice/sdk/ui/VoiceRecordingPane.kt")
        if (!f.isFile) fail("VoiceRecordingPane.kt not found under ${repoRoot()}")
        return codeOnly(f.readText())
    }

    /** Body of `fun <name>(` with balanced braces (first match). */
    private fun extractFunBody(source: String, funName: String): String {
        val idx = source.indexOf("fun $funName(")
        if (idx < 0) fail("fun $funName( not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for $funName not found")
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced braces for $funName")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun `every fade start cancels the in-flight animator first`() {
        val code = yapsSource()
        // Every fade transition â€” pill/strip/toolbar show AND hide â€” must cancel
        // the in-flight animator on the same view before starting a new one, so a
        // rapid toggle can never leave two animators racing on one property.
        // NOTE (#114 amendment): resetToHidden is intentionally NOT in this list.
        // It is the onFinishInputView cancel-and-reset path: it starts no fades
        // (cancel + detach + value reset only, per Done-when "no animator survives
        // onFinishInputView") and is covered by the resetToHidden test below instead.
        for (funName in listOf("startActiveRecording", "stopActiveRecording")) {
            val body = extractFunBody(code, funName)
            val starts = Regex("""\.animate\(\)[^;]*?\.alpha\s*\(""").findAll(body).toList()
            assertTrue(starts.isNotEmpty(),
                "fun $funName must own fade transitions (no .animate().alpha start found)")
            val cancels = Regex("""cancelFade\s*\(|animate\(\)[^;]*?\.cancel\(\)""").findAll(body).toList()
            assertTrue(cancels.isNotEmpty(),
                "fun $funName starts ${starts.size} fade(s) but cancels none: " +
                    "a hide during a running show would race completion order (#114)")
            assertTrue(cancels.size >= starts.size,
                "fun $funName starts ${starts.size} fade(s) but cancels only ${cancels.size}: " +
                    "one animator per property requires cancel-before-start on every start (#114)")
        }
    }

    @Test
    fun `every registered click listener has a matching removal on the hide-detach path`() {
        val code = yapsSource()
        // Audited listener surface (#114): the pill trigger, the toolbar cancel
        // button and the toolbar confirm button each register setOnClickListener.
        // No OnAttachStateChangeListener / OnLayoutChangeListener / AnimatorListener
        // may exist on this surface: withEndAction end-actions are one-shot and are
        // cleared with the animator cancel, so there is nothing else to pair.
        val registrations = Regex("""setOnClickListener\s*\{""").findAll(code).toList()
        assertEquals(3, registrations.size,
            "audited click-listener surface is pill + toolbar-cancel + toolbar-confirm; " +
                "found ${registrations.size} — re-audit before changing this number")
        val removals = Regex("""setOnClickListener\s*\(\s*null\s*\)""").findAll(code).toList()
        assertTrue(removals.isNotEmpty(),
            "no setOnClickListener(null) removal exists: detached pane views would keep " +
                "anonymous listeners holding the IME context across input sessions (#114)")
        for (funName in listOf("stopActiveRecording", "resetToHidden")) {
            val body = extractFunBody(code, funName)
            assertTrue(body.contains("setOnClickListener(null)") || body.contains("clearClickListeners"),
                "fun $funName detaches pane views but clears no click listeners (#114)")
        }
        assertTrue(!code.contains("addOnAttachStateChangeListener"),
            "OnAttachStateChangeListener must not be introduced without a paired removal (#114)")
        assertTrue(!code.contains("AnimatorListener") && !code.contains("setListener(") ||
            code.contains("setListener(null)"),
            "animator listeners must be cleared with the animator cancel (#114)")
    }

    @Test
    fun `resetToHidden cancels every pane animator and resets the surviving strip`() {
        val code = yapsSource()
        val body = extractFunBody(code, "resetToHidden")
        assertTrue(body.contains("stopAnimating"),
            "resetToHidden must stop the transcribing-dots frame loop (#112 stays intact)")
        for (view in listOf("pill", "toolbar", "suggestionStrip")) {
            assertTrue(body.contains(view),
                "resetToHidden must touch the $view animator (no animator may survive onFinishInputView)")
        }
        assertTrue(body.contains("cancelFade") || body.contains(".cancel()"),
            "resetToHidden must cancel in-flight animators, not just detach views")
        assertTrue(body.contains("alpha = 1f") && body.contains("View.VISIBLE"),
            "resetToHidden must reset the surviving suggestion strip to visible/opaque")
    }

    @Test
    fun `streaming typing animator keeps cancel-before-post on every path`() {
        // Lock-in (#114 audit): VelaStreamingSession.mTypingAnimator already pairs
        // every post with a removeCallbacks, and every terminal path removes it.
        val code = sessionSource()
        assertTrue(code.contains("removeCallbacks(mTypingAnimator)"),
            "typing animator posts must be cancellable")
        val queue = code.substringAfter("private void queueTyping")
        assertTrue(queue.substring(0, minOf(queue.length, 600)).contains("removeCallbacks(mTypingAnimator)"),
            "queueTyping must removeCallbacks before post (single animator per property)")
        for (terminal in listOf("release()", "markFinished", "flushTyping", "clearComposingRegion")) {
            assertTrue(code.contains(terminal),
                "typing animator terminal path $terminal must exist")
        }
        val removals = Regex("""removeCallbacks\(mTypingAnimator\)""").findAll(code).toList()
        assertTrue(removals.size >= 5,
            "expected >=5 typing-animator removals (queue/flush/fallback/clear/finish/release), " +
                "found ${removals.size}")
    }

    @Test
    fun `voice recording pane exposes a detach release clearing listeners and handler work`() {
        // The SDK pane owns button click listeners + mainHandler posts; the hide/detach
        // path must clear both so a recycled pane cannot hold the IME context.
        val code = paneSource()
        assertTrue(code.contains("fun release()"),
            "VoiceRecordingPane must expose release() (matched removal for its listeners)")
        val release = code.substringAfter("fun release()")
        val releaseBody = release.substring(0, minOf(release.length, 800))
        assertTrue(releaseBody.contains("removeCallbacksAndMessages"),
            "release() must drop pending handler runnables")
        assertTrue(releaseBody.contains("setOnClickListener(null)"),
            "release() must clear button click listeners")
        assertTrue(code.contains("onDetachedFromWindow"),
            "VoiceRecordingPane must release on detach so view recycle cannot leak the IME context")
    }
}
