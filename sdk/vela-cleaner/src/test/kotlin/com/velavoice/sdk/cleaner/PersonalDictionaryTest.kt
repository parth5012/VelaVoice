package com.velavoice.sdk.cleaner

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalDictionaryTest {

    @Test
    fun `interface can be implemented with custom entries`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> =
                listOf("a" to "b", "c" to "d")
        }
        val entries = dict.getEntries()
        assertEquals(2, entries.size)
        assertEquals("a" to "b", entries[0])
        assertEquals("c" to "d", entries[1])
    }

    @Test
    fun `interface returns empty list`() {
        val dict = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> = emptyList()
        }
        assertEquals(0, dict.getEntries().size)
    }
}
