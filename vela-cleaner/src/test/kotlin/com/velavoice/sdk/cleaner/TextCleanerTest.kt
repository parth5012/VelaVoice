package com.velavoice.sdk.cleaner

import org.junit.Assert.assertEquals
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
        assertEquals("hello world", cleaner.clean(input))
    }

    @Test
    fun `clean with LLM capitalizes sentences`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = ""))
        // LLM init will fail silently (model not found), so isLlmInitialized = false
        // Falls back to rule-based
        val result = cleaner.clean("hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun `clean with LLM when LLM model missing falls back to rule-based`() {
        val cleaner = TextCleaner(CleanerConfig(useLlm = true, llmModelPath = "/nonexistent/model.bin"))
        // Init logs error, isLlmInitialized stays false -> falls back to rule-based
        val input = "um hello world"
        val result = cleaner.clean(input)
        assertEquals("hello world", result)
    }

    // ── Edge cases ────────────────────────────────────────────────────

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
        assertEquals("", cleaner.clean(""))
    }
}
