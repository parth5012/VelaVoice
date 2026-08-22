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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.DictionaryCrossSync
import helium314.keyboard.settings.SearchSettingsScreen
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun VoiceDictionaryScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()

    var dictEntries by remember { mutableStateOf(loadDictionary(prefs)) }
    var originalWord by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    var keywordEntries by remember { mutableStateOf(loadKeywords(prefs)) }
    var keywordInput by remember { mutableStateOf("") }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Personal Dictionary",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                ) {
                    Text(
                        text = "Dictionary",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )

                    DictionaryForm(
                        originalWord = originalWord,
                        replacement = replacement,
                        onOriginalChange = { originalWord = it },
                        onReplacementChange = { replacement = it },
                        onAdd = {
                            if (originalWord.isNotBlank() && replacement.isNotBlank()) {
                                addDictionaryEntry(prefs, originalWord.trim(), replacement.trim())
                                originalWord = ""
                                replacement = ""
                                dictEntries = loadDictionary(prefs)
                            }
                        }
                    )

                    dictEntries.forEach { (orig, repl, idx) ->
                        DictionaryEntryRow(
                            original = orig,
                            replacement = repl,
                            onDelete = {
                                removeDictionaryEntry(prefs, idx)
                                dictEntries = loadDictionary(prefs)
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Dictionary Keywords",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    )

                    KeywordsForm(
                        keywordInput = keywordInput,
                        onKeywordChange = { keywordInput = it },
                        onAdd = {
                            if (keywordInput.isNotBlank()) {
                                val trimmed = keywordInput.trim()
                                addKeyword(prefs, trimmed)
                                DictionaryCrossSync.addToKeyboardDictionary(context, trimmed)
                                keywordInput = ""
                                keywordEntries = loadKeywords(prefs)
                            }
                        }
                    )

                    keywordEntries.forEach { (kw, idx) ->
                        KeywordChipRow(
                            keyword = kw,
                            onDelete = {
                                removeKeyword(prefs, idx)
                                DictionaryCrossSync.removeFromKeyboardDictionary(context, kw)
                                keywordEntries = loadKeywords(prefs)
                            }
                        )
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    )
}

@Composable
private fun DictionaryForm(
    originalWord: String,
    replacement: String,
    onOriginalChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = originalWord,
            onValueChange = onOriginalChange,
            label = { Text("Original word") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = replacement,
            onValueChange = onReplacementChange,
            label = { Text("Replacement word") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAdd,
            enabled = originalWord.isNotBlank() && replacement.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) { Text("Add Mapping") }
    }
}

@Composable
private fun DictionaryEntryRow(
    original: String,
    replacement: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$original \u2192 $replacement",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDelete) {
            Text("\u2715", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun KeywordsForm(
    keywordInput: String,
    onKeywordChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = keywordInput,
            onValueChange = onKeywordChange,
            label = { Text("Add a keyword") },
            placeholder = { Text("e.g. Kubernetes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAdd,
            enabled = keywordInput.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) { Text("Add Keyword") }
    }
}

@Composable
private fun KeywordChipRow(
    keyword: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = keyword,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDelete) {
            Text("\u2715", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun loadDictionary(prefs: android.content.SharedPreferences): List<Triple<String, String, Int>> {
    val json = prefs.getString(Settings.PREF_VELA_DICT_PREFIX + "entries", "[]") ?: "[]"
    val entries = mutableListOf<Triple<String, String, Int>>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            entries.add(Triple(obj.getString("original"), obj.getString("replacement"), i))
        }
    } catch (_: Exception) {}
    return entries
}

private fun addDictionaryEntry(prefs: android.content.SharedPreferences, original: String, replacement: String) {
    val json = prefs.getString(Settings.PREF_VELA_DICT_PREFIX + "entries", "[]") ?: "[]"
    try {
        val arr = JSONArray(json)
        val obj = JSONObject()
        obj.put("original", original)
        obj.put("replacement", replacement)
        arr.put(obj)
        prefs.edit { putString(Settings.PREF_VELA_DICT_PREFIX + "entries", arr.toString()) }
    } catch (_: Exception) {}
}

private fun removeDictionaryEntry(prefs: android.content.SharedPreferences, index: Int) {
    val json = prefs.getString(Settings.PREF_VELA_DICT_PREFIX + "entries", "[]") ?: "[]"
    try {
        val arr = JSONArray(json)
        if (index in 0 until arr.length()) {
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                if (i != index) newArr.put(arr.get(i))
            }
            prefs.edit { putString(Settings.PREF_VELA_DICT_PREFIX + "entries", newArr.toString()) }
        }
    } catch (_: Exception) {}
}

private fun loadKeywords(prefs: android.content.SharedPreferences): List<Pair<String, Int>> {
    val json = prefs.getString(Settings.PREF_VELA_DICT_PREFIX + "keywords", "[]") ?: "[]"
    val keywords = mutableListOf<Pair<String, Int>>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            keywords.add(Pair(arr.getString(i), i))
        }
    } catch (_: Exception) {}
    return keywords
}

private fun addKeyword(prefs: android.content.SharedPreferences, keyword: String) {
    val json = prefs.getString(Settings.PREF_VELA_DICT_PREFIX + "keywords", "[]") ?: "[]"
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            if (arr.getString(i).equals(keyword, ignoreCase = true)) return
        }
        arr.put(keyword)
        prefs.edit { putString(Settings.PREF_VELA_DICT_PREFIX + "keywords", arr.toString()) }
    } catch (_: Exception) {}
}

private fun removeKeyword(prefs: android.content.SharedPreferences, index: Int) {
    val json = prefs.getString(Settings.PREF_VELA_DICT_PREFIX + "keywords", "[]") ?: "[]"
    try {
        val arr = JSONArray(json)
        if (index in 0 until arr.length()) {
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                if (i != index) newArr.put(arr.get(i))
            }
            prefs.edit { putString(Settings.PREF_VELA_DICT_PREFIX + "keywords", newArr.toString()) }
        }
    } catch (_: Exception) {}
}
