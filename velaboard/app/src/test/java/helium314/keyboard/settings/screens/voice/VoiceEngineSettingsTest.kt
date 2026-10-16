package helium314.keyboard.settings.screens.voice

import helium314.keyboard.settings.screens.TranscriptionLogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #113: settings state hoisting + keyed history updates.
 *
 * - `VoiceEngineScreen` rebuilt its full preference list on every recompose
 *   and re-read `PREF_VELA_TRANSCRIPTION_MODE` outside `remember`, so
 *   unrelated recompositions re-ran pref reads and rebuilt `Setting` objects.
 *   The screen now hoists `mode` once and rebuilds only the keyed
 *   per-mode section via `remember(mode)` ([engineSettingKeysForMode]).
 * - The Sync & History list re-composed every row per insert; rows are now
 *   keyed by file name ([transcriptionHistoryKey]) with incremental
 *   [diffTranscriptionHistory] instead of a full rebuild.
 */
class VoiceEngineSettingsTest {

    @Test
    fun `mode resolves from prefs with default fallback`() {
        assertEquals("gemini", resolveTranscriptionMode({ "gemini" }, "local"))
        assertEquals("local", resolveTranscriptionMode({ null }, "local"))
    }

    @Test
    fun `unknown mode falls back to default instead of building nothing`() {
        assertEquals("local", resolveTranscriptionMode({ "wat" }, "local"))
    }

    @Test
    fun `per-mode setting keys are keyed and stable`() {
        val local = engineSettingKeysForMode("local")
        val gemini = engineSettingKeysForMode("gemini")
        // Shared sections keep stable keys across mode switches ...
        assertTrue(local.intersect(gemini.toSet()).contains("transcription_mode"))
        assertTrue(local.intersect(gemini.toSet()).contains("streaming_mode"))
        // ... while provider sections are keyed per mode (no full rebuild).
        assertTrue(gemini.contains("gemini_api_key"))
        assertTrue(gemini.contains("gemini_model"))
        assertTrue(local.none { it.startsWith("gemini_") })
        // Same mode twice builds the identical key list (remember-hit).
        assertEquals(gemini, engineSettingKeysForMode("gemini"))
    }

    @Test
    fun `switching modes diffs to inserts plus removals, never a full rebuild`() {
        val diff = diffSettingKeys(
            engineSettingKeysForMode("local"),
            engineSettingKeysForMode("gemini")
        )
        assertTrue("mode switch must keep shared keys, kept=${diff.kept}", diff.kept.isNotEmpty())
        assertTrue("gemini keys must arrive as inserts", diff.inserted.contains("gemini_api_key"))
        assertEquals("local has no provider keys to remove", 0, diff.removed.size)
        val back = diffSettingKeys(
            engineSettingKeysForMode("gemini"),
            engineSettingKeysForMode("local")
        )
        assertTrue(back.removed.contains("gemini_api_key"))
    }

    @Test
    fun `history rows key on stable file name`() {
        val item = TranscriptionLogItem("r", "c", 1L, "now", "t123.json", null, false)
        assertEquals("t123.json", transcriptionHistoryKey(item))
        // Distinct files never share a key (no row reuse across inserts).
        val other = item.copy(fileName = "t124.json")
        assertTrue(transcriptionHistoryKey(item) != transcriptionHistoryKey(other))
    }

    @Test
    fun `history append diffs to a single insert`() {
        val old = listOf(
            TranscriptionLogItem("a", "a", 1L, "t1", "t1.json", null, true)
        )
        val new = old + TranscriptionLogItem("b", "b", 2L, "t2", "t2.json", null, false)
        val diff = diffTranscriptionHistory(old, new)
        assertEquals(listOf("t2.json"), diff.inserted)
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.changed.isEmpty())
    }

    @Test
    fun `history sync flip diffs to a change, not remove plus insert`() {
        val old = listOf(
            TranscriptionLogItem("a", "a", 1L, "t1", "t1.json", null, false)
        )
        val new = listOf(
            TranscriptionLogItem("a", "a", 1L, "t1", "t1.json", null, true)
        )
        val diff = diffTranscriptionHistory(old, new)
        assertTrue(diff.inserted.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(listOf("t1.json"), diff.changed)
    }
}
