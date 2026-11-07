// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

import com.velavoice.sdk.ModelRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodeRabbit round-1 finding on ticket 806: the "Default Local Model" radio
 * list let users select model rows that have no published release yet
 * (`url` blank → nothing to download). Selectability must be gate-able, so it
 * lives in a pure helper covered here.
 */
class VoiceModelsScreenSelectionTest {

    @Test
    fun `rows without a published url are not selectable`() {
        val noUrl = ModelRegistry.ALL.first { it.url.isBlank() }
        assertFalse(isDefaultModelSelectable(noUrl))
    }

    @Test
    fun `rows with pinned urls are selectable`() {
        val withUrl = ModelRegistry.ALL.first { it.url.isNotBlank() }
        assertTrue(isDefaultModelSelectable(withUrl))
    }
}
