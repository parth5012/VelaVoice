# Code Quality Audit Tracking

## Session summary

- Started: 2026-10-06
- Last updated: 2026-10-06 (end of Batch 3)
- Repository scope: `velavoice app/` TypeScript (21 files; ScribePresetManager deleted)
- Current phase: safe-remediation (Batches 1–3 implemented and verified; App.tsx split planned, execution pending)

## Rating legend

| Total % | Rating | Priority |
|---|---|---|
| 87-100% | Excellent | No action needed |
| 70-86% | Good | Low priority improvements |
| 50-69% | Adequate | Medium priority |
| 30-49% | Weak | High priority |
| < 30% | Insufficient | Critical |

## File entries

### `velavoice app/App.tsx`

- status: `updated`
- score: `28% → ~40% est.` (`Insufficient` → `Adequate` trajectory; still god-component until passes 2–3)
- placement_status: `correct` (composition root, thinned)
- import_impact: `high`
- move_reason: `N/A`
- moved_from: `N/A`
- moved_to: `N/A`

#### Findings

- [severity: high] [confidence: high] App.tsx:34-133 - 30+ useState across 7 domains (models/dictionary/drive/recordings/scribe/perms/transcription), god component
- [severity: high] [confidence: high] App.tsx:134-167 - global fetch monkey-patch on mount, side-effect + untestable
- [severity: medium] [confidence: high] App.tsx:85,411 - `any[]` for transcriptions/recordings, loses type safety

#### Improvements applied

- Batch 3: split plan written to `plan/app-split-plan.md` (3 passes, ≤2 extractions each).
- Pass 1 (2026-10-06): fetch patch → `src/services/installHttpOverrides.ts` (idempotent, called from mount effect); `renderEngineRoom` + 67 engine-only style keys → `src/components/engine/EngineRoomScreen.tsx` (props-only screen, `EngineRoomScreenProps`); dead imports removed (Switch, Dimensions, CorrectionAPI, quarantineBadgeText).
- Pass 2 (2026-10-06): `renderVoiceHub` → `src/components/hub/HubScreen.tsx`; `renderStudio` → `src/components/studio/StudioScreen.tsx` (both `React.FC` + exported prop interfaces; JSX byte-identical via same-name props); shared tab primitives consolidated into `src/theme/appStyles.ts` (`sharedScreenStyles`), EngineRoomScreen switched to it; App pruned 72 style keys (incl. 4 pre-existing dead studio keys) + 6 dead imports. App.tsx **2639 → 1755 → 1045 LOC**.
- Pass 3 (2026-10-06): handler pileup → `src/hooks/useDictionaryKeywords.ts` (dictionary+keyword CRUD), `src/hooks/useSyncToDrive.ts` (Drive sync state/handlers), `src/hooks/useRecordingSim.ts` (sim timers + start/stop; library mutation via onRecordingFinished callback). App.tsx **1045 → 831 LOC** (2639 original, −69%). Remaining (out of split scope): `any[]` transcriptions typing pass; device smoke.

#### New files (split outputs, all verified)

- `src/services/installHttpOverrides.ts`, `src/components/engine/EngineRoomScreen.tsx` (pass 1)
- `src/theme/appStyles.ts`, `src/components/hub/HubScreen.tsx`, `src/components/studio/StudioScreen.tsx` (pass 2)
- `src/hooks/useDictionaryKeywords.ts`, `src/hooks/useSyncToDrive.ts`, `src/hooks/useRecordingSim.ts` (pass 3)

#### Regression found & fixed during pass 2

- `quarantineEgress.test.ts` ×4 TS2345: batch-1's `edits: any[] → EditOperation[]` widened the `basePayload` literal — fixed by annotating `basePayload: SaveCorrectionPayload` (type-only import). Non-env tsc errors now 0.

#### Verification

- `lint:fix`: `not-run` (no lint configured)
- `typecheck`: `pass` (env-error classes only; count 37→25 vs stash-verified baseline, zero new classes)
- `build`: `not-run` (Expo build needs node_modules)
- `test`: `pass` (full 8-suite chain RC=0)
- `test:e2e`: `pass` (geminiE2E 29/29)
- `manual smoke`: `pending` — Engine Room tab must be device-smoked after npm install (no Expo runtime in audit env)

#### Final disposition

- `kept (split pass 1 done; passes 2–3 pending)`

---

### `velavoice app/src/services/GeminiService.ts`

- status: `updated`
- score: `44% → ~57% est.` (`Adequate`)
- placement_status: `correct`
- import_impact: `medium`
- move_reason: `N/A`

#### Findings

- [severity: high] [confidence: high] GeminiService.ts:94-225 vs :512-643 - ~80 LOC duplicated fetch/timeout/AbortController/error branches between testGeminiApiKey and transcribeAudio
- [severity: high] [confidence: high] GeminiService.ts:645-663 - 15+ exports, mixed concerns (storage + network + pcmToWav + parsing)
- [severity: medium] [confidence: high] GeminiService.ts:202,490,620 - `any` (error:any x2, responseJson:any)
- [severity: low] [confidence: high] GeminiService.ts:39-57 - normalizeGeminiModel if-chain, table-driven candidate

#### Improvements applied

- Batch 1: module intent header + shared `toErrorMessage`/`isTimeoutError`/`fetchWithTimeout` helpers, `parseGeminiTranscriptionResponse(unknown)`, both `catch (error:unknown)` narrowed.
- Batch 2: both fetch call-sites migrated to `fetchWithTimeout` (manual controller/timeout blocks + clearTimeout removed — ~40 LOC net reduction, behavior preserved).
- Batch 3 (open-question decision): 404 auto-fallback to gemini-2.0-flash **kept as intentional** — AI Studio retires model aliases without notice; fallback keeps preflight/transcription usable, and the error message when fallback also fails names the replacement. Decision documented in-code at both 404 sites.

#### Verification

- `typecheck`: `pass` (only pre-existing TS2591 Buffer env noise, missing @types/node)
- `test`: `pass` (GeminiService 10/10, geminiE2E 29/29 — re-run after comment edits, RC=0)
- `lint:fix`: `not-run` (no lint configured in package.json)
- `build`: `not-run` (Expo build needs full node_modules)
- `test:e2e`: `pass` (geminiE2E 29/29)

#### Final disposition

- `kept`

---

### `velavoice app/src/services/ModelManager.ts`

- status: `updated`
- score: `46% → ~55% est.` (`Adequate`)
- placement_status: `correct`
- import_impact: `medium`

#### Findings

- [severity: high] [confidence: high] ModelManager.ts:109-351 - god static class: models + dictionary + keywords + corrections + DDL in one file (351 LOC high tier)
- [severity: medium] [confidence: high] ModelManager.ts:112,335,337,345-346 - `any` (getAllAsync<any>, closeAsync casts)
- [severity: low] [confidence: medium] ModelManager.ts:147-150 vs :202-205 vs :215-218 - triplicated INSERT OR REPLACE models statement

#### Improvements applied

- Batch 3: module intent header; `getAllAsync<any>` → `getAllAsync<ModelRow>`; `getCorrections(): Promise<any[]>` → `Promise<CorrectionRow[]>` with exported `CorrectionRow` interface; `(db as any).closeAsync` → structural `{ closeAsync?: () => Promise<void> }` probe; 3 implicit-any callback params annotated (`database`, `r`, `downloadProgress`). Static API preserved byte-identical for callers.

#### Open questions

- Split into models/dictionary/corrections sub-modules? Deferred — static API kept; revisit when a second DB consumer appears.

#### Verification

- `typecheck`: `pass` (no real errors in file)
- `test`: `pass` (api.test, quarantineEgress, e2eCorrectionTracking all green post-change)
- `lint:fix`: `not-run` (no lint configured)
- `build`: `not-run`
- `test:e2e`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/components/GeminiSettings.tsx`

- status: `analyzed`
- score: `53%` (`Adequate`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- [severity: high] [confidence: high] GeminiSettings.tsx:1-544 - 544 LOC critical tier, provider picker + model chips + key manager + test in one component
- [severity: low] [confidence: high] GeminiSettings.tsx:137 - `catch (e:any)`

#### Improvements applied

- None yet — split deferred

#### Verification

- `lint:fix`: `not-run`
- `typecheck`: `not-run`
- `build`: `not-run`
- `test`: `not-run`
- `test:e2e`: `not-run`

#### Final disposition

- `deferred`

---

### `velavoice app/src/components/TranscriptionEditor.tsx`

- status: `updated`
- score: `59% → ~65% est.` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- [severity: medium] [confidence: high] TranscriptionEditor.tsx:61-77 - handleReCleanTranscript duplicates scribeSimulator.buildScribeDrafts + ScribeAI stylePrompts, drifts (e.g. Proofread regex missing)
- [severity: low] [confidence: high] TranscriptionEditor.tsx:21,101 - `edits:any[]`, `err:any`

#### Improvements applied

- Batch 3: replaced 6 sync `ScribeAI.isConfigured` render call-sites with `configuredModels` state hydrated once via `isConfiguredAsync` (SecureStore-aware; default false = fail-closed disabled UI). Fixes the "(off)" bug for users who configured their Gemini key in Engine Settings. Remaining: style-reclean duplication, `edits:any[]` prop typing (deferred to split pass).

#### Verification

- `typecheck`: `pass` (only env TS7031 React.FC noise, also present in untouched components)
- `test`: `pass` (full 8-suite chain RC=0)
- `lint:fix`: `not-run` (no lint configured)
- `build`: `not-run`
- `test:e2e`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/services/ScribeAI.ts`

- status: `updated`
- score: `53% → ~62% est.` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- [severity: medium] [confidence: high] ScribeAI.ts:49-115 - fetch + error parsing duplicates GeminiService; stylePrompts (:24-31) duplicates simulator/editor
- [severity: medium] [confidence: high] ScribeAI.ts:49,83 - rewriteWithGemini/Groq public, should be private
- [severity: medium] [confidence: high] ScribeAI.ts:4-5,117-120 - isConfigured reads build-time Constants, disagrees with SecureStore live key

#### Improvements applied

- Batch 2: `rewriteWithGemini`/`rewriteWithGroq` → `private static`, module intent header added.
- Batch 3 (open-question decision): sync `isConfigured` **removed**, replaced by async `isConfiguredAsync` that reads SecureStore (via `getGeminiApiKey`) plus build-time Constants fresh at call time; fails closed on SecureStore errors. New `ScribeAI.test.ts` (5 cases incl. SecureStore-only key = the bug regression). `catch (error: any)` → `unknown`. Fetch duplication with GeminiService still open (cross-file DRY, deferred).

#### Verification

- `typecheck`: `pass`
- `test`: `pass` (ScribeAI.test 5/5, full 8-suite chain RC=0)
- `lint:fix`: `not-run` (no lint configured)
- `build`: `not-run`
- `test:e2e`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/services/api.ts`

- status: `updated`
- score: `73% → ~78% est.` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- [severity: medium] [confidence: high] api.ts:7,67,84 - `edits:any[]`, `e:any` x2
- [severity: low] [confidence: medium] api.ts:22-34 - requiredFieldError verbose, table-driven candidate

#### Improvements applied

- Batch 1: `edits:any[]` → `EditOperation[]` (import type from utils/editCalculator), `catch (e:any)` → `catch (e:unknown)` with instanceof guard, bare `catch` for JSON parse, module intent header added. Public API preserved.

#### Verification

- `typecheck`: `pass`
- `test`: `pass` (api.test.ts green after Batch 2 hoisting fix)
- `lint:fix`: `not-run` (no lint configured)
- `build`: `not-run`
- `test:e2e`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/services/api.test.ts`

- status: `updated`
- score: n/a (test file)
- placement_status: `correct`

#### Findings

- [severity: high] [confidence: high] api.test.ts:35-36 - static `import './api'` hoisted above mockModule hooks → expo-file-system resolution failed at runtime (pre-existing, documented in LOG.md 2026-10-03)

#### Improvements applied

- Batch 2: converted to runtime `require()` after hooks + `import type` for payload (same pattern as quarantineEgress/GeminiService tests). All API tests pass.

#### Verification

- `test`: `pass`
- `typecheck`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/services/scribeSimulator.ts`

- status: `analyzed`
- score: `77%` (`Good`)
- placement_status: `correct` (candidate move to utils/ deferred — needs approval)
- import_impact: `low`

#### Findings

- [severity: low] [confidence: medium] scribeSimulator.ts:22-35 - spanish/german branches duplicate shape

#### Improvements applied

- None (reference pure pattern — preserve)

#### Verification

- `lint:fix`: `not-run`
- `typecheck`: `not-run`
- `build`: `not-run`
- `test`: `not-run`
- `test:e2e`: `not-run`

#### Final disposition

- `kept`

---

### `velavoice app/src/utils/editCalculator.ts`

- status: `analyzed`
- score: `82%` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- None blocking — generic buildEditTable<T> exemplary; missing module header only

#### Improvements applied

- None (preserve)

#### Verification

- `lint:fix`: `not-run`
- `typecheck`: `not-run`
- `build`: `not-run`
- `test`: `not-run`
- `test:e2e`: `not-run`

#### Final disposition

- `kept`

---

### `velavoice app/src/utils/dictionaryClean.ts`

- status: `analyzed`
- score: `81%` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- None blocking — pure, escaped regex, has header

#### Improvements applied

- None (preserve)

#### Verification

- `lint:fix`: `not-run`
- `typecheck`: `not-run`
- `build`: `not-run`
- `test`: `not-run`
- `test:e2e`: `not-run`

#### Final disposition

- `kept`

---

### `velavoice app/src/utils/quarantineBadge.ts`

- status: `updated`
- score: `99%` (`Excellent`) — preserved
- placement_status: `correct`
- import_impact: `low`

#### Findings

- None — pure/total/null-safe exemplar; now ALSO the single quarantine verdict authority

#### Improvements applied

- Batch 3 (verdict union): `QuarantinableEntry` gained `privacySensitive?`; `isQuarantinedEntry` now checks all three flags (isQuarantined/quarantined/privacySensitive). api.ts's `isQuarantinedPayload` delegates here — payload and badge verdicts can no longer diverge. Zero runtime change for entry callers (no recording carries privacySensitive — grep-verified).

#### Verification

- `typecheck`: `pass`
- `test`: `pass` (quarantineBadge extended +3 cases, api.test, quarantineEgress 9/9, e2eCorrectionTracking green)
- `lint:fix`: `not-run` (no lint configured)
- `build`: `not-run`
- `test:e2e`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/components/RecordingCard.tsx`

- status: `analyzed`
- score: `79%` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- None blocking — memoized, correct quarantine badge use

#### Improvements applied

- None (preserve)

#### Verification

- `lint:fix`: `not-run`
- `typecheck`: `not-run`
- `build`: `not-run`
- `test`: `not-run`
- `test:e2e`: `not-run`

#### Final disposition

- `kept`

---

### `velavoice app/src/components/OverlayLogo.tsx`

- status: `updated`
- score: `78% → ~84% est.` (`Good`)
- placement_status: `correct`
- import_impact: `low`

#### Findings

- [severity: medium] [confidence: high] OverlayLogo.tsx:2,48,64 - unused Platform import, `as any` x2 for transformOrigin

#### Improvements applied

- Batch 1: removed unused Platform import, `as any` → `as ArmStyleWithOrigin` (ViewStyle & { transformOrigin: string }), module intent header added. No visual change.

#### Verification

- `typecheck`: `pass`
- `test`: `pass` (suites importing it green)
- `lint:fix`: `not-run` (no lint configured)
- `build`: `not-run`
- `test:e2e`: `pass`

#### Final disposition

- `kept`

---

### `velavoice app/src/components/ScribePresetManager.tsx`

- status: `removed`
- score: n/a
- placement_status: `correct` (was dead code)
- import_impact: `none`

#### Findings

- [severity: high] [confidence: medium] ScribePresetManager.tsx:1-149 - not imported in App.tsx (dead code)
- [severity: medium] [confidence: high] ScribePresetManager.tsx:45 - `value:any`, Date.now() IDs non-deterministic (:52)

#### Improvements applied

- Batch 3 (open-question decision): **deleted** — grep-verified zero imports anywhere in the app; git history preserves it. Wiring into Engine Room was rejected: it duplicates scribe draft state already living in App/Studio and would add a second preset source of truth.

#### Verification

- `test`: `pass` (full 8-suite chain green post-deletion, zero references)
- `typecheck`: `pass`

#### Final disposition

- `removed (dead code, git-recoverable)`

---

### `velavoice app/src/services/e2eCorrectionTracking.test.ts`

- status: `updated`
- score: n/a (test file)
- placement_status: `correct`

#### Findings

- [severity: high] [confidence: high] e2eCorrectionTracking.test.ts:70-72 - static imports of api/ModelManager/editCalculator hoisted above mockModule hooks (same pre-existing hoisting bug as api.test.ts)

#### Improvements applied

- Batch 2: runtime `require()` after hooks; suite now fully green (5/5 stages incl. python export pipeline).

#### Verification

- `test`: `pass` (requires resolvable `sqlite3` — NODE_PATH=$(npm root -g) in this env; resolves from local node_modules after a normal npm install)

#### Final disposition

- `kept`

---

### `velavoice app/src/services/quarantineEgress.test.ts`

- status: `verified`
- score: n/a (test file)
- placement_status: `correct`

#### Findings

- [severity: high] [confidence: high] quarantineEgress.test.ts - depends on `/tmp/vv-stubs/node_modules/*` env stubs (documented in LOG 2026-10-03) which were missing in this environment → suite failed at baseline

#### Improvements applied

- Batch 2: recreated `/tmp/vv-stubs` stubs (`expo-sqlite` with `__writes`/`__reset` recording + `openDatabaseAsync`, `expo-file-system`, `react-native` with NativeModules). No source change needed — runtime-require pattern already correct.

#### Verification

- `test`: `pass` (9/9)

#### Final disposition

- `kept`

---

### `velavoice app/package.json`

- status: `updated`
- score: n/a (manifest)
- placement_status: `correct`

#### Findings

- [severity: high] [confidence: high] package.json:13 - `test` script ran only 3 of 7 suites (api, quarantineEgress, e2eCorrectionTracking, quarantineBadge excluded)

#### Improvements applied

- Batch 2: `test` now chains all 7 suites in dependency-light order.
- Batch 3: added ScribeAI.test.ts → 8 suites total.

#### Verification

- `test`: `pass` (full chain RC=0 in-env with NODE_PATH for sqlite3)

#### Final disposition

- `kept`
