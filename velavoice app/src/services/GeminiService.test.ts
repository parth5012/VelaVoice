import Module from 'module';

// Intercept imports of native/platform packages before GeminiService is loaded
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

// Now safely load GeminiService with mocked native modules
const {
  testGeminiApiKey,
  saveGeminiApiKey,
  getGeminiApiKey,
  deleteGeminiApiKey,
  saveGeminiModel,
  getGeminiModel,
  GEMINI_MODEL,
  GEMINI_MODEL_STORAGE_KEY,
  GEMINI_API_STORAGE_KEY,
  GOOGLE_AI_STUDIO_URL,
  SUPPORTED_GEMINI_MODELS,
} = require('./GeminiService');

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

async function runTests() {
  console.log('Running GeminiService unit tests...');
  const originalFetch = global.fetch;

  try {
    // Test 1: Valid API Key (HTTP 200)
    console.log('? Test 1: Valid key 200 response');
    let capturedUrl = '';
    let capturedOptions: any = null;

    global.fetch = async (url: any, options: any) => {
      capturedUrl = String(url);
      capturedOptions = options;
      return {
        ok: true,
        status: 200,
        json: async () => ({
          candidates: [
            {
              content: {
                parts: [{ text: 'pong' }],
                role: 'model',
              },
              finishReason: 'STOP',
            },
          ],
        }),
      } as any;
    };

    const validResult = await testGeminiApiKey('AIzaSyValidTestKey123');
    assert(validResult.success === true, 'Valid key should return success: true');
    assert(validResult.error === undefined, 'Valid key should have no error message');
    assert(
      capturedUrl.includes('gemini-3.5-transcribe:generateContent?key=AIzaSyValidTestKey123'),
      'URL must query gemini-3.5-transcribe with passed API key'
    );
    assert(capturedOptions.method === 'POST', 'Request must be POST');
    const bodyObj = JSON.parse(capturedOptions.body);
    assert(Array.isArray(bodyObj.contents), 'Body must contain contents array');
    assert(bodyObj.generationConfig?.maxOutputTokens === 1, 'Query must be lightweight maxOutputTokens 1');

    // Test 2: Invalid API Key (HTTP 403 Forbidden / PERMISSION_DENIED)
    console.log('? Test 2: Invalid key 403 response');
    global.fetch = async () => {
      return {
        ok: false,
        status: 403,
        json: async () => ({
          error: {
            code: 403,
            message: 'API key not valid. Please pass a valid API key.',
            status: 'PERMISSION_DENIED',
          },
        }),
      } as any;
    };

    const invalidResult403 = await testGeminiApiKey('AIzaSyInvalidKey403');
    assert(invalidResult403.success === false, '403 response must return success: false');
    assert(
      invalidResult403.error !== undefined &&
        invalidResult403.error.toLowerCase().includes('invalid'),
      '403 response must return clean human-friendly invalid key message'
    );

    // Test 3: Invalid API Key (HTTP 400 Bad Request / INVALID_ARGUMENT)
    console.log('? Test 3: Invalid key 400 response');
    global.fetch = async () => {
      return {
        ok: false,
        status: 400,
        json: async () => ({
          error: {
            code: 400,
            message: 'API key not valid. Please pass a valid API key.',
            status: 'INVALID_ARGUMENT',
            details: [{ reason: 'API_KEY_INVALID' }],
          },
        }),
      } as any;
    };

    const invalidResult400 = await testGeminiApiKey('AIzaSyInvalidKey400');
    assert(invalidResult400.success === false, '400 response must return success: false');
    assert(
      invalidResult400.error !== undefined &&
        invalidResult400.error.toLowerCase().includes('invalid'),
      '400 response must return clean human-friendly invalid key message'
    );

    // Test 4: Rate limit / Quota Exceeded (HTTP 429)
    console.log('? Test 4: Rate limit / Quota exceeded 429 response');
    global.fetch = async () => {
      return {
        ok: false,
        status: 429,
        json: async () => ({
          error: {
            code: 429,
            message: 'Resource has been exhausted (e.g. check quota).',
            status: 'RESOURCE_EXHAUSTED',
          },
        }),
      } as any;
    };

    const rateLimitResult = await testGeminiApiKey('AIzaSyRateLimitedKey');
    assert(rateLimitResult.success === false, '429 response must return success: false');
    assert(
      rateLimitResult.error !== undefined &&
        (rateLimitResult.error.toLowerCase().includes('quota') ||
          rateLimitResult.error.toLowerCase().includes('rate limit')),
      '429 response must return clean human-friendly quota exceeded message'
    );

    // Test 5: Network timeout (AbortError)
    console.log('? Test 5: Network timeout handling');
    global.fetch = async () => {
      const err = new Error('The operation was aborted');
      err.name = 'AbortError';
      throw err;
    };

    const timeoutResult = await testGeminiApiKey('AIzaSyTimeoutKey');
    assert(timeoutResult.success === false, 'Timeout must return success: false');
    assert(
      timeoutResult.error !== undefined &&
        timeoutResult.error.toLowerCase().includes('timed out'),
      'Timeout must return human-friendly connection timed out message'
    );

    // Test 6: Empty or whitespace API key
    console.log('? Test 6: Empty API key rejection');
    const emptyResult = await testGeminiApiKey('   ');
    assert(emptyResult.success === false, 'Empty key must return success: false');
    assert(
      emptyResult.error !== undefined &&
        emptyResult.error.toLowerCase().includes('required'),
      'Empty key must return human-friendly required message'
    );

    // Test 7: SecureStore Storage operations (save, retrieve, delete)
    console.log('? Test 7: SecureStore key management');
    mockStorage.clear();
    mockSecureStoreAvailable = true;

    await saveGeminiApiKey('AIzaSySecureTestKey123');
    const retrievedKey = await getGeminiApiKey();
    assert(
      retrievedKey === 'AIzaSySecureTestKey123',
      'Stored key must match retrieved key'
    );
    assert(
      mockStorage.get(GEMINI_API_STORAGE_KEY) === 'AIzaSySecureTestKey123',
      'Key must be saved under GEMINI_API_STORAGE_KEY in SecureStore'
    );

    await deleteGeminiApiKey();
    const afterDelete = await getGeminiApiKey();
    assert(
      afterDelete === null || afterDelete === '',
      'Key must be removed after deleteGeminiApiKey'
    );

    // Test 8: Fallback when SecureStore is unavailable
    console.log('✓ Test 8: SecureStore fallback handling');
    mockSecureStoreAvailable = false;
    await saveGeminiApiKey('AIzaSyFallbackKey');
    const fallbackRetrieved = await getGeminiApiKey();
    assert(
      fallbackRetrieved === 'AIzaSyFallbackKey',
      'Fallback storage must retain key when SecureStore is unavailable'
    );
    await deleteGeminiApiKey();

    // Test 9: Model selection and storage
    console.log('✓ Test 9: Model selection & storage management');
    mockSecureStoreAvailable = true;
    assert(GEMINI_MODEL === 'gemini-3.5-transcribe', 'Default model must be gemini-3.5-transcribe');
    await saveGeminiModel('gemini-3.5-transcribe-live');
    const retrievedModel = await getGeminiModel();
    assert(retrievedModel === 'gemini-3.5-transcribe-live', 'Model must match saved live model');
    // Verify auto-normalization of bare 3.5 to gemini-3.5-transcribe
    await saveGeminiModel('gemini-3.5');
    const normalizedModel = await getGeminiModel();
    assert(normalizedModel === 'gemini-3.5-transcribe', 'gemini-3.5 must auto-normalize to gemini-3.5-transcribe');

    // Test 10: Model override in testGeminiApiKey
    console.log('✓ Test 10: Model override in testGeminiApiKey');
    global.fetch = async (url: any, options: any) => {
      capturedUrl = String(url);
      capturedOptions = options;
      return {
        ok: true,
        status: 200,
        json: async () => ({
          candidates: [{ content: { parts: [{ text: 'pong' }] }, finishReason: 'STOP' }],
        }),
      } as any;
    };
    await testGeminiApiKey('AIzaSyTestOverrideKey', 'gemini-2.0-flash');
    assert(
      capturedUrl.includes('gemini-2.0-flash:generateContent?key=AIzaSyTestOverrideKey'),
      'URL must query specified model gemini-2.0-flash'
    );

    // Verify constants
    assert(GEMINI_MODEL === 'gemini-3.5-transcribe', 'Model must be gemini-3.5-transcribe');
    assert(
      GOOGLE_AI_STUDIO_URL === 'https://aistudio.google.com',
      'Google AI Studio URL must match'
    );

    console.log('\nAll GeminiService unit tests passed successfully! (10/10)');
  } finally {
    global.fetch = originalFetch;
  }
}

runTests().catch((err) => {
  console.error('Test execution failed:', err);
  process.exit(1);
});
