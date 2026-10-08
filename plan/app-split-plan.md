# App.tsx Split Plan (repo-quality audit, 2026-10-06)

Status: `pass-1 done` (2026-10-06) — `installHttpOverrides.ts` + `EngineRoomScreen.tsx`
extracted; App.tsx 2639 → 1755 LOC. Passes 2–3 pending approval.
Per skill constraints each implementation pass extracts **at most 2 units** and
re-verifies (tsc filter + full `npm test` chain + manual smoke of the touched tab).

## Current shape

- `velavoice app/App.tsx` — **2639 LOC** (audit baseline said ~1400; file grew).
- ~40 `useState` hooks in one component; single god component `App`.
- Section map:
  | Lines | Content |
  |---|---|
  | 34–133 | State pileup (models, engine settings, dictionary, keywords, sync, recordings, studio, scribe) |
  | 134–167 | Global `fetch` patch (quarantine save_correction interception) |
  | 168–659 | Handlers: dictionary CRUD, keywords, accessibility/LLM toggles, model download/delete, recording sim, transcript edit, correction save, Drive sync |
  | 660–706 | `renderHub` — Voice Hub / Your Library FlatList + quarantine badges |
  | 707–984 | `renderStudio` — cleaned/raw segments, scribe drafts |
  | 985–1475 | `renderEngineRoom` — settings, keys, dictionary, keywords, models, sync |
  | 1476–1546 | Main return: tab bar + tab switch |
  | ~1560–2639 | `StyleSheet` (~1000 LOC of styles) |

## Target end-state

```
velavoice app/App.tsx                      (~300 LOC: state wiring + tab shell)
velavoice app/src/components/hub/HubScreen.tsx
velavoice app/src/components/studio/StudioScreen.tsx
velavoice app/src/components/engine/EngineRoomScreen.tsx
velavoice app/src/services/installHttpOverrides.ts
velavoice app/src/hooks/useDictionary.ts
velavoice app/src/hooks/useKeywords.ts
velavoice app/src/hooks/useSyncToDrive.ts
velavoice app/src/hooks/useRecordingSim.ts
```

Styles move with their screen (colocation). Cross-screen shared tokens stay
in App or a future `theme.ts` only when a second consumer appears.

## Extraction passes (each ≤2 units, verify after each)

### Pass 1 — lowest risk, pure moves ✅ DONE 2026-10-06
1. **`installHttpOverrides.ts`** ✅: extracted the `fetch` patch as
   `installHttpOverrides(): void`, called once from App mount effect.
   Idempotent via `realFetch` guard (same as before).
2. **`EngineRoomScreen.tsx`** ✅: extracted `renderEngineRoom` body + all
   engine-only styles (colocated). Props contract:
   `EngineRoomScreenProps` (state values + `updatePreference` + real state
   setters `setTranscriptionMode` etc. + handler callbacks +
   `onGeminiApiKeyChange`). The 5 generic styles shared with Hub/Studio
   (tabContent/hubTitle/studioSubtitle/sandboxResult*) are colocated copies —
   consolidate into a theme module in Pass 2. Removed 67 engine-only style
   keys + dead imports (Switch, Dimensions, CorrectionAPI, quarantineBadgeText)
   from App. Verification: tsc env-error count 37→25 (all classes pre-existing
   environmental), full 8-suite chain RC=0. Manual device smoke still pending
   (no Expo runtime in this env — no node_modules).

### Pass 2 — screens
3. **`HubScreen.tsx`**: extract `renderHub` (:660–706) + hub styles.
   Props: `{ recordings, unsyncedCount, onOpenRecording }`.
4. **`StudioScreen.tsx`**: extract `renderStudio` (:707–984) + studio styles.
   Props: `{ studioSegment, scribeDrafts, selectedDraft, ... }`.

### Pass 3 — handler hooks (one hook per pass if any doubt)
5. `useDictionary` + `useKeywords` (they share `ModelManager` and are
   self-contained CRUD).
6. `useSyncToDrive` (Drive sync state + handler).
7. `useRecordingSim` (sim recording timers + amplitudes).

## Rules for every pass

- Props-only extraction first; no global state/context introduction.
- No behavior change — pure moves + prop wiring. Screens are
  `React.FC<ScreenProps>` with exported prop interfaces.
- Each pass: `tsc` filtered to touched files (ignore env TS2307/TS7031
  noise from missing node_modules types), full `npm test` chain RC=0,
  manual smoke of the extracted tab.
- Stop on any red test — report, never auto-fix.

## Out of scope / parked

- `scribeSimulator.ts` already extracted (keep as reference shape).
- Unifying `Recordings` type (audit: `any[]` at :85) — separate typing pass
  after Pass 2 so screen props get real types in one go.
- Engine Room settings forms → dedicated settings components only if a
  second consumer (e.g. desktop) appears.
