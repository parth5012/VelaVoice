// ScribeAI config-check tests. Modules load via runtime require() after the
// mock hooks: tsx/esbuild hoists static imports above the module body
// (same pattern as quarantineEgress.test.ts / GeminiService.test.ts).
import Module from 'module';

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

// Mutable mocks so each load can vary build-time keys and SecureStore state.
const constantsMock: { expoConfig: { extra: Record<string, string | undefined> } } = {
  expoConfig: { extra: {} },
};
let storeGeminiKey: string | null = null;
let throwOnKeyRead = false;

const geminiServiceMock = {
  getGeminiApiKey: async (): Promise<string | null> => {
    if (throwOnKeyRead) throw new Error('SecureStore unavailable');
    return storeGeminiKey;
  },
  getGeminiModel: async (): Promise<string | null> => null,
  GEMINI_MODEL: 'gemini-3.6-flash',
};

mockModule('expo-constants', constantsMock);
mockModule('./GeminiService', geminiServiceMock);

const { ScribeAI } = require('./ScribeAI') as typeof import('./ScribeAI');

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

async function runTests() {
  console.log('Running ScribeAI config tests...');

  // Case 1: build-time key (expo extra) counts as configured.
  constantsMock.expoConfig.extra = { geminiApiKey: 'build-key' };
  storeGeminiKey = null;
  assert((await ScribeAI.isConfiguredAsync('gemini')) === true, 'build-time gemini key counts');
  assert((await ScribeAI.isConfiguredAsync('groq')) === false, 'absent groq build key is unconfigured');

  // Case 2: SecureStore-only key (set in Engine Settings at runtime) must
  // count as configured — the old sync isConfigured only read build-time
  // Constants and wrongly reported (off).
  constantsMock.expoConfig.extra = {};
  storeGeminiKey = 'live-secure-store-key';
  assert((await ScribeAI.isConfiguredAsync('gemini')) === true, 'SecureStore live key counts');

  // Case 3: no build key and no stored key -> unconfigured.
  storeGeminiKey = null;
  assert((await ScribeAI.isConfiguredAsync('gemini')) === false, 'no keys anywhere is unconfigured');

  // Case 4: SecureStore read failure fails closed without crashing.
  throwOnKeyRead = true;
  assert((await ScribeAI.isConfiguredAsync('gemini')) === false, 'SecureStore throw fails closed');

  // Case 5: groq build-time key counts.
  throwOnKeyRead = false;
  constantsMock.expoConfig.extra = { groqApiKey: 'groq-build-key' };
  assert((await ScribeAI.isConfiguredAsync('groq')) === true, 'build-time groq key counts');

  console.log('All ScribeAI config tests passed!');
}

runTests().catch((e) => {
  console.error(e);
  process.exit(1);
});
