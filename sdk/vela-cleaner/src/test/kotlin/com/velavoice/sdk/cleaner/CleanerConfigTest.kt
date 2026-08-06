package com.velavoice.sdk.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanerConfigTest {

    @Test
    fun `default config has all optional fields null or false`() {
        val config = CleanerConfig()
        assertEquals(false, config.useLlm)
        assertNull(config.llmModelPath)
        assertNull(config.personalDictionary)
        assertNull(config.customFillers)
        assertEquals(false, config.scribeEnabled)
        assertEquals("Professional", config.defaultScribeStyle)
        assertNull(config.customSystemPrompt)
    }

    @Test
    fun `config accepts custom values`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> = emptyList()
        }
        val config = CleanerConfig(
            useLlm = true,
            llmModelPath = "/path/to/model",
            personalDictionary = dict,
            customFillers = listOf("actually")
        )
        assertEquals(true, config.useLlm)
        assertEquals("/path/to/model", config.llmModelPath)
        assertEquals(dict, config.personalDictionary)
        assertEquals(listOf("actually"), config.customFillers)
    }

    @Test
    fun `config accepts scribe values`() {
        val config = CleanerConfig(
            useLlm = true,
            llmModelPath = "/path/to/model",
            scribeEnabled = true,
            defaultScribeStyle = "Casual",
            customSystemPrompt = "You are a custom assistant."
        )
        assertEquals(true, config.scribeEnabled)
        assertEquals("Casual", config.defaultScribeStyle)
        assertEquals("You are a custom assistant.", config.customSystemPrompt)
    }
}
