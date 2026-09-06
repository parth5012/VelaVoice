/**
 * End-to-End (E2E) Test Verification Suite for Integrated Gemini 2.0 Flash Flow (US-VELA-042)
 *
 * Verification Scope:
 * 1. Google AI Studio free tier API key configuration & SecureStore lifecycle (save, retrieve, fallback, delete)
 * 2. Pre-flight key validation connection test (HTTP 200 success and 400/403/429/timeout failure paths)
 * 3. Raw audio PCM (16kHz 16-bit mono) formatting with 44-byte WAV RIFF header compliance
 * 4. Gemini REST payload serialization with verbatim system instruction
 * 5. Integrated end-to-end transcription flow execution & response text cleanup (markdown fences, whitespace)
 * 6. Edge case handling & error recovery: HTTP 429 rate limit / quota exceeded, HTTP 403 invalid key, empty audio, timeout
 * 7. Complete multi-step user journey: settings -> test -> record -> transcribe -> rate-limit recovery
 */

import Module from 'module';

// --- Intercept imports of native/platform packages before GeminiService is loaded ---
const ModuleClass = Module as any;
const origResolve = ModuleClass._resolveFilename;

ModuleClass._resolveFilename = function (request: string, parent: any, isMain: boolean) {
  if (request === 'react-native') return 'react-native';
  if (request === 'expo-secure-store') return 'expo-secure-store';
  return origResolve.apply(this, arguments);
};

// In-memory mock storage for SecureStore mock
const mockStorage = new Map<string, string>();
let mockSecureStoreAvailable = true;

const mockSecureStore = {
  isAvailableAsync: async () => mockSecureStoreAvailable,
  setItemAsync: async (key: string, value: string) => {
    mockStorage.set(key, value);
  },
  getItemAsync: async (key: string) => {
    return mockStorage.has(key) ? mockStorage.get(key)! : null;
  },
  deleteItemAsync: async (key: string) => {
    mockStorage.delete(key);
  },
};

require.cache['react-native'] = {
  id: 'react-native',
  filename: 'react-native',
  loaded: true,
  exports: {
    Platform: { OS: 'android' },
    NativeModules: {
      ModelVerifier: {},
    },
  },
  parent: null,
  children: [],
} as any;

require.cache['expo-secure-store'] = {
  id: 'expo-secure-store',
  filename: 'expo-secure-store',
  loaded: true,
  exports: mockSecureStore,
  parent: null,
  children: [],
} as any;

// Load GeminiService with mocked native dependencies
const {
  testGeminiApiKey,
  saveGeminiApiKey,
  getGeminiApiKey,
  deleteGeminiApiKey,
  pcmToWav,
  encodeBase64,
  buildGeminiTranscriptionPayload,
  cleanTranscript,
  parseGeminiTranscriptionResponse,
  transcribeAudio,
  GEMINI_MODEL,
  GEMINI_API_STORAGE_KEY,
  GOOGLE_AI_STUDIO_URL,
  VERBATIM_SYSTEM_INSTRUCTION,
  DEFAULT_SAMPLE_RATE,
  DEFAULT_CHANNELS,
  DEFAULT_BITS_PER_SAMPLE,
} = require('./GeminiService');

// --- Test Framework Helpers ---
interface TestResult {
  id: number;
  category: string;
  name: string;
  status: 'PASS' | 'FAIL';
  durationMs: number;
  error?: string;
}

const testResults: TestResult[] = [];
let testCounter = 0;

function assert(condition: boolean, message: string) {
  if (!condition) {
    throw new Error(`Assertion failed: ${message}`);
  }
}

async function runTest(category: string, name: string, fn: () => Promise<void> | void) {
  testCounter++;
  const id = testCounter;
  const start = Date.now();
  try {
    await fn();
    const durationMs = Date.now() - start;
    testResults.push({ id, category, name, status: 'PASS', durationMs });
    console.log(`  [PASS] #${id} (${durationMs}ms): ${name}`);
  } catch (err: any) {
    const durationMs = Date.now() - start;
    const errorMsg = err?.message || String(err);
    testResults.push({ id, category, name, status: 'FAIL', durationMs, error: errorMsg });
    console.error(`  [FAIL] #${id} (${durationMs}ms): ${name}`);
    console.error(`         Error: ${errorMsg}`);
  }
}

// Generates synthetic 16kHz 16-bit mono PCM audio
function generateSyntheticPcm(durationMs: number, sampleRate: number = 16000, frequencyHz: number = 440): Uint8Array {
  const numSamples = Math.floor((sampleRate * durationMs) / 1000);
  const buffer = new Uint8Array(numSamples * 2);
  const view = new DataView(buffer.buffer);

  for (let i = 0; i < numSamples; i++) {
    const t = i / sampleRate;
    const sample = Math.sin(2 * Math.PI * frequencyHz * t) * 16000;
    const intSample = Math.max(-32768, Math.min(32767, Math.floor(sample)));
    view.setInt16(i * 2, intSample, true);
  }

  return buffer;
}

function mockGeminiSuccessResponse(text: string) {
  return {
    candidates: [
      {
        content: {
          parts: [{ text }],
          role: 'model',
        },
        finishReason: 'STOP',
      },
    ],
  };
}

// --- Main Test Suite ---
async function runAllE2ETests() {
  console.log('======================================================================');
  console.log('  VELAVOICE E2E VERIFICATION: GEMINI 2.0 FLASH TRANSCRIPTION FLOW');
  console.log('  Specification: US-VELA-042 (Integrated Full-Stack Verification)');
  console.log('======================================================================\n');

  const originalFetch = global.fetch;

  try {
    // =========================================================================
    // CATEGORY 1: Google AI Studio Key Settings & Storage Lifecycle
    // =========================================================================
    console.log('--- CATEGORY 1: Key Settings & Secure Storage Lifecycle ---');

    await runTest('Storage', 'Save Google AI Studio API key securely to storage', async () => {
      mockStorage.clear();
      mockSecureStoreAvailable = true;
      const testKey = 'AIzaSyTestGoogleAIStudioKey_Live001';
      await saveGeminiApiKey(testKey);

      assert(mockStorage.get(GEMINI_API_STORAGE_KEY) === testKey, 'Key must be saved under GEMINI_API_STORAGE_KEY in storage');
    });

    await runTest('Storage', 'Retrieve stored Google AI Studio API key accurately', async () => {
      const retrieved = await getGeminiApiKey();
      assert(retrieved === 'AIzaSyTestGoogleAIStudioKey_Live001', 'Retrieved key must match saved key');
    });

    await runTest('Storage', 'Trim whitespace from key during storage configuration', async () => {
      await saveGeminiApiKey('   AIzaSyUntrimmedKey123   ');
      const retrieved = await getGeminiApiKey();
      assert(retrieved === 'AIzaSyUntrimmedKey123', 'Key must be trimmed of surrounding whitespace');
    });

    await runTest('Storage', 'Fallback storage retains key when SecureStore is unavailable', async () => {
      mockSecureStoreAvailable = false;
      await saveGeminiApiKey('AIzaSyFallbackStorageKey_456');
      const fallbackRetrieved = await getGeminiApiKey();
      assert(fallbackRetrieved === 'AIzaSyFallbackStorageKey_456', 'Fallback storage must persist key when SecureStore fails');
      mockSecureStoreAvailable = true;
    });

    await runTest('Storage', 'Delete/revoke API key completely from storage', async () => {
      await saveGeminiApiKey('AIzaSyKeyToDelete');
      await deleteGeminiApiKey();
      const afterDelete = await getGeminiApiKey();
      assert(afterDelete === null || afterDelete === '', 'Storage must return null/empty after deletion');
    });

    await runTest('Constants', 'Verify model and Google AI Studio constants', async () => {
      assert(GEMINI_MODEL === 'gemini-3.5-transcribe', 'Model constant must be gemini-3.5-transcribe');
      assert(GOOGLE_AI_STUDIO_URL === 'https://aistudio.google.com', 'AI Studio URL must point to aistudio.google.com');
      assert(GEMINI_API_STORAGE_KEY === 'vela_gemini_api_key', 'Storage key must match vela_gemini_api_key');
      assert(DEFAULT_SAMPLE_RATE === 16000, 'Audio sample rate must be 16000 Hz');
      assert(DEFAULT_CHANNELS === 1, 'Audio channels must be 1 (mono)');
      assert(DEFAULT_BITS_PER_SAMPLE === 16, 'Audio depth must be 16-bit');
    });

    // =========================================================================
    // CATEGORY 2: Key Connection Pre-Flight Validation Gate
    // =========================================================================
    console.log('\n--- CATEGORY 2: Key Pre-Flight Validation Gate ---');

    await runTest('PreFlight', 'Valid API key test succeeds on HTTP 200 OK with lightweight query', async () => {
      let requestedUrl = '';
      let requestBody: any = null;

      global.fetch = async (url: any, opts: any) => {
        requestedUrl = String(url);
        requestBody = JSON.parse(opts.body);
        return {
          ok: true,
          status: 200,
          json: async () => mockGeminiSuccessResponse('pong'),
        } as any;
      };

      const result = await testGeminiApiKey('AIzaSyValidApiKey_PreflightTest');
      assert(result.success === true, 'Valid API key must return success: true');
      assert(result.error === undefined, 'No error must be present on valid key');
      assert(requestedUrl.includes('models/gemini-3.5-transcribe:generateContent'), 'Must call gemini-3.5-transcribe:generateContent');
      assert(requestedUrl.includes('key=AIzaSyValidApiKey_PreflightTest'), 'Must pass API key query parameter');
      assert(requestBody.generationConfig.maxOutputTokens === 1, 'Pre-flight query must restrict maxOutputTokens to 1 for efficiency');
      assert(requestBody.contents[0].parts[0].text === 'ping', 'Pre-flight probe query must send lightweight ping');
    });

    await runTest('PreFlight', 'Invalid key connection test handles HTTP 403 Forbidden gracefully', async () => {
      global.fetch = async () => ({
        ok: false,
        status: 403,
        json: async () => ({
          error: { code: 403, message: 'API key not valid. Please pass a valid API key.', status: 'PERMISSION_DENIED' },
        }),
      } as any);

      const result = await testGeminiApiKey('AIzaSyInvalidKey_403');
      assert(result.success === false, 'HTTP 403 must fail validation');
      assert(result.error !== undefined, 'Error message must be present');
      assert(result.error!.toLowerCase().includes('invalid api key'), 'Error message must inform user of invalid key');
    });

    await runTest('PreFlight', 'Invalid key connection test handles HTTP 400 Bad Request gracefully', async () => {
      global.fetch = async () => ({
        ok: false,
        status: 400,
        json: async () => ({
          error: { code: 400, message: 'API key not valid.', status: 'INVALID_ARGUMENT' },
        }),
      } as any);

      const result = await testGeminiApiKey('AIzaSyInvalidKey_400');
      assert(result.success === false, 'HTTP 400 must fail validation');
      assert(result.error!.toLowerCase().includes('invalid api key'), 'Error message must inform user of invalid key');
    });

    await runTest('PreFlight', 'Rate limit / quota exceeded test handles HTTP 429 gracefully with guidance', async () => {
      global.fetch = async () => ({
        ok: false,
        status: 429,
        json: async () => ({
          error: { code: 429, message: 'Resource has been exhausted (check quota).', status: 'RESOURCE_EXHAUSTED' },
        }),
      } as any);

      const result = await testGeminiApiKey('AIzaSyRateLimitedKey_429');
      assert(result.success === false, 'HTTP 429 must fail validation');
      assert(result.error!.toLowerCase().includes('quota') || result.error!.toLowerCase().includes('rate limit'), 'Must mention quota or rate limit');
      assert(result.error!.toLowerCase().includes('free tier'), 'Must guide user about free tier quota');
    });

    await runTest('PreFlight', 'Connection timeout handles AbortError cleanly', async () => {
      global.fetch = async () => {
        const err = new Error('The operation was aborted');
        err.name = 'AbortError';
        throw err;
      };

      const result = await testGeminiApiKey('AIzaSyTimeoutKey_001');
      assert(result.success === false, 'Aborted request must fail validation');
      assert(result.error!.toLowerCase().includes('timed out'), 'Error must indicate connection timeout');
    });

    await runTest('PreFlight', 'Blank / empty API key is rejected immediately without network call', async () => {
      let networkCalled: boolean = false;
      global.fetch = async () => {
        networkCalled = true;
        return { ok: true, status: 200 } as any;
      };

      const emptyResult = await testGeminiApiKey('');
      const whitespaceResult = await testGeminiApiKey('    ');

      assert(emptyResult.success === false, 'Empty key must fail');
      assert(whitespaceResult.success === false, 'Whitespace key must fail');
      assert(networkCalled === false, 'Network request must not be issued for empty/blank key');
    });

    // =========================================================================
    // CATEGORY 3: Audio PCM (16kHz Mono) Formatting & WAV Header Construction
    // =========================================================================
    console.log('\n--- CATEGORY 3: Audio PCM (16kHz Mono) Formatting & WAV RIFF Header ---');

    await runTest('AudioWav', 'Constructs 44-byte WAV header conforming to RIFF 16kHz mono 16-bit PCM', () => {
      const dummyPcm = generateSyntheticPcm(20, 16000);
      const wav = pcmToWav(dummyPcm, 16000, 1, 16);

      assert(wav.length === 44 + dummyPcm.length, `WAV size (${wav.length}) must equal 44 + PCM size (${dummyPcm.length})`);

      // 1. RIFF descriptor
      assert(String.fromCharCode(wav[0], wav[1], wav[2], wav[3]) === 'RIFF', "Chunk ID must be 'RIFF'");

      const view = new DataView(wav.buffer, wav.byteOffset, wav.byteLength);
      const reportedFileSize = view.getUint32(4, true);
      assert(reportedFileSize === 36 + dummyPcm.length, `RIFF chunk size (${reportedFileSize}) must be 36 + dataSize (${36 + dummyPcm.length})`);

      // 2. WAVE identifier
      assert(String.fromCharCode(wav[8], wav[9], wav[10], wav[11]) === 'WAVE', "Format must be 'WAVE'");

      // 3. 'fmt ' subchunk
      assert(String.fromCharCode(wav[12], wav[13], wav[14], wav[15]) === 'fmt ', "Subchunk1ID must be 'fmt '");
      const subchunk1Size = view.getUint32(16, true);
      assert(subchunk1Size === 16, 'Subchunk1Size must be 16 for PCM');

      const audioFormat = view.getUint16(20, true);
      assert(audioFormat === 1, 'AudioFormat must be 1 (uncompressed PCM)');

      const numChannels = view.getUint16(22, true);
      assert(numChannels === 1, 'NumChannels must be 1 (mono)');

      const sampleRate = view.getUint32(24, true);
      assert(sampleRate === 16000, 'SampleRate must be 16000 Hz');

      const byteRate = view.getUint32(28, true);
      assert(byteRate === 32000, 'ByteRate must be 32000 (16000 * 1 * 2)');

      const blockAlign = view.getUint16(32, true);
      assert(blockAlign === 2, 'BlockAlign must be 2 (channels * bitsPerSample / 8)');

      const bitsPerSample = view.getUint16(34, true);
      assert(bitsPerSample === 16, 'BitsPerSample must be 16');

      // 4. 'data' subchunk
      assert(String.fromCharCode(wav[36], wav[37], wav[38], wav[39]) === 'data', "Subchunk2ID must be 'data'");
      const reportedDataSize = view.getUint32(40, true);
      assert(reportedDataSize === dummyPcm.length, `Subchunk2Size (${reportedDataSize}) must match input PCM length (${dummyPcm.length})`);

      // 5. Raw audio sample preservation
      let samplesMatch = true;
      for (let i = 0; i < dummyPcm.length; i++) {
        if (wav[44 + i] !== dummyPcm[i]) {
          samplesMatch = false;
          break;
        }
      }
      assert(samplesMatch, 'Raw PCM audio samples must be preserved byte-for-byte after 44-byte header');
    });

    await runTest('AudioWav', 'Base64 encoding produces valid RFC 4648 output that decodes identically', () => {
      const pcmData = generateSyntheticPcm(50, 16000);
      const wav = pcmToWav(pcmData);
      const b64 = encodeBase64(wav);

      assert(typeof b64 === 'string' && b64.length > 0, 'Base64 string must not be empty');
      assert(!b64.includes('\n') && !b64.includes('\r'), 'Base64 string must not contain line breaks');

      const decodedBuffer = Buffer.from(b64, 'base64');
      assert(decodedBuffer.length === wav.length, 'Decoded buffer length must equal original WAV length');

      let match = true;
      for (let i = 0; i < wav.length; i++) {
        if (decodedBuffer[i] !== wav[i]) {
          match = false;
          break;
        }
      }
      assert(match, 'Base64 roundtrip must match original WAV bytes identically');
    });

    // =========================================================================
    // CATEGORY 4: Gemini REST Payload Serialization & Verbatim Instruction
    // =========================================================================
    console.log('\n--- CATEGORY 4: Gemini REST Payload Serialization & System Instruction ---');

    await runTest('Payload', 'Serializes verbatim system instruction conforming strictly to STT contract', () => {
      const dummyB64 = 'UklGRi4AAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=';
      const payload = buildGeminiTranscriptionPayload(dummyB64);

      const expectedInstruction =
        'You are a professional verbatim speech-to-text transcriber. Output ONLY the exact spoken transcription of the audio. Do not add notes, explanations, commentary, or conversational replies.';

      assert(payload.system_instruction !== undefined, 'system_instruction must be present in payload');
      assert(Array.isArray(payload.system_instruction.parts), 'system_instruction.parts must be an array');
      assert(
        payload.system_instruction.parts[0].text === expectedInstruction,
        'Verbatim system instruction text must match expected specification exactly'
      );
      assert(
        payload.system_instruction.parts[0].text === VERBATIM_SYSTEM_INSTRUCTION,
        'Instruction must match VERBATIM_SYSTEM_INSTRUCTION constant'
      );
    });

    await runTest('Payload', 'Formats user message with inline_data audio/wav and transcription prompt', () => {
      const dummyB64 = 'MOCK_BASE64_AUDIO_WAV_STRING_123456';
      const payload = buildGeminiTranscriptionPayload(dummyB64);

      assert(Array.isArray(payload.contents), 'contents must be an array');
      assert(payload.contents.length === 1, 'contents must have exactly 1 turn');
      assert(payload.contents[0].role === 'user', "contents[0].role must be 'user'");

      const parts = payload.contents[0].parts;
      assert(Array.isArray(parts) && parts.length === 2, 'user turn must have 2 parts (inline_data and prompt)');

      // Audio part
      const audioPart = parts[0];
      assert(audioPart.inline_data !== undefined, 'First part must contain inline_data');
      assert(audioPart.inline_data.mime_type === 'audio/wav', "inline_data.mime_type must be 'audio/wav'");
      assert(audioPart.inline_data.data === dummyB64, 'inline_data.data must contain base64 audio string');

      // Prompt part
      const promptPart = parts[1];
      assert(promptPart.text === 'Transcribe this audio verbatim.', "Second part must request: 'Transcribe this audio verbatim.'");
    });

    await runTest('Payload', 'Sets temperature to 0.0 for deterministic verbatim transcription', () => {
      const payload = buildGeminiTranscriptionPayload('SAMPLE_B64');
      assert(payload.generationConfig !== undefined, 'generationConfig must be present');
      assert(payload.generationConfig.temperature === 0.0, 'temperature must be 0.0 for deterministic verbatim STT');
    });

    // =========================================================================
    // CATEGORY 5: Response Parsing & Transcript Cleaning
    // =========================================================================
    console.log('\n--- CATEGORY 5: Response Parsing & Transcript Cleaning ---');

    await runTest('Parser', 'Extracts clean speech transcript from standard Gemini JSON response', () => {
      const responseObj = mockGeminiSuccessResponse('The quick brown fox jumps over the lazy dog.');
      const parsed = parseGeminiTranscriptionResponse(responseObj);
      assert(parsed === 'The quick brown fox jumps over the lazy dog.', 'Parsed transcript must match output text');
    });

    await runTest('Parser', 'Strips markdown code fences and trims whitespace', () => {
      const fenceWithLang = '```markdown\nPatient reports persistent dry cough and fatigue.\n```';
      const parsed = cleanTranscript(fenceWithLang);
      assert(
        parsed === 'Patient reports persistent dry cough and fatigue.',
        'Markdown code fence with language tag must be stripped cleanly'
      );
    });

    await runTest('Parser', 'Strips raw code fences and surrounding whitespace', () => {
      const rawFence = '   ```\nDoctor prescribed amoxicillin 500mg three times daily.\n```   ';
      const parsed = cleanTranscript(rawFence);
      assert(
        parsed === 'Doctor prescribed amoxicillin 500mg three times daily.',
        'Raw code fence and outer whitespace must be stripped cleanly'
      );
    });

    await runTest('Parser', 'Safely handles empty candidates, empty parts, and empty JSON without crashing', () => {
      assert(parseGeminiTranscriptionResponse({}) === '', 'Empty object returns empty string');
      assert(parseGeminiTranscriptionResponse({ candidates: [] }) === '', 'Empty candidates returns empty string');
      assert(parseGeminiTranscriptionResponse({ candidates: [{ content: { parts: [] } }] }) === '', 'Empty parts returns empty string');
      assert(parseGeminiTranscriptionResponse('') === '', 'Empty string returns empty string');
      assert(parseGeminiTranscriptionResponse(null) === '', 'Null returns empty string');
    });

    // =========================================================================
    // CATEGORY 6: Integrated Transcription Pipeline & Edge Cases
    // =========================================================================
    console.log('\n--- CATEGORY 6: Integrated Transcription Pipeline & Edge Cases ---');

    await runTest('Pipeline', 'Executes successful end-to-end transcription using configured key and PCM audio', async () => {
      mockSecureStoreAvailable = true;
      await saveGeminiApiKey('AIzaSyIntegratedTranscriptionLiveKey');

      let capturedUrl = '';
      let capturedMethod = '';
      let capturedHeaders: any = null;
      let capturedPayload: any = null;

      global.fetch = async (url: any, opts: any) => {
        capturedUrl = String(url);
        capturedMethod = opts.method;
        capturedHeaders = opts.headers;
        capturedPayload = JSON.parse(opts.body);

        return {
          ok: true,
          status: 200,
          json: async () => mockGeminiSuccessResponse('Welcome to VelaVoice advanced clinical speech transcription.'),
        } as any;
      };

      const pcmAudio = generateSyntheticPcm(100, 16000);
      const transcriptionResult = await transcribeAudio(pcmAudio);

      assert(transcriptionResult.success === true, 'Transcription must succeed');
      assert(
        transcriptionResult.text === 'Welcome to VelaVoice advanced clinical speech transcription.',
        'Clean transcript must be returned'
      );

      // Verify HTTP call details
      assert(capturedMethod === 'POST', 'HTTP method must be POST');
      assert(capturedHeaders['Content-Type'] === 'application/json', 'Content-Type must be application/json');
      assert(
        capturedUrl === `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent?key=AIzaSyIntegratedTranscriptionLiveKey`,
        'Endpoint URL must match gemini-2.0-flash with configured key'
      );
      assert(capturedPayload.contents[0].parts[0].inline_data.mime_type === 'audio/wav', 'Payload must carry audio/wav inline data');
    });

    await runTest('EdgeCase', 'Handles HTTP 429 Rate Limit / Quota Exceeded with clear free tier message', async () => {
      global.fetch = async () => ({
        ok: false,
        status: 429,
        json: async () => ({
          error: { code: 429, message: 'Resource exhausted: rate limit reached', status: 'RESOURCE_EXHAUSTED' },
        }),
      } as any);

      const pcmAudio = generateSyntheticPcm(50, 16000);
      const result = await transcribeAudio(pcmAudio, 'AIzaSyRateLimitKey');

      assert(result.success === false, 'Rate limit must return success: false');
      assert(result.error !== undefined, 'Error message must be provided');
      assert(
        result.error!.toLowerCase().includes('rate limit') || result.error!.toLowerCase().includes('quota'),
        'Error must mention rate limit or quota'
      );
      assert(
        result.error!.includes('15 requests/minute'),
        'Error must inform user about the 15 requests/minute free tier limit'
      );
    });

    await runTest('EdgeCase', 'Handles HTTP 403 Forbidden Invalid API Key with user-facing message', async () => {
      global.fetch = async () => ({
        ok: false,
        status: 403,
        json: async () => ({
          error: { code: 403, message: 'API key not valid.', status: 'PERMISSION_DENIED' },
        }),
      } as any);

      const pcmAudio = generateSyntheticPcm(50, 16000);
      const result = await transcribeAudio(pcmAudio, 'AIzaSyForbiddenKey_403');

      assert(result.success === false, 'HTTP 403 must fail transcription');
      assert(
        result.error!.toLowerCase().includes('invalid gemini api key') || result.error!.toLowerCase().includes('unauthorized'),
        'Error must clearly state invalid API key or unauthorized access'
      );
    });

    await runTest('EdgeCase', 'Handles HTTP 400 Bad Request Invalid API Key with user-facing message', async () => {
      global.fetch = async () => ({
        ok: false,
        status: 400,
        json: async () => ({
          error: { code: 400, message: 'API key not valid.', status: 'INVALID_ARGUMENT' },
        }),
      } as any);

      const pcmAudio = generateSyntheticPcm(50, 16000);
      const result = await transcribeAudio(pcmAudio, 'AIzaSyBadRequestKey_400');

      assert(result.success === false, 'HTTP 400 must fail transcription');
      assert(
        result.error!.toLowerCase().includes('invalid gemini api key') || result.error!.toLowerCase().includes('unauthorized'),
        'Error must state invalid API key'
      );
    });

    await runTest('EdgeCase', 'Rejects empty audio data immediately without making network call', async () => {
      let networkCalled: boolean = false;
      global.fetch = async () => {
        networkCalled = true;
        return { ok: true, status: 200 } as any;
      };

      const emptyPcm = new Uint8Array(0);
      const result = await transcribeAudio(emptyPcm, 'AIzaSyValidKey');

      assert(result.success === false, 'Empty audio must fail validation');
      assert(result.error!.toLowerCase().includes('empty'), 'Error must specify audio data is empty');
      assert(networkCalled === false, 'Must not issue network request for empty audio');
    });

    await runTest('EdgeCase', 'Rejects transcription when no API key is configured or provided', async () => {
      mockStorage.clear();
      let networkCalled: boolean = false;
      global.fetch = async () => {
        networkCalled = true;
        return { ok: true, status: 200 } as any;
      };

      const pcmAudio = generateSyntheticPcm(50, 16000);
      const result = await transcribeAudio(pcmAudio, '');

      assert(result.success === false, 'Missing API key must fail validation');
      assert(
        result.error!.toLowerCase().includes('invalid gemini api key') || result.error!.toLowerCase().includes('unauthorized'),
        'Error must specify key requirement / authorization'
      );
      assert(networkCalled === false, 'Must not issue network request when key is missing');
    });

    await runTest('EdgeCase', 'Handles network timeout / abort during audio transcription gracefully', async () => {
      global.fetch = async () => {
        const err = new Error('The user aborted a request.');
        err.name = 'AbortError';
        throw err;
      };

      const pcmAudio = generateSyntheticPcm(50, 16000);
      const result = await transcribeAudio(pcmAudio, 'AIzaSyTimeoutKey');

      assert(result.success === false, 'Timeout must return success: false');
      assert(result.error!.toLowerCase().includes('timed out'), 'Error message must state connection timed out');
    });

    // =========================================================================
    // CATEGORY 7: Full Multi-Step User Journey Simulation
    // =========================================================================
    console.log('\n--- CATEGORY 7: Full Multi-Step User Journey Simulation ---');

    await runTest('UserJourney', 'Simulates entire end-to-end user journey: configure -> preflight test -> record -> transcribe -> 429 recovery', async () => {
      // Step 1: User enters Google AI Studio API key in GeminiSettings
      const userEnteredKey = 'AIzaSyDoctorApiKey_ClinicalWorkflow999';

      // Step 2: Pre-flight connection test gate
      let preflightCalled: boolean = false;
      global.fetch = async (url: any) => {
        preflightCalled = true;
        assert(String(url).includes('key=AIzaSyDoctorApiKey_ClinicalWorkflow999'), 'Preflight must verify entered key');
        return {
          ok: true,
          status: 200,
          json: async () => mockGeminiSuccessResponse('ping'),
        } as any;
      };

      const preflightResult = await testGeminiApiKey(userEnteredKey);
      assert(preflightResult.success === true, 'Preflight connection test must succeed');
      assert(Boolean(preflightCalled), 'Preflight request must be executed');

      // Step 3: Key saved to SecureStore upon successful preflight verification
      await saveGeminiApiKey(userEnteredKey);
      const verifiedStoredKey = await getGeminiApiKey();
      assert(verifiedStoredKey === userEnteredKey, 'Key must be securely stored');

      // Step 4: User records audio in app (16kHz mono PCM)
      const clinicalAudioPcm = generateSyntheticPcm(200, 16000, 300);
      assert(clinicalAudioPcm.length === 6400, '200ms of 16kHz 16-bit audio = 6400 bytes');

      // Step 5: First transcription attempt succeeds
      global.fetch = async (url: any, opts: any) => {
        const payload = JSON.parse(opts.body);
        assert(payload.system_instruction.parts[0].text === VERBATIM_SYSTEM_INSTRUCTION, 'Payload must use verbatim system instruction');
        return {
          ok: true,
          status: 200,
          json: async () => mockGeminiSuccessResponse('Patient is a 45-year-old male presenting with chronic lower back pain.'),
        } as any;
      };

      const firstTranscribe = await transcribeAudio(clinicalAudioPcm);
      assert(firstTranscribe.success === true, 'First transcription must succeed');
      assert(
        firstTranscribe.text === 'Patient is a 45-year-old male presenting with chronic lower back pain.',
        'Transcript must be accurate'
      );

      // Step 6: Subsequent rapid request triggers 429 rate limit (free tier threshold)
      global.fetch = async () => ({
        ok: false,
        status: 429,
        json: async () => ({
          error: { code: 429, message: 'Resource exhausted: rate limit exceeded', status: 'RESOURCE_EXHAUSTED' },
        }),
      } as any);

      const rateLimitedTranscribe = await transcribeAudio(clinicalAudioPcm);
      assert(rateLimitedTranscribe.success === false, 'Rate limited call must return failure');
      assert(rateLimitedTranscribe.error!.includes('15 requests/minute'), 'User must be notified of 15 requests/min limit');

      // Step 7: System recovers cleanly on next attempt (after backoff)
      global.fetch = async () => ({
        ok: true,
        status: 200,
        json: async () => mockGeminiSuccessResponse('Recovery test: Vital signs stable, blood pressure 120 over 80.'),
      } as any);

      const recoveredTranscribe = await transcribeAudio(clinicalAudioPcm);
      assert(recoveredTranscribe.success === true, 'Transcriber must recover cleanly after rate limit clears');
      assert(
        recoveredTranscribe.text === 'Recovery test: Vital signs stable, blood pressure 120 over 80.',
        'Recovered transcript must be accurate'
      );
    });

  } finally {
    global.fetch = originalFetch;
  }

  // =========================================================================
  // Test Execution Summary Report
  // =========================================================================
  const total = testResults.length;
  const passed = testResults.filter((r) => r.status === 'PASS').length;
  const failed = testResults.filter((r) => r.status === 'FAIL').length;
  const totalDuration = testResults.reduce((sum, r) => sum + r.durationMs, 0);

  console.log('\n======================================================================');
  console.log('  E2E TEST VERIFICATION SUMMARY REPORT');
  console.log('======================================================================');
  console.log(`  Total Tests Run:  ${total}`);
  console.log(`  Passed:          ${passed}`);
  console.log(`  Failed:          ${failed}`);
  console.log(`  Total Time:      ${totalDuration}ms`);
  console.log('----------------------------------------------------------------------');

  const categories = Array.from(new Set(testResults.map((r) => r.category)));
  for (const cat of categories) {
    const catTests = testResults.filter((r) => r.category === cat);
    const catPass = catTests.filter((r) => r.status === 'PASS').length;
    console.log(`  Category [${cat.padEnd(12)}]: ${catPass}/${catTests.length} Passed`);
  }
  console.log('======================================================================\n');

  if (failed > 0) {
    console.error(`E2E Verification FAILED with ${failed} failure(s).`);
    process.exit(1);
  } else {
    console.log(`E2E Verification PASSED! All ${passed}/${total} test cases succeeded.\n`);
    process.exit(0);
  }
}

runAllE2ETests().catch((err) => {
  console.error('Fatal unhandled error during E2E verification:', err);
  process.exit(1);
});
