package helium314.keyboard.keyboard

import android.os.Looper
import com.velavoice.sdk.RevisionMarker
import com.velavoice.sdk.StreamingPipeline
import com.velavoice.sdk.cleaner.CleanerConfig
import com.velavoice.sdk.cleaner.TextCleaner
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputConnection
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wayfinder map #208 ticket #215: the finalize/commit text contract locked in
 * grilling ticket #214, covering adversarial-review findings F04, F05 and F28
 * on the streamed-dictation path ([VelaStreamingSession]).
 *
 * Contract: the source of truth for editor verification is `raw0` (the actual
 * editor buffer content), NOT the spoken-command-cleaned `raw`; commit markers
 * with non-empty text REPLACE the composing span and reveal; typing is queued
 * from `mRevealed.length()` so joining spaces are preserved.
 *
 * The editor is a Mockito [RichInputConnection] injected into
 * LatinIME.mInputLogic.mConnection: streaming runs against whatever the
 * current InputConnection is, so the mock stands in for the app editor
 * (same seam idea as KeyboardTranscriberSessionReuseTest's factory seam).
 * `getTextBeforeCursor` answers from a simulated buffer string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class VelaStreamingSessionTest {

    private lateinit var latinIME: LatinIME
    private val pipeline = mock(StreamingPipeline::class.java)
    private val connection = mock(RichInputConnection::class.java)
    private val textCleaner = mock(TextCleaner::class.java)

    /** Simulated editor buffer the mocked connection reports via getTextBeforeCursor. */
    private var editorText = ""

    private var finishedCount = 0
    private var finishedCancelled = false

    private val listener = object : VelaStreamingSession.Listener {
        override fun onStreamingAmplitude(normalized: Float) {}
        override fun onStreamingFinished(cancelled: Boolean) {
            finishedCount++
            finishedCancelled = cancelled
        }
        override fun onStreamingError(message: String) {}
    }

    /**
     * Kotlin-declared wrapper: Mockito's matchers return Java-generic values the
     * Kotlin compiler null-checks at the call site (see KeyboardScribeStyleSessionTest).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(type: Class<T>): T = ArgumentMatchers.any() as T

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)

        // Inject the mocked editor connection where VelaStreamingSession reads it.
        val logicField = LatinIME::class.java.getDeclaredField("mInputLogic")
        logicField.isAccessible = true
        val inputLogic = logicField.get(latinIME)
        val connectionField = inputLogic.javaClass.getDeclaredField("mConnection")
        connectionField.isAccessible = true
        connectionField.set(inputLogic, connection)

        // The typing animator must not enter batch fallback; composing edits succeed.
        Mockito.doReturn(true).`when`(connection)
            .setComposingText(anyArg(CharSequence::class.java), ArgumentMatchers.anyInt())
        // getTextBeforeCursor reports the tail of the simulated editor buffer.
        Mockito.doAnswer { inv ->
            val n = inv.getArgument<Int>(0)
            editorText.takeLast(n.coerceAtMost(editorText.length))
        }.`when`(connection).getTextBeforeCursor(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())
    }

    @AfterTest
    fun tearDown() {
        ShadowInputMethodService.reset()
    }

    // ---------- helpers ----------

    private fun newSession(cleanupSpec: VelaStreamingSession.CleanupSpec? = null): VelaStreamingSession {
        val session = VelaStreamingSession(latinIME, pipeline, "cloud", true, cleanupSpec, listener)
        return session
    }

    private fun partial(session: VelaStreamingSession, text: String, revisionId: Long) {
        session.onRevisionMarker(RevisionMarker("partial", text, 0 until text.length, revisionId))
    }

    private fun commit(session: VelaStreamingSession, text: String, revisionId: Long) {
        session.onRevisionMarker(RevisionMarker("commit", text, 0 until text.length, revisionId))
    }

    /** Runs every posted (and re-posted) handler task, including the delayed typing animator. */
    private fun idleAll() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        // The typing animator reposts itself every TYPING_INTERVAL_MS; advance the
        // clock so every repost comes due and the queue drains fully.
        looper.idle(2000L, TimeUnit.MILLISECONDS)
        looper.idle()
    }

    /** Drains the main looper until the session finished or the timeout hits. */
    private fun awaitFinished(session: VelaStreamingSession, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!session.isActive) return
            Thread.sleep(10)
        }
    }

    private fun readField(session: VelaStreamingSession, name: String): String {
        val f = VelaStreamingSession::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(session).toString()
    }

    private fun invokePrivate(session: VelaStreamingSession, name: String) {
        val m = VelaStreamingSession::class.java.getDeclaredMethod(name)
        m.isAccessible = true
        m.invoke(session)
    }

    /** Last composing text the session left in the editor = what the user sees. */
    private fun lastRenderedComposing(): String {
        val captor: ArgumentCaptor<CharSequence> = ArgumentCaptor.forClass(CharSequence::class.java)
        Mockito.verify(connection, Mockito.atLeastOnce())
            .setComposingText(captor.capture(), ArgumentMatchers.anyInt())
        return captor.allValues.last().toString()
    }

    /**
     * Seeds the TextCleaner model cache with the mocked cleaner so the finalize
     * cleanup pass (TextCleaner.getOrCreate inside mCleanerTask) resolves it
     * without loading ONNX, and pre-runs the cleaner FutureTask so runCleanup's
     * get() returns immediately instead of blocking 10s.
     */
    private fun seedCleaner(session: VelaStreamingSession) {
        // Kotlin compiles companion properties to a static backing field on the
        // enclosing class; fall back to the Companion instance just in case.
        val cacheField = try {
            TextCleaner::class.java.getDeclaredField("modelCache")
        } catch (e: NoSuchFieldException) {
            TextCleaner.Companion::class.java.getDeclaredField("modelCache")
        }
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(TextCleaner.Companion) as ConcurrentHashMap<String, TextCleaner>
        cache["vela-test-model"] = textCleaner
        Mockito.doReturn("Hello, world.").`when`(textCleaner).clean(
            anyArg(String::class.java), anyArg(String::class.java), anyArg(String::class.java),
            anyArg(String::class.java), anyArg(String::class.java), anyArg(String::class.java),
            ArgumentMatchers.anyBoolean())

        val taskField = VelaStreamingSession::class.java.getDeclaredField("mCleanerTask")
        taskField.isAccessible = true
        (taskField.get(session) as FutureTask<*>).run()
    }

    // ---------- F05: streamed partials lose joining spaces ----------

    @Test
    fun `two streamed partials render with the joining space preserved`() {
        val session = newSession()
        partial(session, "hello", 1)
        idleAll()
        partial(session, "world", 2)
        idleAll()

        assertEquals("hello world", readField(session, "mRevealed"),
            "the joining separator appended by appendDelta must be typed into the editor too")
        assertEquals("hello world", lastRenderedComposing(),
            "the editor must show the two-delta sentence with the joining space, not glued words")
    }

    // ---------- F28: commit-marker transcript text dropped ----------

    @Test
    fun `commit marker with a correction replaces the streamed composing span`() {
        val session = newSession()
        partial(session, "hello worl", 1)
        idleAll()
        commit(session, "hello world.", 2)
        idleAll()

        assertEquals("hello world.", lastRenderedComposing(),
            "the commit marker carries the authoritative transcript: server-side corrections " +
                "must replace the composing span instead of being dropped")
        assertEquals("hello world.", readField(session, "mCommitted"),
            "the internal model must record the commit text as committed")
        Mockito.verify(connection).finishComposingText()
    }

    @Test
    fun `commit marker with an empty composing span still appends its text`() {
        val session = newSession()
        commit(session, "Hello", 1)
        idleAll()

        assertEquals("Hello", lastRenderedComposing())
        assertEquals("Hello", readField(session, "mCommitted"))
        Mockito.verify(connection).finishComposingText()
    }

    // ---------- F04: finalize verifies the editor against the spoken-cleaned raw ----------

    @Test
    fun `finalize swaps the composing span using the actual editor content raw0`() {
        val session = newSession(VelaStreamingSession.CleanupSpec(
            CleanerConfig(useLlm = true, llmModelPath = "vela-test-model"),
            "", "", "com.test.app", "text", null))
        seedCleaner(session)

        partial(session, "hello world period", 1)
        idleAll()
        // Editor holds exactly what was streamed; spoken commands turn the
        // trailing "period" into "." so the cleaned raw differs from raw0.
        editorText = "hello world period"
        invokePrivate(session, "finalizeSession")
        awaitFinished(session)

        Mockito.verify(connection).setComposingText(
            ArgumentMatchers.eq("Hello, world."), ArgumentMatchers.eq(1))
        Mockito.verify(connection).finishComposingText()
        assertEquals("Hello, world.", readField(session, "mCommitted"),
            "the cleaned text must reach the editor and the model")
    }

    @Test
    fun `finalize committed swap verifies against raw0 and deletes the raw0 length`() {
        val session = newSession(VelaStreamingSession.CleanupSpec(
            CleanerConfig(useLlm = true, llmModelPath = "vela-test-model"),
            "", "", "com.test.app", "text", null))
        seedCleaner(session)

        partial(session, "hello world period", 1)
        idleAll()
        commit(session, "hello world period", 2)
        idleAll()
        editorText = "hello world period"
        invokePrivate(session, "finalizeSession")
        awaitFinished(session)

        val deleteCaptor: ArgumentCaptor<Int> = ArgumentCaptor.forClass(Int::class.java as Class<Int>)
        Mockito.verify(connection).deleteTextBeforeCursor(deleteCaptor.capture())
        assertEquals("hello world period".length, deleteCaptor.allValues.first(),
            "the delete count must match the actual editor content (raw0), " +
                "not the spoken-command-cleaned text")
        Mockito.verify(connection).commitText(
            ArgumentMatchers.eq("Hello, world."), ArgumentMatchers.eq(1))
        assertEquals("Hello, world.", readField(session, "mCommitted"))
    }

    // ---------- regression guard: commit of an identical segment is idempotent ----------

    @Test
    fun `a stale redelivered commit with empty composing is not inserted twice`() {
        val session = newSession()
        partial(session, "Hello", 1)
        idleAll()
        commit(session, "Hello", 2)
        idleAll()
        commit(session, "Hello", 3)
        idleAll()

        assertEquals("Hello", readField(session, "mCommitted"))
        assertTrue(finishedCount == 0, "session must still be active")
        val captor: ArgumentCaptor<CharSequence> = ArgumentCaptor.forClass(CharSequence::class.java)
        Mockito.verify(connection, Mockito.atLeastOnce())
            .setComposingText(captor.capture(), ArgumentMatchers.anyInt())
        assertEquals(1, captor.allValues.count { it.toString() == "Hello" },
            "an already-present segment text must not be inserted a second time")
    }
}
