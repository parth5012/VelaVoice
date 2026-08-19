// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.TranscriptionStorage
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

data class TranscriptionLogItem(
    val raw: String,
    val cleaned: String,
    val durationMs: Long,
    val createdAt: String,
    val fileName: String,
    val audioFileName: String?,
    val isSynced: Boolean
)

fun loadRecentTranscriptions(context: Context, limit: Int): List<TranscriptionLogItem> {
    val dir = java.io.File(context.filesDir, "transcriptions")
    if (!dir.exists()) return emptyList()
    val files = dir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList()
    val sortedFiles = files.sortedByDescending { it.name }
    val result = mutableListOf<TranscriptionLogItem>()
    val sliceLimit = if (limit <= 0) sortedFiles.size else minOf(limit, sortedFiles.size)
    for (i in 0 until sliceLimit) {
        val file = sortedFiles[i]
        val pair = TranscriptionStorage.readTranscriptionFile(file)
        if (pair != null) {
            val isSynced = java.io.File(file.parent, "${file.name}.synced").exists()
            result.add(TranscriptionLogItem(
                raw = pair.raw,
                cleaned = pair.cleaned,
                durationMs = pair.durationMs,
                createdAt = pair.createdAt,
                fileName = pair.fileName,
                audioFileName = pair.audioFileName,
                isSynced = isSynced
            ))
        }
    }
    return result
}

fun createVoiceSettings(context: Context): List<Setting> = listOf(
    Setting(context, Settings.PREF_VELA_MODEL_PATH, R.string.voice_model_path_title, R.string.voice_model_path_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_MODEL_PATH)
    },
    Setting(context, Settings.PREF_VELA_LLM_TOGGLE, R.string.voice_llm_toggle_title, R.string.voice_llm_toggle_summary) {
        SwitchPreference(setting = it, default = Defaults.PREF_VELA_LLM_TOGGLE)
    },
    Setting(context, Settings.PREF_VELA_LLM_MODEL_PATH, R.string.voice_llm_model_path_title, R.string.voice_llm_model_path_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_LLM_MODEL_PATH)
    },
    Setting(context, Settings.PREF_VELA_SCRIBE_ENABLED, R.string.voice_scribe_enabled_title, R.string.voice_scribe_enabled_summary) {
        SwitchPreference(setting = it, default = Defaults.PREF_VELA_SCRIBE_ENABLED)
    },
    Setting(context, Settings.PREF_VELA_SCRIBE_STYLE, R.string.voice_scribe_style_title, R.string.voice_scribe_style_summary) {
        ListPreference(
            setting = it,
            items = listOf(
                context.getString(R.string.voice_scribe_style_professional) to "Professional",
                context.getString(R.string.voice_scribe_style_casual) to "Casual",
                context.getString(R.string.voice_scribe_style_bullet_points) to "Bullet Points",
                context.getString(R.string.voice_scribe_style_email_draft) to "Email Draft",
                context.getString(R.string.voice_scribe_style_proofread) to "Proofread"
            ),
            default = Defaults.PREF_VELA_SCRIBE_STYLE
        )
    },
    Setting(context, Settings.PREF_VELA_SCRIBE_CONTEXT_FALLBACK, R.string.voice_scribe_context_fallback_title, R.string.voice_scribe_context_fallback_summary) {
        SwitchPreference(setting = it, default = Defaults.PREF_VELA_SCRIBE_CONTEXT_FALLBACK)
    },
    Setting(context, Settings.PREF_VELA_LANGUAGE, R.string.voice_language_title, R.string.voice_language_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_LANGUAGE)
    },
    Setting(context, Settings.PREF_VELA_THREADS, R.string.voice_threads_title, R.string.voice_threads_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_THREADS.toString())
    },
    Setting(context, Settings.PREF_VELA_CUSTOM_FILLERS, R.string.voice_custom_fillers_title, R.string.voice_custom_fillers_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_FILLERS)
    },
    Setting(context, Settings.PREF_VELA_TRANSCRIPTION_MODE, R.string.voice_transcription_mode_title, R.string.voice_transcription_mode_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_TRANSCRIPTION_MODE)
    },
    Setting(context, Settings.PREF_VELA_STREAMING_MODE, R.string.voice_streaming_mode_title, R.string.voice_streaming_mode_summary) {
        ListPreference(
            setting = it,
            items = listOf(
                context.getString(R.string.voice_streaming_mode_instant) to "instant",
                context.getString(R.string.voice_streaming_mode_streamed) to "streamed"
            ),
            default = Defaults.PREF_VELA_STREAMING_MODE
        )
    },
    Setting(context, Settings.PREF_VELA_GROQ_API_KEY, R.string.voice_groq_api_key_title, R.string.voice_groq_api_key_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_GROQ_API_KEY)
    },
    Setting(context, Settings.PREF_VELA_GROQ_MODEL, R.string.voice_groq_model_title, R.string.voice_groq_model_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_GROQ_MODEL)
    },
    Setting(context, Settings.PREF_VELA_OPENAI_API_KEY, R.string.voice_openai_api_key_title, R.string.voice_openai_api_key_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_OPENAI_API_KEY)
    },
    Setting(context, Settings.PREF_VELA_OPENAI_MODEL, R.string.voice_openai_model_title, R.string.voice_openai_model_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_OPENAI_MODEL)
    },
    Setting(context, Settings.PREF_VELA_OPENAI_ENDPOINT, R.string.voice_openai_endpoint_title, R.string.voice_openai_endpoint_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_OPENAI_ENDPOINT)
    },
    Setting(context, Settings.PREF_VELA_CUSTOM_API_KEY, R.string.voice_custom_api_key_title, R.string.voice_custom_api_key_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_API_KEY)
    },
    Setting(context, Settings.PREF_VELA_CUSTOM_MODEL, R.string.voice_custom_model_title, R.string.voice_custom_model_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_MODEL)
    },
    Setting(context, Settings.PREF_VELA_CUSTOM_ENDPOINT, R.string.voice_custom_endpoint_title, R.string.voice_custom_endpoint_summary) {
        TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_ENDPOINT)
    }
)
