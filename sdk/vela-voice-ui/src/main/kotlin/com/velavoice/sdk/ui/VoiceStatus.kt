package com.velavoice.sdk.ui

import androidx.annotation.StringRes

/**
 * Voice-pane states with user-visible text (map #105 ticket #115). Every
 * status resolves to a `strings.xml` entry so the pane is translatable;
 * [VoiceRecordingPane.setStatus] announces each change for screen readers.
 * Dynamic content (live partials, engine error details) flows through
 * `setStatusText`, never as inline literals in callers.
 */
enum class VoiceStatus(@StringRes val stringResId: Int) {
    LISTENING(R.string.vela_voice_status_listening),
    INITIALIZING(R.string.vela_voice_status_initializing),
    RECORDING(R.string.vela_voice_status_recording),
    STREAMING(R.string.vela_voice_status_streaming),
    PROCESSING(R.string.vela_voice_status_processing),
    DONE(R.string.vela_voice_status_done),
    NOTHING_HEARD(R.string.vela_voice_status_nothing_heard),
    READY(R.string.vela_voice_status_ready),
    TIME_LIMIT(R.string.vela_voice_status_time_limit),
    NO_MODEL(R.string.vela_voice_status_no_model),
    NO_MODEL_DOWNLOAD(R.string.vela_voice_status_no_model_download),
    INIT_ERROR(R.string.vela_voice_status_init_error),
    STREAMING_ERROR(R.string.vela_voice_status_streaming_error),
}
