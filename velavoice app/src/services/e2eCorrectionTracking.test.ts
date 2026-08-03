import Module from 'module';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

// Mocks for React Native and Expo runtime
const mockModule = (name: string, exports: any) => {
  const ModuleClass = Module as any;
  const origResolve = ModuleClass._resolveFilename;
  ModuleClass._resolveFilename = function (request: string, parent: any, isMain: boolean) {
    if (request === name) return name;
    return origResolve.apply(this, arguments);
  };
  require.cache[name] = {
    id: name,
    filename: name,
    loaded: true,
    exports: exports,
    parent: null,
    children: []
  } as any;
};

// Setup SQLite Mock mock connection for Node using installed sqlite3
const sqlite3 = require('sqlite3');
const testDbPath = path.resolve('e2e_models.db');
if (fs.existsSync(testDbPath)) {
  fs.unlinkSync(testDbPath);
}

mockModule('expo-sqlite', {
  openDatabaseAsync: async (dbName: string) => {
    // Open a real disk SQLite database to test python export pipeline integration
    const db = new sqlite3.Database(testDbPath);
    return {
      execAsync: async (sqlQuery: string) => {
        return new Promise<void>((resolve, reject) => {
          db.exec(sqlQuery, (err: Error | null) => (err ? reject(err) : resolve()));
        });
      },
      runAsync: async (sqlQuery: string, params: any[]) => {
        return new Promise<void>((resolve, reject) => {
          db.run(sqlQuery, params, (err: Error | null) => (err ? reject(err) : resolve()));
        });
      },
      getAllAsync: async (sqlQuery: string, params: any[] = []) => {
        return new Promise<any[]>((resolve, reject) => {
          db.all(sqlQuery, params, (err: Error | null, rows: any[]) => (err ? reject(err) : resolve(rows)));
        });
      },
      closeAsync: async () => {
        return new Promise<void>((resolve, reject) => {
          db.close((err: Error | null) => (err ? reject(err) : resolve()));
        });
      }
    };
  }
});

mockModule('react-native', {
  NativeModules: {
    ModelVerifier: {}
  }
});
mockModule('expo-file-system', {
  documentDirectory: 'file://mock/'
});

// Import modules under test
import { CorrectionAPI } from './api';
import { ModelManager } from './ModelManager';
import { getEdits, calculateEditDistance } from '../utils/editCalculator';

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('E2E Assertion Failed: ' + message);
  }
}

async function runE2E() {
  console.log('Starting E2E verification of Correction Tracking feature...');

  // 1. Ingest Database Schema
  // Calling getModels will trigger getDb() and initialize the tables
  await ModelManager.getModels();

  // Open the SQLite database to check if corrections table exists
  const sqliteDb = new sqlite3.Database(testDbPath);
  const tables = await new Promise<any[]>((resolve, reject) => {
    sqliteDb.all("SELECT name FROM sqlite_master WHERE type='table' AND name='corrections'", (err: Error | null, rows: any[]) => {
      if (err) reject(err);
      else resolve(rows);
    });
  });
  sqliteDb.close();

  assert(tables.length === 1, 'corrections table successfully created in SQLite');
  console.log('1. Database Schema verification: [PASS]');

  // 2. Simulate User Interaction (UI Component saves correction)
  const audioId = 'audio_e2e_999';
  const originalTranscript = 'the quick brown fox jump over the lazy dog';
  const correctedTranscript = 'the quick brown fox jumps over the lazy dog';

  const edits = getEdits(originalTranscript, correctedTranscript);
  const editDistance = calculateEditDistance(originalTranscript, correctedTranscript);
  
  assert(editDistance === 1, 'calculated correct Levenshtein distance');
  assert(edits.length === 1 && edits[0].type === 'substitution', 'extracted substitution edit');
  console.log('2. Edit Utility calculations verification: [PASS]');

  // 3. Simulating Frontend calling save api POST endpoint
  const payload = {
    audio_id: audioId,
    original_transcription: originalTranscript,
    corrected_transcription: correctedTranscript,
    edits,
    edit_distance: editDistance,
    user_id: 'e2e_tester_user',
    confidence_score: 0.88
  };

  // Mock Fetch integration check
  const fetchResponse = await CorrectionAPI.mockFetch('https://api.velavoice.com/save_correction', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
  
  assert(fetchResponse.ok === true, 'API call returned OK response status');
  const responseJson = await fetchResponse.json();
  assert(responseJson.success === true, 'API payload successfully validated and saved');
  console.log('3. Frontend API REST Mock routing verification: [PASS]');

  // 4. Verify record present in SQLite database
  const dbRecords = await ModelManager.getCorrections();
  assert(dbRecords.length === 1, 'Saved record inserted into SQLite database');
  assert(dbRecords[0].audio_id === audioId, 'audio_id matches database record');
  assert(dbRecords[0].edit_distance === 1, 'edit_distance matches database record');
  console.log('4. SQLite Database Storage persistence verification: [PASS]');

  // 5. Test Python Export script pipeline
  const testOutJsonl = 'e2e_fine_tune_dataset.jsonl';
  if (fs.existsSync(testOutJsonl)) {
    fs.unlinkSync(testOutJsonl);
  }

  // Execute export python script
  execSync(`python scripts/export_corrections.py --db "${testDbPath}" --out "${testOutJsonl}"`);
  
  assert(fs.existsSync(testOutJsonl), 'export_corrections.py output JSONL file created');
  const fileContent = fs.readFileSync(testOutJsonl, 'utf-8').trim();
  const exportedItem = JSON.parse(fileContent);
  assert(exportedItem.audio_id === audioId, 'Exported JSONL audio_id matches original value');
  assert(exportedItem.original === originalTranscript, 'Exported JSONL original transcript matches');
  assert(exportedItem.corrected === correctedTranscript, 'Exported JSONL corrected transcript matches');
  console.log('5. fine-tuning dataset export pipeline verification: [PASS]');

  // Close database first to unlock file
  await ModelManager.closeDb();

  // Cleanup E2E artifacts
  if (fs.existsSync(testDbPath)) {
    fs.unlinkSync(testDbPath);
  }
  if (fs.existsSync(testOutJsonl)) {
    fs.unlinkSync(testOutJsonl);
  }

  console.log('SUCCESS: Correction Tracking E2E verification workflow completed perfectly!');
}

runE2E().catch(e => {
  console.error('❌ E2E failed:', e);
  process.exit(1);
});
