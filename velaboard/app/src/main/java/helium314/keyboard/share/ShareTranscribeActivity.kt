// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.velavoice.sdk.VelaTranscriber
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.ModelDownloadHelper
import helium314.keyboard.settings.TranscriptionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ShareTranscribeActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var state by mutableStateOf<UiState>(UiState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = extractSharedUri(intent)
        setContent {
            when (val s = state) {
                is UiState.Loading -> AlertDialog(
                    onDismissRequest = { finish() },
                    confirmButton = { },
                    title = { Text("Transcribing") },
                    text = { CircularProgressIndicator() }
                )
                is UiState.MissingModel -> AlertDialog(
                    onDismissRequest = { finish() },
                    confirmButton = { Button(onClick = { finish() }) { Text("OK") } },
                    title = { Text("Model required") },
                    text = { Text("Local Whisper model is not installed. Open Vela settings to download it, then share again.") }
                )
                is UiState.Result -> AlertDialog(
                    onDismissRequest = { finish() },
                    confirmButton = { TextButton(onClick = { copy(s.text); finish() }) { Text("Copy") } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Close") } },
                    title = { Text("Transcript") },
                    text = {
                        Column(Modifier.fillMaxWidth().padding(4.dp)) {
                            Text(s.text)
                            TextButton(onClick = { shareText(s.text) }) { Text("Share as text") }
                            TextButton(onClick = { save(s.text); finish() }) { Text("Save") }
                        }
                    }
                )
                is UiState.Error -> AlertDialog(
                    onDismissRequest = { finish() },
                    confirmButton = { Button(onClick = { finish() }) { Text("OK") } },
                    title = { Text("Transcription failed") },
                    text = { Text(s.message) }
                )
            }
        }

        if (uri == null) {
            state = UiState.Error("No audio shared.")
            return
        }

        scope.launch {
            val modelPath = resolveModelPath()
            if (modelPath == null) {
                state = UiState.MissingModel
                return@launch
            }
            try {
                val pcm = withContext(Dispatchers.IO) { decodeToPcm(this@ShareTranscribeActivity, uri) }
                val text = withContext(Dispatchers.IO) { transcribe(modelPath, pcm) }
                state = UiState.Result(text.ifBlank { "(empty transcript)" })
            } catch (e: Exception) {
                state = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun resolveModelPath(): String? {
        val prefs = getSharedPreferences("vela", Context.MODE_PRIVATE)
        val shared = ModelDownloadHelper.getSharedModelPath(this, "whisper")
        if (shared != null) return shared
        val legacy = prefs.getString(Settings.PREF_VELA_MODEL_PATH, Defaults.PREF_VELA_MODEL_PATH)
        if (!legacy.isNullOrBlank() && File(legacy).exists()) return legacy
        return null
    }

    private fun transcribe(modelPath: String, pcm: ByteArray): String {
        val transcriber = VelaTranscriber.Builder(this)
            .whisperModel(modelPath)
            .language("en")
            .threads(4)
            .build()
        return try {
            val result = transcriber.transcribe(pcm)
            result.rawTranscript ?: ""
        } finally {
            transcriber.release()
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share transcript"))
    }

    private fun save(text: String) {
        TranscriptionStorage.save(
            context = this,
            raw = text,
            cleaned = text,
            durationMs = 0L,
            audioBytes = null,
            privacySensitive = false
        )
    }

    private fun decodeToPcm(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return bytes
    }

    companion object {
        fun extractSharedUri(intent: Intent?): Uri? {
            if (intent == null) return null
            if (intent.action != Intent.ACTION_SEND) return null
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) return uri
            return intent.data
        }
    }

    sealed class UiState {
        object Loading : UiState()
        object MissingModel : UiState()
        data class Result(val text: String) : UiState()
        data class Error(val message: String) : UiState()
    }
}
