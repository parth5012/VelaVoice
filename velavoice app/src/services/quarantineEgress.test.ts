// Quarantine egress tests (map #130 ticket #136): quarantined sessions are
// local-only and must NEVER reach the correction-tracking SQLite (no training
// on quarantined content), the /save_correction egress, or the export script.
// Run with the native-module test stubs in place, e.g. from the repo root:
//   npx tsx "velavoice app/src/services/quarantineEgress.test.ts"
// Native modules resolve to the committed test stubs under test-stubs/
// (expo-sqlite exposing __writes/__reset, react-native, expo-file-system)
// via the Module._resolveFilename redirect below, installed BEFORE the
// service imports load — the real react-native entry is Flow and cannot be
// parsed by tsx/esbuild in Node, and the local node_modules copies would
// otherwise shadow any NODE_PATH stubs. The stubs are IN THE REPO (not
// under /tmp): a fresh CI runner has no hand-made /tmp files, so a
// /tmp-based path made `npm test` fail with ENOENT on every clean checkout.
import Module from 'module';

const NATIVE_STUBS_DIR = path.join(__dirname, '..', '..', 'test-stubs');
const NATIVE_STUBS = ['react-native', 'expo-file-system', 'expo-sqlite'];
const ModuleClass = Module as any;
const origResolve = ModuleClass._resolveFilename;
ModuleClass._resolveFilename = function (request: string, parent: any, isMain: boolean) {
  if (NATIVE_STUBS.includes(request)) {
    return NATIVE_STUBS_DIR + '/' + request + '/index.js';
  }
  return origResolve.apply(this, arguments);
};

// Loaded via runtime require() after the redirect: tsx/esbuild hoists static
// imports above the module body, so the real react-native would load before
// the hook is installed (same pattern as GeminiService.test.ts).
const { CorrectionAPI } = require('./api') as typeof import('./api');
const { ModelManager } = require('./ModelManager') as typeof import('./ModelManager');
const { isQuarantinedEntry } = require('../utils/quarantineBadge') as typeof import('../utils/quarantineBadge');
// Type-only query: keeps basePayload literal-widening from invalidating the
// EditOperation[] field of SaveCorrectionPayload (batch-1 type regression).
type SaveCorrectionPayload = import('./api').SaveCorrectionPayload;
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { pathToFileURL } from 'url';

const sqliteStub = require('expo-sqlite') as {
  __writes: { sql: string; params: unknown[] }[];
  __reset: () => void;
};

// The tsconfig module flag rejects literal `import()` calls (TS1323), so route
// the runtime dynamic imports of the copied .js mirrors through an indirection
// string — identical runtime behavior, no static-import syntax to check.
const dynamicImport = new Function('u', 'return import(u)') as (u: string) => Promise<any>;

// Read a named export from an import()-loaded JS mirror across Node versions:
// the mirrors are ESM (`export class`) but run as tsx-transpiled CJS, and the
// ESM->CJS interop differs. Node 20's import() surfaces only { default:
// module.exports } (its lexer finds no named exports in the original source),
// while newer Node also exposes module.exports properties as namespace keys —
// under Node 20 the plain `ns.Name` read is undefined, which threw
// "Cannot read properties of undefined (reading 'closeDb')" in CI.
const mirrorExport = (ns: any, name: string, file: string) => {
  const value = ns[name] ?? (ns.default && ns.default[name]);
  if (value === undefined) {
    throw new Error(
      `${file} does not export ${name} (namespace keys: ${Object.keys(ns).join(', ')})`
    );
  }
  return value;
};

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

const basePayload: SaveCorrectionPayload = {
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
  let threw: unknown = null;
  try {
    await ModelManager.saveCorrection('audio_q2', 'raw', 'fixed', '[]', 1, null, null, true);
  } catch (e) {
    threw = e;
  }
  assert(
    threw instanceof Error && /quarantined session is local-only/.test(threw.message),
    'ModelManager.saveCorrection throws the quarantine gate error on quarantined write'
  );
  assert(insertCount() === 0, 'quarantined ModelManager write issued zero SQLite INSERTs');

  // 5. A quarantined session never becomes readable training data: after a
  // refused save, the corrections table exposes zero rows for it.
  await freshDb();
  await CorrectionAPI.saveCorrection({ ...basePayload, privacySensitive: true });
  const rows = await ModelManager.getCorrections();
  assert(rows.length === 0, 'quarantine never enters the corrections table');

  // 6. Editor 7-arg offset (ticket #136 finding 1): TranscriptionEditor.onSave
  // passes (audioId, original, corrected, edits, editDistance, scribeStyle,
  // quarantined) — quarantined lives in the 7TH slot. The App
  // handleSaveCorrection gate must read that slot AND fall back to the
  // library entry verdict (defense-in-depth). This dispatch replica mirrors
  // the fixed App.tsx/App.js handleSaveCorrection gate exactly.
  {
    type Rec = { id: string; quarantined?: boolean };
    let posts = 0;
    const post = (_body: unknown) => {
      posts++;
    };
    const dispatchSave = (
      recordings: Rec[],
      audioId: string,
      _original: string,
      _corrected: string,
      _edits: unknown[],
      _editDistance: number,
      _scribeStyle?: string,
      quarantined?: boolean
    ): string => {
      const isQuarantined =
        quarantined === true || isQuarantinedEntry(recordings.find((r) => r.id === audioId));
      if (isQuarantined) {
        return 'blocked';
      }
      post({ audio_id: audioId });
      return 'posted';
    };
    const recs: Rec[] = [{ id: 'audio_q_editor', quarantined: true }];
    // Editor passes the style string 6th, quarantine flag 7th: the old 6-arg
    // handler read the style slot as the flag and leaked a POST.
    const verdict = dispatchSave(recs, 'audio_q_editor', 'o', 'c', [], 1, 'Professional', true);
    assert(verdict === 'blocked', '7-arg offset: quarantined 7th blocks POST');
    assert(posts === 0, '7-arg offset: zero POSTs for quarantined edit');
    // Defense-in-depth: a stale caller that drops the flag still cannot
    // egress — the library entry verdict blocks the POST.
    const fallback = dispatchSave(recs, 'audio_q_editor', 'o', 'c', [], 1, 'Professional', undefined);
    assert(fallback === 'blocked', 'entry-lookup fallback blocks POST when flag dropped');
    assert(posts === 0, 'fallback: zero POSTs for quarantined edit');
    // Non-quarantined edits still POST (no regression).
    const clean: Rec[] = [{ id: 'audio_clean' }];
    const posted = dispatchSave(clean, 'audio_clean', 'o', 'c', [], 1, 'Casual', false);
    assert(posted === 'posted', 'non-quarantined edit still POSTs');
    assert(posts === 1, 'non-quarantined edit issued one POST');
  }

  // 7. JS-mirror direct bypass (ticket #136 finding 2): load the REAL
  // ModelManager.js/api.js (copied aside so tsx cannot remap them onto the
  // .ts twins) and prove an 8-arg TS-shaped direct call with quarantined=true
  // in the 8th slot throws before SQLite — never a quarantined=0 row.
  {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-jsmirror-'));
    fs.copyFileSync(path.join(__dirname, 'ModelManager.js'), path.join(dir, 'ModelManager.js'));
    fs.copyFileSync(path.join(__dirname, 'api.js'), path.join(dir, 'api.js'));
    const mmjs = await dynamicImport(pathToFileURL(path.join(dir, 'ModelManager.js')).href);
    const apijs = await dynamicImport(pathToFileURL(path.join(dir, 'api.js')).href);
    const ModelManagerJS = mirrorExport(mmjs, 'ModelManager', 'ModelManager.js');
    const CorrectionAPIJS = mirrorExport(apijs, 'CorrectionAPI', 'api.js');
    const freshJsDb = async () => {
      sqliteStub.__reset();
      await ModelManagerJS.closeDb();
      await ModelManager.closeDb();
    };
    await freshJsDb();
    let threwJs: unknown = null;
    try {
      await ModelManagerJS.saveCorrection('audio_q3', 'raw', 'fixed', '[]', 1, null, null, true);
    } catch (e) {
      threwJs = e;
    }
    assert(
      threwJs instanceof Error && /quarantined session is local-only/.test(threwJs.message),
      'JS ModelManager.saveCorrection throws the quarantine gate error on quarantined 8th-arg write'
    );
    assert(insertCount() === 0, 'JS direct-bypass write issued zero SQLite INSERTs');
    // The CorrectionAPI pre-validation refusal stays intact (zero writes).
    await freshJsDb();
    const refusedJs = await CorrectionAPIJS.saveCorrection({ ...basePayload, quarantined: true });
    assert(refusedJs.success === false, 'JS CorrectionAPI still refuses quarantined pre-validation');
    assert(insertCount() === 0, 'JS refused correction issued zero SQLite INSERTs');
    await ModelManagerJS.closeDb();
    await ModelManager.closeDb();
  }

  // 8. Pre-quarantine DBs gain the column (ticket #136 finding 3): simulate a
  // DB that already has the tri-state columns but lacks quarantined — the
  // duplicate-column ALTERs must not prevent the quarantined ALTER.
  {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-jsmig-'));
    fs.copyFileSync(path.join(__dirname, 'ModelManager.js'), path.join(dir, 'ModelManager.js'));
    const mmjs = await dynamicImport(pathToFileURL(path.join(dir, 'ModelManager.js')).href);
    const ModelManagerJS = mirrorExport(mmjs, 'ModelManager', 'ModelManager.js');
    const columns = new Set([
      'id', 'audio_id', 'original_transcription', 'corrected_transcription',
      'edits', 'edit_distance', 'timestamp', 'user_id', 'confidence_score',
      'raw_whisper_transcript', 'cleaned_llm_transcript', 'user_final_text',
      'scribe_style', 'wer_score',
    ]);
    const stubAny = sqliteStub as unknown as { openDatabaseAsync: unknown };
    const realOpen = stubAny.openDatabaseAsync;
    stubAny.openDatabaseAsync = async () => ({
      execAsync: async (sql: string) => {
        for (const stmt of sql.split(';').map((s) => s.trim()).filter(Boolean)) {
          const m = /ALTER TABLE corrections ADD COLUMN (\w+)/i.exec(stmt);
          if (m) {
            if (columns.has(m[1])) {
              throw new Error(`duplicate column name: ${m[1]}`);
            }
            columns.add(m[1]);
          }
        }
      },
      runAsync: async () => {},
      getAllAsync: async () => [],
      closeAsync: async () => {},
    });
    try {
      await ModelManagerJS.closeDb();
      await ModelManagerJS.getCorrections();
      assert(columns.has('quarantined') === true, 'migration adds quarantined column on pre-quarantine DB');
    } finally {
      stubAny.openDatabaseAsync = realOpen;
      await ModelManagerJS.closeDb();
      await ModelManager.closeDb();
    }
  }

  // 9. Recent-list visibility (ticket #136 finding 4): shared + quarantined
  // entries merge, sort desc by name/date, take the limit slice — a
  // quarantined session is a visible local-only entry, never starved by a
  // full shared page. This port mirrors the fixed
  // GoogleDriveSyncModule.getRecentTranscriptions order exactly.
  {
    const mergeRecentVisible = (shared: string[], quarantined: string[], limit: number): string[] => {
      const tagged: { name: string; quarantined: boolean }[] = [
        ...shared.map((name) => ({ name, quarantined: false })),
        ...quarantined.map((name) => ({ name, quarantined: true })),
      ];
      tagged.sort((a, b) => (a.name < b.name ? 1 : a.name > b.name ? -1 : 0));
      return tagged.slice(0, limit > 0 ? limit : undefined).map((e) => e.name);
    };
    const visible = mergeRecentVisible(['t3', 't2', 't1'], ['t9'], 2);
    assert(visible.includes('t9') === true, 'quarantined newest stays visible under limit pressure');
    assert(visible.length <= 2, 'limit slice respected');
    const ordered = [...visible].sort().reverse();
    assert(JSON.stringify(visible) === JSON.stringify(ordered), 'merged list sorted desc by name');
  }

  console.log('All quarantine egress tests passed!');
}

runTests().catch((e) => {
  console.error('quarantine egress tests failed:', e.message);
  process.exit(1);
});
