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
}
