/**
 * Ticket 805 (map #290): registry sync contract — Kotlin ModelRegistry JSON
 * rows must map cleanly into the TS ModelInfo definition shape, and the
 * native→TS merge must never lose the legacy ids.
 *
 * tsx test: exercises the pure parts (registryRowToModelDefinition,
 * getNativeRegistry fallback). Native-bridge equivalence itself needs an
 * emulator pass — noted in the ticket Resolution.
 */
import {
  getNativeRegistry,
  registryRowToModelDefinition,
} from './ModelRegistryBridge';

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

function expectThrows(fn: () => unknown, message: string) {
  let threw = false;
  try {
    fn();
  } catch {
    threw = true;
  }
  if (!threw) {
    throw new Error('Assertion failed: expected throw: ' + message);
  }
}

async function runTests() {
  console.log('Running registrySync tests...');

  const whisperRow = {
    id: 'whisper-tiny-en',
    name: 'Whisper Tiny (English)',
    engine: 'WHISPER' as const,
    url: 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin',
    filename: 'ggml-tiny.en.bin',
    expectedSha256: '921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f',
    minRamMb: 512,
    supportsStreaming: false,
    multilingual: false,
    languages: ['en'],
    downloadSizeHintMb: 75,
  };

  // Valid WHISPER row maps to a download-available whisper definition.
  const whisperDef = registryRowToModelDefinition(whisperRow);
  assert(whisperDef.id === 'whisper-tiny-en', 'id round-trips');
  assert(whisperDef.engine === 'whisper', 'WHISPER engine lowercased');
  assert(whisperDef.expectedHash === whisperRow.expectedSha256, 'sha256 → expectedHash');
  assert(whisperDef.downloadAvailable === true, 'pinned url row is downloadAvailable');
  assert(Array.isArray(whisperDef.languages) && whisperDef.languages[0] === 'en', 'languages array kept');
  assert(whisperDef.minRamMb === 512, 'minRamMb kept for preflight');

  // SHERPA row with unpinned URL is registered but NOT download-available.
  const sherpaDef = registryRowToModelDefinition({
    ...whisperRow,
    id: 'fluid-speech-mini',
    engine: 'SHERPA' as const,
    url: '',
    expectedSha256: null,
  });
  assert(sherpaDef.engine === 'sherpa', 'SHERPA engine lowercased');
  assert(sherpaDef.downloadAvailable === false, 'unpinned url → not downloadAvailable');
  assert(sherpaDef.expectedHash === '', 'absent sha maps to empty string, not undefined');

  // Drift detector: a row missing the id contract fails loudly.
  expectThrows(
    () => registryRowToModelDefinition(null as unknown as never),
    'null row rejected'
  );
  expectThrows(
    () => registryRowToModelDefinition({ ...whisperRow, id: '' }),
    'empty id rejected'
  );

  // Native bridge absent (tsx runtime) → clean null, never a throw.
  const bridge = await getNativeRegistry();
  assert(bridge === null, 'missing native bridge degrades to null without throwing');

  console.log('registrySync tests passed.');
}

runTests().catch((e) => {
  console.error(e);
  process.exit(1);
});
