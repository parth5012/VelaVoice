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
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.ModelDownloadHelper
import java.io.File

class DictationTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Session state lives on the companion object so the recording survives
     * the tile service being unbound/destroyed: the system tears down a
     * non-active TileService once the quick settings shade closes, and a
     * later tile instance must still be able to stop that session and show
     * its state (#207 review round). The recording transcriber is built on
     * the application context so the callbacks never touch a dead service
     * instance. Residual limitation: process death still ends the session —
     * a full foreground-service migration stays parked on #175.
     *
     * Deliberately NOT released in onDestroy: releasing a live session there
     * would cut off dictation exactly during teardown; the running session
     * finalizes itself via the [VelaRecordingCallback] result/error paths.
     */
    private val activeSession: VelaTranscriber? get() = Companion.activeSession

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val session = activeSession
        if (session != null) {
            try {
                session.stopRecording()
            } catch (e: Throwable) {
                Companion.activeSession = null
                toast(applicationContext, "Dictation session lost: ${e.message ?: e.javaClass.simpleName}")
                refreshTile()
            }
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        // Hardened start path (#207 smoke bug): any SDK/engine initialization
        // error on the tile surface must surface as a toast instead of killing
        // the hosting process (which also runs the IME).
        val appContext = applicationContext
        try {
            startRecordingInner(appContext)
        } catch (e: Throwable) {
            Companion.activeSession?.release()
            Companion.activeSession = null
            refreshTile()
            toastLong(appContext, "Dictation unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startRecordingInner(appContext: Context) {
        val modelPath = resolveModelPath(appContext)
        if (modelPath == null) {
            toastLong(appContext, "Local Whisper model missing. Open Vela settings to download.")
            return
        }
        // Follow the voice-settings language/threads the main dictation path
        // uses (same prefs store as Settings.prefs(); #207 review round).
        val prefs = appContext.prefs()
        val language = prefs.getString(Settings.PREF_VELA_LANGUAGE, null)
            ?.takeUnless { it.isBlank() } ?: Defaults.PREF_VELA_LANGUAGE
        val threads = prefs.getInt(Settings.PREF_VELA_THREADS, Defaults.PREF_VELA_THREADS)
            .coerceIn(1, 8)
        val t = VelaTranscriber.Builder(appContext)
            .whisperModel(modelPath)
            .language(language)
            .threads(threads)
            .build()
        Companion.activeSession = t
        refreshTile()
        t.startRecording(object : VelaRecordingCallback {
            override fun onAmplitude(normalized: Float) {}
            override fun onResult(result: TranscriptionResult) {
                Companion.activeSession?.release()
                Companion.activeSession = null
                val text = result.cleanedTranscript.ifBlank { result.rawTranscript }
                copyToClipboard(appContext, text)
                toast(appContext, "Copied to clipboard")
                refreshTile()
            }
            override fun onError(error: VelaException) {
                Companion.activeSession?.release()
                Companion.activeSession = null
                toast(appContext, error.message ?: "Error")
                refreshTile()
            }
            override fun onDurationLimitReached() {
                // AudioRecorder contract (#104): at the cap the recorder stops
                // collecting and the caller must stop so transcription runs.
                activeSession?.stopRecording()
            }
        })
    }

    private fun refreshTile() {
        handler.post {
            qsTile?.apply {
                state = if (activeSession != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                label = if (activeSession != null) "Listening…" else "Dictate"
                updateTile()
            }
        }
    }

    private fun toast(context: Context, message: String) {
        handler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun toastLong(context: Context, message: String) {
        handler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
    }

    private fun resolveModelPath(context: Context): String? {
        // Same prefs store the settings screens write to (device-protected).
        val shared = ModelDownloadHelper.getSharedModelPath(context, "whisper")
        if (shared != null) return shared
        val legacy = context.prefs().getString(Settings.PREF_VELA_MODEL_PATH, Defaults.PREF_VELA_MODEL_PATH)
        if (!legacy.isNullOrBlank() && File(legacy).exists()) return legacy
        return null
    }

    companion object {
        @Volatile
        private var activeSession: VelaTranscriber? = null
    }
}
