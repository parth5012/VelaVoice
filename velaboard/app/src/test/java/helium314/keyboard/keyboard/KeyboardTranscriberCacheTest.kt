package helium314.keyboard.keyboard

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OCR review finding (session a7d7065a, KeyboardSwitcher.java:491): the transcriber
 * reuse cache validated modelPath/useLlm/effectiveScribe but not language, threads,
 * customFillers or llmModelPath, so a settings change between recordings silently
 * reused a transcriber built with the stale configuration.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardTranscriberCacheTest {

    private val switcher = KeyboardSwitcher.getInstance()

    private fun setCached(name: String, value: Any?) {
        val field = KeyboardSwitcher::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(switcher, value)
    }

    private fun matches(
        modelPath: String = "/models/whisper.bin",
        llmModelPath: String? = "/models/llm.bin",
        useLlm: Boolean = true,
        effectiveScribe: Boolean = false,
        language: String = "en",
        threads: Int = 4,
        customFillers: List<String> = listOf("um"),
    ) = switcher.cachedTranscriberConfigMatches(
        modelPath, llmModelPath, useLlm, effectiveScribe, language, threads, customFillers
    )

    @Before
    fun setUp() {
        setCached("mCachedVelaModelPath", "/models/whisper.bin")
        setCached("mCachedVelaLlmToggle", true)
        setCached("mCachedVelaScribeToggle", false)
        setCached("mCachedVelaLlmModelPath", "/models/llm.bin")
        setCached("mCachedVelaLanguage", "en")
        setCached("mCachedVelaThreads", 4)
        setCached("mCachedVelaCustomFillers", listOf("um"))
    }

    @After
    fun tearDown() {
        setCached("mCachedVelaModelPath", null)
        setCached("mCachedVelaLlmToggle", null)
        setCached("mCachedVelaScribeToggle", null)
        setCached("mCachedVelaLlmModelPath", null)
        setCached("mCachedVelaLanguage", null)
        setCached("mCachedVelaThreads", -1)
        setCached("mCachedVelaCustomFillers", null)
    }

    @Test
    fun `identical configuration is reusable`() {
        assertTrue("unchanged config must be reusable", matches())
    }

    @Test
    fun `changed language is not reusable`() {
        assertFalse(
            "a language change must invalidate the cached transcriber",
            matches(language = "fr")
        )
    }

    @Test
    fun `changed thread count is not reusable`() {
        assertFalse(
            "a thread-count change must invalidate the cached transcriber",
            matches(threads = 2)
        )
    }

    @Test
    fun `changed custom fillers are not reusable`() {
        assertFalse(
            "a custom-fillers change must invalidate the cached transcriber",
            matches(customFillers = listOf("um", "like"))
        )
    }

    @Test
    fun `changed llm model path is not reusable`() {
        assertFalse(
            "an LLM model path change must invalidate the cached transcriber",
            matches(llmModelPath = "/models/llm-v2.bin")
        )
    }

    @Test
    fun `changed whisper model path is not reusable`() {
        assertFalse(
            "a whisper model path change must invalidate the cached transcriber",
            matches(modelPath = "/models/whisper-v2.bin")
        )
    }

    @Test
    fun `changed llm toggle is not reusable`() {
        assertFalse(
            "an LLM toggle change must invalidate the cached transcriber",
            matches(useLlm = false)
        )
    }

    @Test
    fun `changed scribe toggle is not reusable`() {
        assertFalse(
            "a scribe toggle change must invalidate the cached transcriber",
            matches(effectiveScribe = true)
        )
    }
}
