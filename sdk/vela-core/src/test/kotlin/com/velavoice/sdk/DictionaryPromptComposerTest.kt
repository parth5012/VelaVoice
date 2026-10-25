package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryPromptComposerTest {
    private val composer = DictionaryPromptComposer()

    @Test
    fun emptyEntriesReturnsNull() {
        assertNull(composer.compose(emptyList()))
    }

    @Test
    fun emptyDictionaryEdgeCase() {
        assertNull(composer.compose(listOf("", "  ", "a")))
    }

    @Test
    fun dedupsCaseInsensitive() {
        assertEquals("Foo, BAR", composer.compose(listOf("Foo", "foo", "BAR", "bar", "Foo")))
    }

    @Test
    fun skipsSingleChar() {
        assertEquals("Alice, Bob", composer.compose(listOf("A", "Alice", "B", "Bob")))
    }

    @Test
    fun commaSeparated() {
        assertEquals("Alice, Bob, Charlie", composer.compose(listOf("Alice", "Bob", "Charlie")))
    }

    @Test
    fun lengthCap() {
        val long = "A".repeat(900)
        val result = DictionaryPromptComposer(10).compose(listOf(long, "Bob"))
        // first entry truncated? Actually long exceeds cap; since chosen empty and entry.length>maxLength, break -> null
        assertNull(result)
    }

    @Test
    fun lengthCapPartial() {
        val result = DictionaryPromptComposer(12).compose(listOf("Alice", "Bob", "Charlie"))
        // "Alice" (5) + ", Bob" (5) = 10 <=12; + ", Charlie" (9) = 19 >12 -> stop
        assertEquals("Alice, Bob", result)
    }

    @Test
    fun priorityOrderingPreservesInput() {
        assertEquals("Zed, Alice", composer.compose(listOf("Zed", "Alice")))
    }
}
