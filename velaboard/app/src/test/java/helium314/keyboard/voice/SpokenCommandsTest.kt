// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpokenCommandsTest {

    @Test fun punctuationBasic() {
        assertEquals("hello.", SpokenCommands.applySpokenCommands("hello period"))
        assertEquals("hello, world?", SpokenCommands.applySpokenCommands("hello comma world question mark"))
        assertEquals("hi!", SpokenCommands.applySpokenCommands("hi exclamation mark"))
    }

    @Test fun caseInsensitive() {
        assertEquals("Hello.", SpokenCommands.applySpokenCommands("Hello Period"))
        assertEquals("a, b.", SpokenCommands.applySpokenCommands("a COMMA b PERIOD"))
    }

    @Test fun wordBoundaryNonMatch() {
        assertEquals("periodic", SpokenCommands.applySpokenCommands("periodic"))
        assertEquals("hello periodic", SpokenCommands.applySpokenCommands("hello periodic"))
    }

    @Test fun newLineAndParagraph() {
        assertEquals("a\nb", SpokenCommands.applySpokenCommands("a new line b"))
        assertEquals("a\n\nb", SpokenCommands.applySpokenCommands("a new paragraph b"))
    }

    @Test fun consecutiveCommands() {
        assertEquals("a..", SpokenCommands.applySpokenCommands("a period period"))
    }

    @Test fun commandAtStartAndEnd() {
        assertEquals(". hello", SpokenCommands.applySpokenCommands("period hello")) // leading punct
        // empty input
        assertEquals("", SpokenCommands.applySpokenCommands(""))
        assertEquals("", SpokenCommands.applySpokenCommands("   "))
    }

    @Test fun deleteLastWord() {
        assertEquals("hello", SpokenCommands.applySpokenCommands("hello world delete last word"))
        assertEquals("", SpokenCommands.applySpokenCommands("hello delete last word"))
    }

    @Test fun deleteLastSentence() {
        assertEquals("Hello.", SpokenCommands.applySpokenCommands("Hello. World delete last sentence"))
        assertEquals("", SpokenCommands.applySpokenCommands("Hello delete last sentence"))
    }

    @Test fun scratchThat() {
        assertEquals("hello", SpokenCommands.applySpokenCommands("hello world scratch that"))
    }

    @Test fun emojiExtension() {
        // "<name> emoji" phrase form produces the mapped emoji (#163)
        assertEquals("great \uD83D\uDD25 great", SpokenCommands.applySpokenCommands("great fire emoji great").trim())
        assertEquals("hi \uD83D\uDE0A", SpokenCommands.applySpokenCommands("hi smiley emoji").trim())
        assertEquals("\uD83D\uDC4D", SpokenCommands.applySpokenCommands("thumbs up emoji").trim())
        assertEquals("\u2764\uFE0F", SpokenCommands.applySpokenCommands("red heart emoji").trim())
        assertEquals("\uD83D\uDCAF nice", SpokenCommands.applySpokenCommands("100 emoji nice").trim())
    }

    @Test fun bareEmojiNameIsNotACommand() {
        // ticket #163: the bare name alone must stay literal
        assertEquals("fire", SpokenCommands.applySpokenCommands("fire"))
        assertEquals("give me a big thumbs up", SpokenCommands.applySpokenCommands("give me a big thumbs up"))
        assertEquals("love heart", SpokenCommands.applySpokenCommands("love heart"))
        // "emoji" alone is not a command either
        assertEquals("nice emoji", SpokenCommands.applySpokenCommands("nice emoji"))
        // word-boundary safe: "periodic" must not match "period"
        assertEquals("periodic", SpokenCommands.applySpokenCommands("periodic"))
    }

    @Test fun editSingleWordInput() {
        assertEquals("", SpokenCommands.applySpokenCommands("word delete last word"))
        assertEquals("", SpokenCommands.applySpokenCommands("word scratch that"))
    }
}
