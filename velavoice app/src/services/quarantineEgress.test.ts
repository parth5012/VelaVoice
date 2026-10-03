// Quarantine egress tests (map #130 ticket #136): quarantined sessions are
// local-only and must NEVER reach the correction-tracking SQLite (no training
// on quarantined content), the /save_correction egress, or the export script.
// Run with NODE_PATH=/tmp/vv-stubs/node_modules from the repo root, e.g.:
//   NODE_PATH=/tmp/vv-stubs/node_modules node <tsx-cli> "velavoice app/src/services/quarantineEgress.test.ts"
import { CorrectionAPI } from './api';
import { ModelManager } from './ModelManager';

const sqliteStub = require('expo-sqlite') as {
  __writes: { sql: string; params: unknown[] }[];
  __reset: () => void;
};

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

const basePayload = {
  audio_id: 'audio_quarantine_001',
  original_transcription: 'my password is hunter2',
  corrected_transcription: 'my password is hunter2!',
  edits: [{ type: 'substitution', original: 'x', corrected: 'y', position: 0 }],
  edit_distance: 1,
  user_id: 'local_user',
  confidence_score: 0.9,
};

async function freshDb() {
  sqliteStub.__reset();
  await ModelManager.closeDb();
}

function insertCount(): number {
  return sqliteStub.__writes.filter((w) => /^insert\s+into\s+corrections/i.test(w.sql)).length;
}

async function runTests() {
  console.log('Running quarantine egress tests...');

  // 1. CorrectionAPI refuses quarantined payloads before touching SQLite.
  await freshDb();
  const refused = await CorrectionAPI.saveCorrection({ ...basePayload, privacySensitive: true });
  assert(refused.success === false, 'quarantined correction refused at API gate');
  assert(insertCount() === 0, 'refused correction issued zero SQLite INSERTs');

  // 2. Same refusal via the quarantined alias flag.
  await freshDb();
  const refusedAlias = await CorrectionAPI.saveCorrection({ ...basePayload, quarantined: true });
  assert(refusedAlias.success === false, 'quarantined-alias correction refused at API gate');
  assert(insertCount() === 0, 'alias-refused correction issued zero SQLite INSERTs');

  // 3. Non-quarantined corrections still save (no regression).
  await freshDb();
  const ok = await CorrectionAPI.saveCorrection({ ...basePayload });
  assert(ok.success === true, 'non-quarantined correction still saves');
  assert(insertCount() === 1, 'non-quarantined correction issued one SQLite INSERT');

  // 4. ModelManager is a second fail-closed gate: a direct quarantined write
  // throws and never reaches SQLite, even bypassing the API layer.
  await freshDb();
  let threw = false;
  try {
    await ModelManager.saveCorrection('audio_q2', 'raw', 'fixed', '[]', 1, null, null, true);
  } catch {
    threw = true;
  }
  assert(threw === true, 'ModelManager.saveCorrection throws on quarantined write');
  assert(insertCount() === 0, 'quarantined ModelManager write issued zero SQLite INSERTs');

  // 5. A quarantined session never becomes readable training data: after a
  // refused save, the corrections table exposes zero rows for it.
  await freshDb();
  await CorrectionAPI.saveCorrection({ ...basePayload, privacySensitive: true });
  const rows = await ModelManager.getCorrections();
  assert(rows.length === 0, 'quarantine never enters the corrections table');

  console.log('All quarantine egress tests passed!');
}

runTests().catch((e) => {
  console.error('quarantine egress tests failed:', e.message);
  process.exit(1);
});
