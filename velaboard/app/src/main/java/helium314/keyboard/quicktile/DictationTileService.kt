package helium314.keyboard.quicktile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.velavoice.sdk.TranscriptionResult
import com.velavoice.sdk.VelaException
import com.velavoice.sdk.VelaRecordingCallback
import com.velavoice.sdk.VelaTranscriber
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.ModelDownloadHelper
import java.io.File

class DictationTileService : TileService() {

    private var transcriber: VelaTranscriber? = null
    private var recording: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (recording) {
            transcriber?.stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val modelPath = resolveModelPath()
        if (modelPath == null) {
            handler.post {
                Toast.makeText(this, "Local Whisper model missing. Open Vela settings to download.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val t = VelaTranscriber.Builder(this)
            .whisperModel(modelPath)
            .language("en")
            .threads(4)
            .build()
        transcriber = t
        recording = true
        refreshTile()
        t.startRecording(object : VelaRecordingCallback {
            override fun onAmplitude(normalized: Float) {}
            override fun onResult(result: TranscriptionResult) {
                val text = result.cleanedTranscript.ifBlank { result.rawTranscript }
                copyToClipboard(text)
                handler.post {
                    Toast.makeText(this@DictationTileService, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                recording = false
                transcriber = null
                refreshTile()
            }
            override fun onError(error: VelaException) {
                handler.post {
                    Toast.makeText(this@DictationTileService, error.message ?: "Error", Toast.LENGTH_SHORT).show()
                }
                recording = false
                transcriber = null
                refreshTile()
            }
            override fun onDurationLimitReached() {}
        })
    }

    private fun refreshTile() {
        handler.post {
            qsTile?.apply {
                state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = if (recording) "Listening…" else "Dictate"
                updateTile()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
    }

    private fun resolveModelPath(): String? {
        val prefs = getSharedPreferences("vela", Context.MODE_PRIVATE)
        val shared = ModelDownloadHelper.getSharedModelPath(this, "whisper")
        if (shared != null) return shared
        val legacy = prefs.getString(Settings.PREF_VELA_MODEL_PATH, Defaults.PREF_VELA_MODEL_PATH)
        if (!legacy.isNullOrBlank() && File(legacy).exists()) return legacy
        return null
    }
}
