import * as FileSystem from 'expo-file-system';
import * as SQLite from 'expo-sqlite';
import { NativeModules } from 'react-native';
const { ModelVerifier } = NativeModules;
const DEFAULT_MODELS = [
    {
        id: 'whisper-tiny-en',
        name: 'Whisper Tiny (English)',
        url: 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin',
        filename: 'ggml-tiny.en.bin',
        expectedHash: '921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f',
    },
    {
        id: 'cleaner-llama-3b',
        name: 'Llama 3.2 1B Cleaner ONNX',
        url: 'https://huggingface.co/onnx-community/Llama-3.2-1B-Instruct-ONNX/resolve/main/onnx/model.onnx',
        filename: 'llama-cleaner.onnx',
        expectedHash: '3002ec321434a9ac3e6e9b5e05b1e9e6eb751a2b560ecb898538f9cf7c1ae203',
    }
];
let dbPromise = null;
async function getDb() {
    if (!dbPromise) {
        dbPromise = SQLite.openDatabaseAsync('models.db').then(async (database) => {
            await database.execAsync(`
        CREATE TABLE IF NOT EXISTS models (
          id TEXT PRIMARY KEY,
          name TEXT,
          url TEXT,
          filename TEXT,
          expectedHash TEXT,
          path TEXT,
          status TEXT
        );
        CREATE TABLE IF NOT EXISTS personal_dictionary (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          original_word TEXT UNIQUE NOT NULL,
          replacement TEXT NOT NULL,
          language TEXT,
          priority INTEGER DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS dictionary_keywords (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          keyword TEXT UNIQUE NOT NULL,
          language TEXT,
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS corrections (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          audio_id TEXT NOT NULL,
          original_transcription TEXT NOT NULL,
          corrected_transcription TEXT NOT NULL,
          edits TEXT NOT NULL,
          edit_distance INTEGER NOT NULL,
          timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
          user_id TEXT,
          confidence_score REAL,
          raw_whisper_transcript TEXT,
          cleaned_llm_transcript TEXT,
          user_final_text TEXT,
          scribe_style TEXT,
          wer_score REAL,
          quarantined INTEGER DEFAULT 0
        );
      `);
      // Run migrations for existing databases missing tri-state columns
      try {
        await database.execAsync(`
          ALTER TABLE corrections ADD COLUMN raw_whisper_transcript TEXT;
          ALTER TABLE corrections ADD COLUMN cleaned_llm_transcript TEXT;
          ALTER TABLE corrections ADD COLUMN user_final_text TEXT;
          ALTER TABLE corrections ADD COLUMN scribe_style TEXT;
          ALTER TABLE corrections ADD COLUMN wer_score REAL;
          ALTER TABLE corrections ADD COLUMN quarantined INTEGER DEFAULT 0;
        `);
      } catch (e) {
        // Columns already exist or migration not needed
      }
      return database;
        });
    }
    return dbPromise;
}
export class ModelManager {
    static async getModels() {
        const db = await getDb();
        const rows = await db.getAllAsync('SELECT * FROM models');
        // Merge database state with DEFAULT_MODELS list
        return DEFAULT_MODELS.map((def) => {
            const row = rows.find((r) => r.id === def.id);
            if (row) {
                return {
                    ...def,
                    path: row.path,
                    status: row.status,
                    progress: row.status === 'completed' ? 1 : 0,
                };
            }
            return {
                ...def,
                path: null,
                status: 'pending',
                progress: 0,
            };
        });
    }
    static async downloadModel(id, onProgress) {
        const models = await this.getModels();
        const model = models.find((m) => m.id === id);
        if (!model) {
            throw new Error(`Model not found with id: ${id}`);
        }
        const db = await getDb();
        // Update state to downloading
        await db.runAsync('INSERT OR REPLACE INTO models (id, name, url, filename, expectedHash, path, status) VALUES (?, ?, ?, ?, ?, ?, ?)', [model.id, model.name, model.url, model.filename, model.expectedHash, null, 'downloading']);
        const localUri = FileSystem.documentDirectory + model.filename;
        // Create download resumable
        const downloadResumable = FileSystem.createDownloadResumable(model.url, localUri, {}, (downloadProgress) => {
            const progress = downloadProgress.totalBytesWritten /
                downloadProgress.totalBytesExpectedToWrite;
            onProgress(progress);
        });
        try {
            const result = await downloadResumable.downloadAsync();
            if (!result) {
                throw new Error('Download returned null result');
            }
            // Convert URI to absolute path (remove file:// prefix for Kotlin usage)
            let absolutePath = result.uri;
            if (absolutePath.startsWith('file://')) {
                absolutePath = absolutePath.substring(7);
            }
            // Verify SHA-256 using Native Module
            let isVerified = false;
            if (ModelVerifier && ModelVerifier.verifySHA256) {
                // Run native check
                isVerified = await ModelVerifier.verifySHA256(absolutePath, model.expectedHash);
            }
            else {
                console.warn('ModelVerifier native module not available. Skipping checksum check.');
                // Fallback to true if we are running in Expo Go or environment without native modules
                isVerified = true;
            }
            const finalStatus = isVerified ? 'completed' : 'checksum_failed';
            const finalPath = isVerified ? absolutePath : null;
            if (!isVerified) {
                // Delete invalid file
                try {
                    await FileSystem.deleteAsync(result.uri, { idempotent: true });
                }
                catch (e) {
                    console.error('Failed to clean up invalid model file', e);
                }
            }
            await db.runAsync('INSERT OR REPLACE INTO models (id, name, url, filename, expectedHash, path, status) VALUES (?, ?, ?, ?, ?, ?, ?)', [model.id, model.name, model.url, model.filename, model.expectedHash, finalPath, finalStatus]);
            return {
                ...model,
                path: finalPath,
                status: finalStatus,
                progress: isVerified ? 1 : 0,
            };
        }
        catch (error) {
            console.error(`Download failed for model ${id}`, error);
            await db.runAsync('INSERT OR REPLACE INTO models (id, name, url, filename, expectedHash, path, status) VALUES (?, ?, ?, ?, ?, ?, ?)', [model.id, model.name, model.url, model.filename, model.expectedHash, null, 'failed']);
            throw error;
        }
    }
    static async deleteModel(id) {
        const models = await this.getModels();
        const model = models.find((m) => m.id === id);
        if (model && model.path) {
            try {
                const fileUri = 'file://' + model.path;
                await FileSystem.deleteAsync(fileUri, { idempotent: true });
            }
            catch (e) {
                console.error('Failed to delete file', e);
            }
        }
        const db = await getDb();
        await db.runAsync('DELETE FROM models WHERE id = ?', [id]);
    }
    static async getDictionaryEntries() {
        const db = await getDb();
        return await db.getAllAsync('SELECT * FROM personal_dictionary ORDER BY priority DESC, original_word ASC');
    }
    static async addDictionaryEntry(originalWord, replacement, language, priority) {
        const db = await getDb();
        await db.runAsync('INSERT OR REPLACE INTO personal_dictionary (original_word, replacement, language, priority) VALUES (?, ?, ?, ?)', [originalWord, replacement, language ?? null, priority ?? 1]);
    }
    static async deleteDictionaryEntry(id) {
        const db = await getDb();
        await db.runAsync('DELETE FROM personal_dictionary WHERE id = ?', [id]);
    }
    // ──────────────────────────────────────────────
    // Dictionary Keywords
    // ──────────────────────────────────────────────
    static async getKeywords() {
        const db = await getDb();
        return await db.getAllAsync('SELECT * FROM dictionary_keywords ORDER BY keyword ASC');
    }
    static async addKeyword(keyword, language) {
        const db = await getDb();
        await db.runAsync('INSERT OR REPLACE INTO dictionary_keywords (keyword, language) VALUES (?, ?)', [keyword, language ?? null]);
    }
    static async deleteKeyword(id) {
        const db = await getDb();
        await db.runAsync('DELETE FROM dictionary_keywords WHERE id = ?', [id]);
    }
  static async saveCorrection(audioId, original, corrected, edits, editDistance, userId, confidenceScore, rawWhisper, cleanedLlm, userFinal, scribeStyle, werScore, quarantined) {
    // Second fail-closed gate (ticket #136): even bypassing CorrectionAPI, a
    // quarantined write throws before SQLite — never trainable. Counts only.
    if (quarantined === true) {
      throw new Error('quarantined session is local-only and never saved for training');
    }
    const db = await getDb();
    await db.runAsync(`INSERT INTO corrections (
      audio_id, original_transcription, corrected_transcription,
      edits, edit_distance, user_id, confidence_score,
      raw_whisper_transcript, cleaned_llm_transcript, user_final_text, scribe_style, wer_score,
      quarantined
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
      audioId,
      original,
      corrected,
      edits,
      editDistance,
      userId !== undefined && userId !== null ? userId : null,
      confidenceScore !== undefined && confidenceScore !== null ? confidenceScore : null,
      rawWhisper || original,
      cleanedLlm || corrected,
      userFinal || corrected,
      scribeStyle || 'default',
      werScore !== undefined && werScore !== null ? werScore : (editDistance / Math.max(1, original.length)),
      quarantined === true ? 1 : 0
    ]);
  }
    static async getCorrections() {
        const db = await getDb();
        return await db.getAllAsync('SELECT * FROM corrections ORDER BY timestamp DESC');
    }
    static async closeDb() {
        if (dbPromise) {
            const db = await dbPromise;
            if (db.closeAsync) {
                await db.closeAsync();
            }
            dbPromise = null;
        }
    }
}
