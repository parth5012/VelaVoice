# Repository Organization Map — VelaVoice (velavoice app scope)

This file is the placement contract for source files.
Update it when folder ownership or dependency boundaries change.

## Global principles

- Prefer small focused files and cohesive modules (<250 LOC warning, >350 high, >500 critical).
- Keep dependency direction one-way across layers.
- Avoid circular dependencies across folders.
- Put shared utilities in explicitly shared locations only.
- Preserve public import/API paths unless explicitly approved.
- Every touched TS module gets a Module intent header (Intent/Responsibilities/Public API/Invariants/Side Effects).

## Folder contracts

### `velavoice app/App.tsx` (composition root)

- Purpose: tab composition + screen wiring only. No business logic, no fetch patching.
- Include: `useState` for activeTab/selection, render delegates to `src/screens/*` (future) or current inline `renderVoiceHub/renderStudio/renderEngineRoom` during transition.
- Exclude: transcription cleaning, Scribe draft building, SQLite, network fetch, Drive sync logic.
- Allowed imports: `src/components/*`, `src/services/*` (facades only), `src/utils/*` (pure), `src/hooks/*` (future).
- Disallowed imports: direct `expo-sqlite`, direct `fetch('https://api.velavoice.com/...')` (must go via `services/api.ts`).
- Ownership notes: thinning in progress — max 2 extractions per pass. Current ~1400 LOC critical, target <500 via screens/hooks split.

### `velavoice app/src/services/*`

- Purpose: infrastructure boundaries + external integrations (network, SQLite, SecureStore, native modules).
- Include: `api.ts` (CorrectionAPI gate), `ModelManager.ts` (SQLite + FileSystem adapter), `GeminiService.ts` (network + SecureStore + audio encoding), `ScribeAI.ts` (rewrite orchestration).
- Exclude: JSX, StyleSheet, component state.
- Allowed imports: `expo-*`, `react-native` NativeModules, `../utils/*` (pure helpers), sibling services via explicit DI.
- Disallowed imports: `../components/*`, global `fetch` monkey-patching.
- Ownership notes: `GeminiService.ts` critical oversized (665 LOC) — extraction target is shared `fetchGemini()` helper inside same folder, no cross-folder move. `ModelManager.ts` high (351 LOC) — future split is `models/` vs `dictionary/` vs `corrections/` sub-modules, deferred.

### `velavoice app/src/components/*`

- Purpose: presentation + local UI state only.
- Include: `TranscriptionEditor`, `GeminiSettings`, `RecordingCard` (memoized), `OverlayLogo`, `ScribePresetManager` (status: possibly dead — confirm before delete).
- Exclude: direct SQLite, direct `fetch`, business-rule duplication (Scribe style maps live in services/utils, not components).
- Allowed imports: `../services/*` (facades), `../utils/*` (pure), `react-native`/`expo-*` UI only.
- Disallowed imports: `expo-sqlite`, `expo-file-system` directly.
- Ownership notes: `GeminiSettings.tsx` critical (544 LOC) — split plan deferred (provider picker vs key manager vs model chips).

### `velavoice app/src/utils/*`

- Purpose: pure, total, easily-tested functions. Reference standard: `quarantineBadge.ts` (98.6%).
- Include: `editCalculator.ts`, `dictionaryClean.ts`, `quarantineBadge.ts`, `scribeSimulator.ts` (pure draft builder — note: misplaced, belongs in `services/` or `utils/`? Currently `services/scribeSimulator.ts`; candidate move to `utils/scribeDrafts.ts` with import-path preservation).
- Exclude: I/O, timers, randomness without injection, JSX.
- Allowed imports: types only (`import type ...`), no runtime deps.
- Disallowed imports: `expo-*`, `react-native`, sibling services.
- Ownership notes: keep total + null-safe; every util needs AAA tests.

### Out-of-scope boundaries (do not move TS into these)

- `sdk/` (Kotlin/Gradle Android libs), `velaboard/` (nested IME repo), `scripts/` (Python), `ui/` (PRD assets), `docs/` (ADRs). TS refactors must not cross into these.

## Placement review checklist

- Does this file match the folder purpose?
- Is it importing only allowed layers?
- Is related code grouped in the same module area?
- Could this module be moved to a narrower scope?

## Move policy

- Prefer local moves over broad tree rewrites.
- Preserve public import paths unless explicitly approved.
- Batch moves in small groups (max 10 files), then run full verification.
- Current deferred moves: `services/scribeSimulator.ts` → `utils/scribeDrafts.ts` (needs approval, breaks 2 imports in App.tsx + tests).

## Change log

- 2026-10-06: Initial map created from audit (App.tsx critical, GeminiService critical, ModelManager high). Session 2026-10-06-repo-quality-cleanup.
