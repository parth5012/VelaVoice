// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.ModelDownloadHelper
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.TextInputPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun VoiceModelsScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val context = LocalContext.current

    var whisperStatus by remember {
        mutableStateOf(ModelDownloadHelper.getStatus(prefs, context, "whisper"))
    }
    var llmStatus by remember {
        mutableStateOf(ModelDownloadHelper.getStatus(prefs, context, "llm"))
    }
    var whisperProgress by remember { mutableStateOf(0f) }
    var llmProgress by remember { mutableStateOf(0f) }
    var whisperPath by remember {
        mutableStateOf(prefs.getString(Settings.PREF_VELA_MODEL_PATH, null) ?: Defaults.PREF_VELA_MODEL_PATH)
    }
    var llmPath by remember {
        mutableStateOf(prefs.getString(Settings.PREF_VELA_LLM_MODEL_PATH, null) ?: Defaults.PREF_VELA_LLM_MODEL_PATH)
    }
    var whisperNote by remember { mutableStateOf<String?>(null) }
    var llmNote by remember { mutableStateOf<String?>(null) }

    val whisperFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            whisperStatus = "downloading"
            whisperProgress = 0f
            CoroutineScope(Dispatchers.Main).launch {
                val result = ModelDownloadHelper.downloadModelToFolder(context, prefs, "whisper", uri) {
                    whisperProgress = it
                }
                whisperPath = result.path ?: whisperPath
                whisperStatus = if (result.path != null) "completed" else "failed"
                whisperNote = when {
                    result.path == null -> "Download failed"
                    result.savedPublic -> "Saved to the picked folder (plus a private working copy)"
                    else -> "Could not write to that folder - model saved privately"
                }
            }
        }
    }

    val llmFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            llmStatus = "downloading"
            llmProgress = 0f
            CoroutineScope(Dispatchers.Main).launch {
                val result = ModelDownloadHelper.downloadModelToFolder(context, prefs, "llm", uri) {
                    llmProgress = it
                }
                llmPath = result.path ?: llmPath
                llmStatus = if (result.path != null) "completed" else "failed"
                llmNote = when {
                    result.path == null -> "Download failed"
                    result.savedPublic -> "Saved to the picked folder (plus a private working copy)"
                    else -> "Could not write to that folder - model saved privately"
                }
            }
        }
    }

    val whisperImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val path = ModelDownloadHelper.importModel(context, prefs, "whisper", uri)
                if (path != null) {
                    whisperPath = path
                    whisperStatus = "completed"
                    whisperNote = "Imported - points at the selected file"
                } else {
                    whisperStatus = "failed"
                    whisperNote = "Import failed"
                }
            }
        }
    }

    val llmImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val path = ModelDownloadHelper.importModel(context, prefs, "llm", uri)
                if (path != null) {
                    llmPath = path
                    llmStatus = "completed"
                    llmNote = "Imported - points at the selected file"
                } else {
                    llmStatus = "failed"
                    llmNote = "Import failed"
                }
            }
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Models",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                ) {
                    ModelFileCard(
                        title = "Whisper Tiny (English)",
                        filename = "ggml-tiny.en.bin",
                        path = whisperPath,
                        status = whisperStatus,
                        progress = whisperProgress,
                        note = whisperNote,
                        onImport = { whisperImportLauncher.launch(arrayOf("*/*")) },
                        onDownload = { whisperFolderLauncher.launch(null) },
                        onDelete = {
                            ModelDownloadHelper.deleteModel(context, prefs, "whisper")
                            whisperPath = Defaults.PREF_VELA_MODEL_PATH
                            whisperNote = null
                            whisperStatus = "pending"
                        }
                    )

                    Setting(context, Settings.PREF_VELA_MODEL_PATH, R.string.voice_model_path_title, R.string.voice_model_path_summary) {
                        TextInputPreference(setting = it, default = Defaults.PREF_VELA_MODEL_PATH)
                    }

                    Spacer(Modifier.height(8.dp))

                    ModelFileCard(
                        title = "Llama 3.2 1B Cleaner (ONNX)",
                        filename = "llama-cleaner.onnx",
                        path = llmPath,
                        status = llmStatus,
                        progress = llmProgress,
                        note = llmNote,
                        onImport = { llmImportLauncher.launch(arrayOf("*/*")) },
                        onDownload = { llmFolderLauncher.launch(null) },
                        onDelete = {
                            ModelDownloadHelper.deleteModel(context, prefs, "llm")
                            llmPath = Defaults.PREF_VELA_LLM_MODEL_PATH
                            llmNote = null
                            llmStatus = "pending"
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "LLM Cleaner",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    )

                    Setting(context, Settings.PREF_VELA_LLM_TOGGLE, R.string.voice_llm_toggle_title, R.string.voice_llm_toggle_summary) {
                        helium314.keyboard.settings.preferences.SwitchPreference(setting = it, default = Defaults.PREF_VELA_LLM_TOGGLE)
                    }

                    Setting(context, Settings.PREF_VELA_LLM_MODEL_PATH, R.string.voice_llm_model_path_title, R.string.voice_llm_model_path_summary) {
                        TextInputPreference(setting = it, default = Defaults.PREF_VELA_LLM_MODEL_PATH)
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Voice UI Layout Style",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    )

                    VoiceUiStyleSelector(prefs)

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    )
}

@Composable
private fun VoiceUiStyleSelector(prefs: android.content.SharedPreferences) {
    var currentStyle by remember { mutableStateOf(
        prefs.getString(Settings.PREF_VELA_UI_STYLE, Defaults.PREF_VELA_UI_STYLE) ?: Defaults.PREF_VELA_UI_STYLE
    ) }
    val styles = listOf("vela" to "Vela Pane", "yaps" to "Toolbar (Yaps)")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        styles.forEach { (value, label) ->
            FilterChip(
                selected = currentStyle == value,
                onClick = {
                    prefs.edit { putString(Settings.PREF_VELA_UI_STYLE, value) }
                    currentStyle = value
                },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModelFileCard(
    title: String,
    filename: String,
    path: String,
    status: String,
    progress: Float = 0f,
    note: String? = null,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when (status) {
                    "completed" -> "DOWNLOADED"
                    "downloading" -> "DOWNLOADING"
                    "failed" -> "FAILED"
                    else -> "PENDING"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    "completed" -> MaterialTheme.colorScheme.primary
                    "downloading" -> MaterialTheme.colorScheme.tertiary
                    "failed" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(path, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status == "downloading" && progress > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (status == "completed") {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) { Text("Delete") }
                } else if (status == "pending" || status == "failed") {
                    TextButton(onClick = onImport) { Text("Import") }
                    Button(onClick = onDownload) { Text("Download") }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
