// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.GoogleDriveSync
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.TranscriptionStorage
import helium314.keyboard.settings.screens.TranscriptionLogItem
import helium314.keyboard.settings.screens.loadRecentTranscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun VoiceSyncScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val context = LocalContext.current

    val isDriveConfigured = remember { GoogleDriveSync.isConfigured(prefs) }
    var driveSyncing by remember { mutableStateOf(false) }
    var driveResult by remember { mutableStateOf<String?>(null) }
    var unsyncedCount by remember {
        mutableStateOf(TranscriptionStorage.getUnsyncedCount(context))
    }
    var totalTranscriptions by remember {
        mutableStateOf(TranscriptionStorage.getTranscriptionCount(context))
    }
    var recentTranscriptions by remember {
        mutableStateOf(loadRecentTranscriptions(context, 10))
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Sync & History",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                ) {
                    Text(
                        text = "Google Drive Sync",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "Transcription Backup",
                                style = MaterialTheme.typography.bodyLarge
                            )

                            Text(
                                text = "Each transcription (raw + cleaned) is saved locally. " +
                                        "Press Sync to upload pending pairs to Google Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Drive Connection:",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = if (isDriveConfigured) "CONFIGURED" else "NOT SET UP",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDriveConfigured) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Local Transcriptions:",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "$totalTranscriptions total",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            if (isDriveConfigured) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Pending Sync:",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = if (unsyncedCount > 0) "$unsyncedCount pending"
                                        else "All synced",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (unsyncedCount > 0) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (driveResult != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = driveResult!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (driveResult!!.contains("\u274c") || driveResult!!.contains("\u26a0") || driveResult!!.contains("failed", ignoreCase = true))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            if (isDriveConfigured) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            driveSyncing = true
                                            driveResult = null
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val result = GoogleDriveSync.syncToDrive(context, prefs)
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    driveSyncing = false
                                                    driveResult = buildString {
                                                        if (result.uploaded > 0) {
                                                            append("\u2705 Synced ${result.uploaded} transcription(s) to Google Drive.")
                                                        } else {
                                                            append(result.message)
                                                        }
                                                        unsyncedCount = TranscriptionStorage.getUnsyncedCount(context)
                                                        totalTranscriptions = TranscriptionStorage.getTranscriptionCount(context)
                                                        recentTranscriptions = loadRecentTranscriptions(context, 10)
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !driveSyncing,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (unsyncedCount > 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            if (driveSyncing) "\u23f3 Syncing..."
                                            else "\u2601\ufe0f Sync with Drive ($unsyncedCount)"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recentTranscriptions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = "Recent Transcriptions",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "The latest transcription logs saved on your device and their Google Drive sync status.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                recentTranscriptions.forEach { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(Modifier.padding(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (item.createdAt.isNotEmpty()) item.createdAt else item.fileName.replace(".json", "").replace("_", " "),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Card(
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (item.isSynced)
                                                            Color(0xFF00504B)
                                                        else
                                                            Color(0xFF3C1800)
                                                    )
                                                ) {
                                                    Text(
                                                        text = if (item.isSynced) "Synced" else "Local Only",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "Raw Text:",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = item.raw,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (item.cleaned.isNotEmpty() && item.cleaned != item.raw) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = "Cleaned Text:",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = item.cleaned,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "Duration: ${Math.round(item.durationMs / 1000f)}s | File: ${item.fileName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    )
}
