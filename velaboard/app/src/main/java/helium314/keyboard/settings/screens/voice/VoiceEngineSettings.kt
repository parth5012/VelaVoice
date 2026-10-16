// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

import helium314.keyboard.settings.screens.TranscriptionLogItem

/**
 * Hoisted settings state + keyed list contracts (map #105 ticket #113).
 *
 * `VoiceEngineScreen` rebuilt its full preference list on every recompose and
 * re-read `PREF_VELA_TRANSCRIPTION_MODE` outside `remember`, so unrelated
 * recompositions re-ran pref reads and rebuilt `Setting` objects (including
 * the per-mode provider sections). The screen now reads the mode once via
 * [resolveTranscriptionMode], holds it as hoisted state, and rebuilds only
 * the keyed per-mode section via `remember(mode)` over
 * [engineSettingKeysForMode]. [diffSettingKeys] is the keyed-update contract:
 * mode switches apply as inserts + removals of provider keys while shared
 * keys are kept (never a full rebuild).
 *
 * The Sync & History list likewise re-composed every row per insert; rows are
 * keyed by [transcriptionHistoryKey] with incremental [diffTranscriptionHistory]
 * (append = one insert, sync flip = one change) instead of a full rebuild.
 *
 * Pure Kotlin (prefs enter as a reader lambda) so this stays plain-JUnit
 * testable without Robolectric or Compose.
 */

/** Transcription modes the engine screen can render. */
val ENGINE_MODES = listOf("local", "gemini", "groq", "openai", "custom")

/**
 * Single pref read for the engine screen's hoisted mode state. Unknown values
 * fall back to [default] so the screen always renders a known section.
 */
fun resolveTranscriptionMode(
    getString: (String) -> String?,
    default: String
): String {
    val mode = getString("vela_transcription_mode") ?: return default
    return if (mode in ENGINE_MODES) mode else default
}

/**
 * Stable keys for the engine screen's settings, in display order. Shared
 * sections keep identical keys across modes so `remember(mode)` + keyed
 * composition reuses them; only provider keys come and go.
 */
fun engineSettingKeysForMode(mode: String): List<String> = buildList {
    add("transcription_mode")
    add("streaming_mode")
    when (mode) {
        "gemini" -> {
            add("gemini_api_key")
            add("gemini_model")
        }
        "groq" -> {
            add("groq_api_key")
            add("groq_model")
        }
        "openai" -> {
            add("openai_api_key")
            add("openai_model")
            add("openai_endpoint")
        }
        "custom" -> {
            add("custom_api_key")
            add("custom_model")
            add("custom_endpoint")
        }
    }
}

/** Keyed update between two engine setting key lists. */
data class SettingKeysDiff(
    val kept: List<String>,
    val inserted: List<String>,
    val removed: List<String>
)

fun diffSettingKeys(old: List<String>, new: List<String>): SettingKeysDiff {
    val newSet = new.toSet()
    val oldSet = old.toSet()
    return SettingKeysDiff(
        kept = old.filter { it in newSet },
        inserted = new.filter { it !in oldSet },
        removed = old.filter { it !in newSet }
    )
}

/** Stable Compose key for one history row: the on-disk file name. */
fun transcriptionHistoryKey(item: TranscriptionLogItem): String = item.fileName

/** Incremental update between two history snapshots, keyed by file name. */
data class HistoryDiff(
    val inserted: List<String>,
    val removed: List<String>,
    val changed: List<String>
)

fun diffTranscriptionHistory(
    old: List<TranscriptionLogItem>,
    new: List<TranscriptionLogItem>
): HistoryDiff {
    val oldByKey = old.associateBy(::transcriptionHistoryKey)
    val newByKey = new.associateBy(::transcriptionHistoryKey)
    return HistoryDiff(
        inserted = new.map(::transcriptionHistoryKey).filter { it !in oldByKey },
        removed = old.map(::transcriptionHistoryKey).filter { it !in newByKey },
        changed = new.filter { item ->
            val key = transcriptionHistoryKey(item)
            val prev = oldByKey[key]
            prev != null && prev != item
        }.map(::transcriptionHistoryKey)
    )
}
