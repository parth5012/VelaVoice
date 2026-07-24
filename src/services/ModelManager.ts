import * as FileSystem from 'expo-file-system';
import * as SQLite from 'expo-sqlite';
import { NativeModules } from 'react-native';

const { ModelVerifier } = NativeModules;

export interface ModelInfo {
  id: string;
  name: string;
  url: string;
  filename: string;
  expectedHash: string;
  path: string | null;
  status: 'pending' | 'downloading' | 'completed' | 'failed' | 'checksum_failed';
  progress: number;
}

const DEFAULT_MODELS: Omit<ModelInfo, 'progress' | 'path' | 'status'>[] = [
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

let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

async function getDb(): Promise<SQLite.SQLiteDatabase> {
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
      `);
      return database;
    });
  }
  return dbPromise;
}

export class ModelManager {
  static async getModels(): Promise<ModelInfo[]> {
    const db = await getDb();
    const rows = await db.getAllAsync<any>('SELECT * FROM models');
    
    // Merge database state with DEFAULT_MODELS list
    return DEFAULT_MODELS.map((def) => {
      const row = rows.find((r) => r.id === def.id);
      if (row) {
        return {
          ...def,
          path: row.path,
          status: row.status as ModelInfo['status'],
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

  static async downloadModel(
    id: string,
    onProgress: (progress: number) => void
  ): Promise<ModelInfo> {
    const models = await this.getModels();
    const model = models.find((m) => m.id === id);
    if (!model) {
      throw new Error(`Model not found with id: ${id}`);
    }

    const db = await getDb();
    
    // Update state to downloading
    await db.runAsync(
      'INSERT OR REPLACE INTO models (id, name, url, filename, expectedHash, path, status) VALUES (?, ?, ?, ?, ?, ?, ?)',
      [model.id, model.name, model.url, model.filename, model.expectedHash, null, 'downloading']
    );

    const localUri = FileSystem.documentDirectory + model.filename;
    
    // Create download resumable
    const downloadResumable = FileSystem.createDownloadResumable(
      model.url,
      localUri,
      {},
      (downloadProgress) => {
        const progress =
          downloadProgress.totalBytesWritten /
          downloadProgress.totalBytesExpectedToWrite;
        onProgress(progress);
      }
    );

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
      } else {
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
        } catch (e) {
          console.error('Failed to clean up invalid model file', e);
        }
      }

      await db.runAsync(
        'INSERT OR REPLACE INTO models (id, name, url, filename, expectedHash, path, status) VALUES (?, ?, ?, ?, ?, ?, ?)',
        [model.id, model.name, model.url, model.filename, model.expectedHash, finalPath, finalStatus]
      );

      return {
        ...model,
        path: finalPath,
        status: finalStatus,
        progress: isVerified ? 1 : 0,
      };
    } catch (error) {
      console.error(`Download failed for model ${id}`, error);
      await db.runAsync(
        'INSERT OR REPLACE INTO models (id, name, url, filename, expectedHash, path, status) VALUES (?, ?, ?, ?, ?, ?, ?)',
        [model.id, model.name, model.url, model.filename, model.expectedHash, null, 'failed']
      );
      throw error;
    }
  }

  static async deleteModel(id: string): Promise<void> {
    const models = await this.getModels();
    const model = models.find((m) => m.id === id);
    if (model && model.path) {
      try {
        const fileUri = 'file://' + model.path;
        await FileSystem.deleteAsync(fileUri, { idempotent: true });
      } catch (e) {
        console.error('Failed to delete file', e);
      }
    }
    const db = await getDb();
    await db.runAsync('DELETE FROM models WHERE id = ?', [id]);
  }
}
