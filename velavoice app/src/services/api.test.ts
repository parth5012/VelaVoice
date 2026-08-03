import Module from 'module';

// Intercept imports of native packages before ModelManager is loaded
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

// Apply mocks
mockModule('react-native', {
  NativeModules: {
    ModelVerifier: {}
  }
});
mockModule('expo-file-system', {
  documentDirectory: 'file://mock-dir/'
});
mockModule('expo-sqlite', {
  openDatabaseAsync: async () => ({})
});

// Now import ModelManager and API safely
import { CorrectionAPI, SaveCorrectionPayload } from './api';
import { ModelManager } from './ModelManager';

// Mock ModelManager.saveCorrection
const originalSaveCorrection = ModelManager.saveCorrection;
let savedArgs: any[] = [];
ModelManager.saveCorrection = async (...args: any[]) => {
  savedArgs = args;
  return Promise.resolve();
};

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

async function runTests() {
  console.log('Running API tests...');

  // Test successful saveCorrection
  const payload: SaveCorrectionPayload = {
    audio_id: 'audio_123',
    original_transcription: 'hello cat',
    corrected_transcription: 'hello dog',
    edits: [{ type: 'substitution', original: 'cat', corrected: 'dog', position: 1 }],
    edit_distance: 1,
    user_id: 'user_456',
    confidence_score: 0.95
  };

  const res = await CorrectionAPI.saveCorrection(payload);
  assert(res.success === true, 'Successful save correction');
  assert(savedArgs[0] === 'audio_123', 'audio_id matches');
  assert(savedArgs[1] === 'hello cat', 'original matches');
  assert(savedArgs[2] === 'hello dog', 'corrected matches');
  assert(JSON.parse(savedArgs[3]).length === 1, 'edits JSON string array matches');
  assert(savedArgs[4] === 1, 'edit distance matches');
  assert(savedArgs[5] === 'user_456', 'user_id matches');
  assert(savedArgs[6] === 0.95, 'confidence_score matches');

  // Test validations
  const resNullId = await CorrectionAPI.saveCorrection({ ...payload, audio_id: '' });
  assert(resNullId.success === false, 'audio_id validated');

  const resNullOriginal = await CorrectionAPI.saveCorrection({ ...payload, original_transcription: undefined as any });
  assert(resNullOriginal.success === false, 'original validated');

  // Test Mock Fetch
  const fetchRes = await CorrectionAPI.mockFetch('https://api.velavoice.com/save_correction', {
    method: 'POST',
    body: JSON.stringify(payload)
  });

  assert(fetchRes.ok === true, 'Fetch returns OK');
  assert(fetchRes.status === 200, 'Fetch status is 200');
  const fetchJson = await fetchRes.json();
  assert(fetchJson.success === true, 'Fetch json success is true');

  console.log('✅ All API tests passed!');
  
  // Cleanup
  ModelManager.saveCorrection = originalSaveCorrection;
}

runTests().catch(e => {
  console.error('❌ API tests failed:', e.message);
  process.exit(1);
});
