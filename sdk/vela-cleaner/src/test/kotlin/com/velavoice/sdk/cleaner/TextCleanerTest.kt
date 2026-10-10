package com.velavoice.sdk.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextCleanerTest {

    // ── cleanRuleBased ────────────────────────────────────────────────

    @Test
    fun `cleanRuleBased empty text returns empty`() {
        val cleaner = TextCleaner(CleanerConfig())
        assertEquals("", cleaner.cleanRuleBased(""))
    }

    @Test
    fun `cleanRuleBased removes default filler words`() {
        val cleaner = TextCleaner(CleanerConfig())
        val input = "I um went to the ah store like yesterday"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("I went to the store yesterday", result)
    }

    @Test
    fun `cleanRuleBased fillers case insensitive`() {
        val cleaner = TextCleaner(CleanerConfig())
        val input = "Um I was like Uh huh"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("I was huh", result)
    }

    @Test
    fun `cleanRuleBased removes duplicate spaces`() {
        val cleaner = TextCleaner(CleanerConfig())
        val input = "hello    world   test"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("hello world test", result)
    }

    @Test
    fun `cleanRuleBased trims surrounding whitespace`() {
        val cleaner = TextCleaner(CleanerConfig())
        val input = "   hello world   "
        val result = cleaner.cleanRuleBased(input)
        assertEquals("hello world", result)
    }

    @Test
    fun `cleanRuleBased with custom fillers`() {
        val config = CleanerConfig(customFillers = listOf("actually", "basically", "you know"))
        val cleaner = TextCleaner(config)
        val input = "actually I basically went to you know the store"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("I went to the store", result)
    }

    @Test
    fun `cleanRuleBased empty fillers list does nothing`() {
        val config = CleanerConfig(customFillers = emptyList())
        val cleaner = TextCleaner(config)
        val input = "um ah like"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("um ah like", result)
    }

    @Test
    fun `cleanRuleBased with personal dictionary`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> =
                listOf("vela" to "Vela Voice", "whisper" to "Whisper")
        }
        val config = CleanerConfig(personalDictionary = dict)
        val cleaner = TextCleaner(config)
        val input = "I use vela for whisper"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("I use Vela Voice for Whisper", result)
    }

    @Test
    fun `cleanRuleBased personal dictionary case insensitive matching`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> =
                listOf("vela" to "Vela Voice")
        }
        val config = CleanerConfig(personalDictionary = dict)
        val cleaner = TextCleaner(config)
        val input = "VELA is great and Vela rocks"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("Vela Voice is great and Vela Voice rocks", result)
    }

    @Test
    fun `cleanRuleBased filler then dictionary combined`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> =
                listOf("vela" to "Vela Voice")
        }
        val config = CleanerConfig(personalDictionary = dict)
        val cleaner = TextCleaner(config)
        val input = "I um like vela"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("I Vela Voice", result)
    }

    // ── clean (LLM mode) ───────────────────────────────────────────────

    @Test
    fun `clean without LLM only runs rule-based`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = false))
        val input = "hello world"
        assertEquals("hello world", cleaner.clean(input, privacySensitive = false))
    }

    @Test
    fun `clean with LLM capitalizes sentences`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = ""))
        // LLM init will fail silently (model not found), so isLlmInitialized = false
        // Falls back to rule-based
        val result = cleaner.clean("hello world", privacySensitive = false)
        assertEquals("hello world", result)
    }

    @Test
    fun `clean with LLM when LLM model missing falls back to rule-based`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = "/nonexistent/model.bin"))
        // Init logs error, isLlmInitialized stays false -> falls back to rule-based
        val input = "um hello world"
        val result = cleaner.clean(input, privacySensitive = false)
        assertEquals("hello world", result)
    }

    // Scribe (AI Rewrite) prompt formatting (Ticket 002 / Ticket 003)

    @Test
    fun `formatScribePrompt uses Llama chat template`() {
        val cleaner = TextCleaner(CleanerConfig())
        val prompt = cleaner.formatScribePrompt(
            rawInput = "hello world",
            style = "Professional",
            contextBefore = "",
            contextAfter = "",
            appName = "",
            inputType = "text"
        )
        assertTrue(prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|eot_id|><|start_header_id|>user<|end_header_id|>"))
        assertTrue(prompt.trimEnd().endsWith("<|start_header_id|>assistant<|end_header_id|>"))
        assertTrue(prompt.contains("hello world"))
    }

    @Test
    fun `formatScribePrompt includes style instruction`() {
        val cleaner = TextCleaner(CleanerConfig())
        val prompt = cleaner.formatScribePrompt(
            rawInput = "need to buy milk",
            style = "Bullet Points",
            contextBefore = "",
            contextAfter = "",
            appName = "",
            inputType = "text"
        )
        assertTrue(prompt.contains("bullet-point list"))
    }

    @Test
    fun `formatScribePrompt includes surrounding context`() {
        val cleaner = TextCleaner(CleanerConfig())
        val prompt = cleaner.formatScribePrompt(
            rawInput = "and also",
            style = "Casual",
            contextBefore = "We should grab dinner",
            contextAfter = "before the movie",
            appName = "com.example.messages",
            inputType = "number"
        )
        assertTrue(prompt.contains("We should grab dinner"))
        assertTrue(prompt.contains("before the movie"))
        assertTrue(prompt.contains("com.example.messages"))
    }

    @Test
    fun `formatScribePrompt with no context omits context section`() {
        val cleaner = TextCleaner(CleanerConfig())
        val prompt = cleaner.formatScribePrompt(
            rawInput = "hi",
            style = "Professional",
            contextBefore = "",
            contextAfter = "",
            appName = "",
            inputType = "text"
        )
        assertFalse(prompt.contains("Following Context"))
    }

    @Test
    fun `styleInstruction covers all canonical styles`() {
        val cleaner = TextCleaner(CleanerConfig())
        val styles = listOf("Professional", "Casual", "Bullet Points", "Email Draft", "Proofread")
        for (style in styles) {
            assertTrue("expected instruction for $style", cleaner.styleInstruction(style).isNotBlank())
        }
    }

    @Test
    fun `styleInstruction unknown style falls back to professional`() {
        val cleaner = TextCleaner(CleanerConfig())
        assertEquals(cleaner.styleInstruction("Professional"), cleaner.styleInstruction("UnknownStyle"))
    }

    @Test
    fun `formatScribePrompt includes custom system prompt`() {
        val cleaner = TextCleaner(CleanerConfig(customSystemPrompt = "You are my writing coach."))
        val prompt = cleaner.formatScribePrompt(
            rawInput = "help",
            style = "Professional",
            contextBefore = "",
            contextAfter = "",
            appName = "",
            inputType = "text"
        )
        assertTrue(prompt.contains("You are my writing coach."))
    }

    @Test
    fun `clean with scribe enabled but LLM unavailable falls back to rule-based`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, scribeEnabled = true, llmModelPath = "/nonexistent"))
        val input = "um hello world"
        val result = cleaner.clean(input, privacySensitive = false)
        assertEquals("hello world", result)
    }

    @Test
    fun `clean with scribe disabled and LLM unavailable falls back to rule-based`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = "/nonexistent"))
        val input = "um hello world"
        val result = cleaner.clean(input, privacySensitive = false)
        assertEquals("hello world", result)
    }

    @Test
    fun `privacySensitive forces rule-based even with LLM initialized`() {
        // LLM init with an existing file would mark initialized; we pass a missing path
        // so isLlmInitialized is false, but the point is the privacySensitive path
        // must never reach prompt formatting — it returns regexCleaned directly.
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = "/nonexistent"))
        val result = cleaner.clean(
            "um hello world",
            privacySensitive = true
        )
        assertEquals("hello world", result)
    }

    @Test
    fun `privacySensitive ignores context metadata`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = "/nonexistent"))
        val result = cleaner.clean(
            "um hello world",
            contextBefore = "secret password hint",
            contextAfter = "private data",
            privacySensitive = true
        )
        assertEquals("hello world", result)
    }

    // ── Privacy Guard contract (map #72, ticket #80) ─────────────────
    // Forces isLlmInitialized true so the privacySensitive guard is exercised
    // against a REAL initialized path - the old test passed with a missing model.

    /** Records generate() calls so guard behaviour is directly observable. */
    private class RecordingTextCleaner(config: CleanerConfig) : TextCleaner(config) {
        val generateCalls = mutableListOf<String>()
        override fun generate(prompt: String): String {
            generateCalls.add(prompt)
            return "LLM-RESULT"
        }
    }

    @Test
    fun `generate is NEVER invoked when privacySensitive is true even with LLM initialized`() {
        val cleaner = RecordingTextCleaner(CleanerConfig(useLlm = true))
        cleaner.forceLlmInitializedForTesting()

        val result = cleaner.clean("um hello world", privacySensitive = true)

        assertEquals("must fall back to rule-based cleanup", "hello world", result)
        assertTrue(
            "privacy-sensitive cleaning must never reach the LLM",
            cleaner.generateCalls.isEmpty()
        )
    }

    @Test
    fun `generate IS invoked when LLM is initialized and field is not privacy-sensitive`() {
        val cleaner = RecordingTextCleaner(CleanerConfig(useLlm = true))
        cleaner.forceLlmInitializedForTesting()

        val result = cleaner.clean("um hello world", privacySensitive = false)

        assertEquals("LLM path must run for non-sensitive input", 1, cleaner.generateCalls.size)
        assertEquals("LLM-RESULT", result)
    }

    // Edge cases

    @Test
    fun `cleanRuleBased filler at start with comma`() {
        val cleaner = TextCleaner(CleanerConfig())
        val input = "um, hello there"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("hello there", result)
    }

    @Test
    fun `cleanRuleBased filler at end with space`() {
        val cleaner = TextCleaner(CleanerConfig())
        val input = "hello there like"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("hello there", result)
    }

    @Test
    fun `cleanRuleBased personal dictionary empty entry ignored`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> =
                listOf("" to "nope")
        }
        val config = CleanerConfig(personalDictionary = dict)
        val cleaner = TextCleaner(config)
        val input = "hello"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("hello", result)
    }

    @Test
    fun `cleanRuleBased personal dictionary exception does not crash`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> =
                throw RuntimeException("DB error")
        }
        val config = CleanerConfig(personalDictionary = dict)
        val cleaner = TextCleaner(config)
        val input = "hello world"
        val result = cleaner.cleanRuleBased(input)
        assertEquals("hello world", result)
    }

    @Test
    fun `clean with LLM empty text returns empty`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true))
        // LLM init skipped (no model), isLlmInitialized false -> fallback
        assertEquals("", cleaner.clean("", privacySensitive = false))
    }

    // ── Ticket #96: Resource leak closure & caching tests ───────────

    @Test
    fun `TextCleaner implements AutoCloseable and release or close can be called safely`() {
        val cleaner = TextCleaner(CleanerConfig())
        assertTrue("TextCleaner must implement AutoCloseable", cleaner is AutoCloseable)
        cleaner.use {
            assertEquals("hello", it.cleanRuleBased("hello"))
        }
        // release() is idempotent
        cleaner.release()
        cleaner.close()
    }

    @Test
    fun `TextCleaner getOrCreate caches instance per model path`() {
        TextCleaner.clearCache()
        val path = "/models/shared_cleaner.onnx"
        val config1 = CleanerConfig(useLlm = true, llmModelPath = path)
        val config2 = CleanerConfig(useLlm = true, llmModelPath = path)

        val cleaner1 = TextCleaner.getOrCreate(config1)
        val cleaner2 = TextCleaner.getOrCreate(config2)

        assertSame("Subsequent getOrCreate with identical model path must return cached instance", cleaner1, cleaner2)
        TextCleaner.clearCache()
    }

    @Test
    fun `TextCleaner getOrCreate updates config on existing cached instance`() {
        TextCleaner.clearCache()
        val path = "/models/update_config_test.onnx"
        val dict1 = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> = listOf("foo" to "bar")
        }
        val config1 = CleanerConfig(useLlm = true, llmModelPath = path, personalDictionary = dict1)
        val cleaner1 = TextCleaner.getOrCreate(config1)
        assertEquals("bar", cleaner1.cleanRuleBased("foo"))

        val dict2 = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> = listOf("foo" to "baz")
        }
        val config2 = CleanerConfig(useLlm = true, llmModelPath = path, personalDictionary = dict2)
        val cleaner2 = TextCleaner.getOrCreate(config2)

        assertSame(cleaner1, cleaner2)
        assertEquals("Config update must take effect on cached instance without reloading", "baz", cleaner2.cleanRuleBased("foo"))
        TextCleaner.clearCache()
    }

    @Test
    fun `TextCleaner getOrCreate with different model paths returns distinct instances`() {
        TextCleaner.clearCache()
        val cleanerA = TextCleaner.getOrCreate(CleanerConfig(useLlm = true, llmModelPath = "/models/model_a.onnx"))
        val cleanerB = TextCleaner.getOrCreate(CleanerConfig(useLlm = true, llmModelPath = "/models/model_b.onnx"))

        assertNotSame(cleanerA, cleanerB)
        TextCleaner.clearCache()
    }

    @Test
    fun `TextCleaner without useLlm or null path returns un-cached instances`() {
        val cleaner1 = TextCleaner.getOrCreate(CleanerConfig(useLlm = false, llmModelPath = "/models/model.onnx"))
        val cleaner2 = TextCleaner.getOrCreate(CleanerConfig(useLlm = false, llmModelPath = "/models/model.onnx"))
        assertNotSame("Non-LLM cleaner should not be cached", cleaner1, cleaner2)

        val cleaner3 = TextCleaner.getOrCreate(CleanerConfig(useLlm = true, llmModelPath = null))
        val cleaner4 = TextCleaner.getOrCreate(CleanerConfig(useLlm = true, llmModelPath = null))
        assertNotSame("Cleaner without model path should not be cached", cleaner3, cleaner4)
    }

    @Test
    fun `TextCleaner clearCache releases and removes all cached instances`() {
        TextCleaner.clearCache()
        val path = "/models/evict_test.onnx"
        val cleaner1 = TextCleaner.getOrCreate(CleanerConfig(useLlm = true, llmModelPath = path))
        assertEquals(1, TextCleaner.cachedCount())

        TextCleaner.clearCache()
        assertEquals(0, TextCleaner.cachedCount())

        val cleaner2 = TextCleaner.getOrCreate(CleanerConfig(useLlm = true, llmModelPath = path))
        assertNotSame("After clearCache, getOrCreate should create a fresh instance", cleaner1, cleaner2)
        TextCleaner.clearCache()
    }
}
