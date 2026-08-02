# Research: How the HeliBoard-vela Fork Consumes Vela SDK Config

**Research ticket:** How does a new "custom shortcuts" feature (voice-triggered text expansion) config reach the Vela SDK from BOTH the Vela Voice app and the HeliBoard fork?

**Date:** 2026-08-02
**Status:** Research notes — forward-looking design. The "custom shortcuts" feature does not yet exist in this repo (see Assumptions).

---

## 1. Source constraint

The HeliBoard fork source at `D:\work\projects\HeliBoard-vela` **does not exist on this machine** and could not be inspected.

- `CONTEXT.md:29` records the fork location, and `CONTEXT.md:33-38` is treated here as the authoritative (but second-hand) description of its current state: SDK dependency `com.velavoice.sdk:vela-core:1.0.0` + `vela-voice-ui:1.0.0` from mavenLocal; Settings → Voice Input Settings with model management, transcription engine mode (local/Groq/OpenAI), LLM cleaner toggle, personal dictionary, language/threads/fillers; `LatinIME.java` checks `isVelaReady()` before showing the Vela pane.
- Everything about the fork's wiring below is therefore an **inference from the Phase 2 blueprint** (`docs/blueprint-sdk-heliboard.md`) plus the actual SDK/app code in this repo. Claims that are inferred are flagged inline.

---

## 2. What config must the fork give the SDK

The SDK's public facade is `VelaTranscriber.Builder` (`vela-core/src/main/kotlin/com/velavoice/sdk/VelaTranscriber.kt:16-57`). The blueprint's Builder surface (`docs/blueprint-sdk-heliboard.md`, §2.2, lines 128-167) is a strict subset of the code that actually shipped. The complete config surface:

| Config | Builder method | Default / Notes | Source |
|---|---|---|---|
| Whisper model file path | `whisperModel(path)` | **Required** — `build()` throws `IllegalStateException("whisperModel() required")` | `VelaTranscriber.kt:26,38`; blueprint §2.2 line 142 |
| Language | `language(lang)` | `"en"` | `VelaTranscriber.kt:27`; blueprint line 143 |
| Threads | `threads(n)` | `4` | `VelaTranscriber.kt:28`; blueprint line 144 |
| LLM cleaner toggle + path | `useLlmCleaner(enable, modelPath)` | `false`, path optional | `VelaTranscriber.kt:29-32`; blueprint lines 145-147 |
| Personal dictionary | `personalDictionary(dict)` | nullable; `PersonalDictionary` interface | `VelaTranscriber.kt:33`; blueprint lines 139, 148 |
| Custom filler list | `customFillers(list)` | nullable | `VelaTranscriber.kt:34`; blueprint line 149 |
| Dictionary keywords (recognition hints) | `dictionaryKeywords(keywords)` | nullable; **added post-blueprint** — not in blueprint §2.2 | `VelaTranscriber.kt:24,35`; `DictionaryKeywords.kt:13-16` |

These collapse into two config objects:

- `WhisperConfig(modelPath, language, numThreads)` — `vela-whisper/src/main/kotlin/com/velavoice/sdk/whisper/WhisperConfig.kt:3-7`; consumed by `WhisperEngine` (`WhisperEngine.kt:6,22-33`), which fails hard if the model file is absent (`WhisperEngine.kt:22-25`).
- `CleanerConfig(useLlm, llmModelPath, personalDictionary, customFillers, dictionaryKeywords)` — `vela-cleaner/src/main/kotlin/com/velavoice/sdk/cleaner/CleanerConfig.kt:3-9`; consumed by `TextCleaner` (`TextCleaner.kt:6-13,26-36`).

**Notes / nuance:**
- The remote transcription modes (Groq/OpenAI) listed in `CONTEXT.md:36` are **not** SDK config. In the Vela app they are handled by direct HTTP calls in `src/native/VoiceAccessibilityService.kt:346-377` (`transcribeWithApi`, lines 641-708) and stored in the app's SharedPreferences. A fork implementing remote mode would do the same as fork-side glue, not via the SDK. (Inference for the fork; directly true for the Vela app.)
- A new "custom shortcuts" feature is exactly the kind of config that would enter the SDK through a new Builder method + a new interface, mirroring how `dictionaryKeywords` was added after the blueprint (see §5).

---

## 3. Where config lives: Vela app (models.db) vs HeliBoard-style (SharedPreferences)

### Vela Voice app — SQLite `models.db` + SharedPreferences

- Database schema is defined in TypeScript, `src/services/ModelManager.ts:37-68` (`getDb()`): tables `models`, `personal_dictionary` (`original_word UNIQUE`, `replacement`, `language`, `priority`), `dictionary_keywords`. Opened via `expo-sqlite` `SQLite.openDatabaseAsync('models.db')` (`ModelManager.ts:39`).
- Model file paths are written by downloads (`ModelManager.ts:178-181`, `INSERT OR REPLACE INTO models … path … status`) and verified by SHA-256 (`ModelManager.ts:155-167`).
- CRUD for dictionary entries and keywords: `ModelManager.getDictionaryEntries` / `addDictionaryEntry` / `deleteDictionaryEntry` (`ModelManager.ts:214-237`) and `getKeywords` / `addKeyword` / `deleteKeyword` (`ModelManager.ts:243-261`).
- The native IME reads the same DB **read-only, cross-process, by opening the file directly**: `src/native/VoiceInputMethodService.kt:298-311` (`loadModelPaths()`), `326-356` (`loadPersonalDictionary()` — SQL `SELECT original_word, replacement FROM personal_dictionary ORDER BY priority DESC, name ASC`), `359-388` (`loadDictionaryKeywords()`), via `findDatabaseFile()` which probes `context.getDatabasePath("models.db")`, `filesDir/SQLite/models.db`, `filesDir/databases/models.db` (`VoiceInputMethodService.kt:390-402`). The same pattern exists in `src/native/VoiceAccessibilityService.kt:404-430,531-543,545-581`.
- Non-DB settings (LLM cleaner toggle, transcription mode, API keys, endpoints) live in SharedPreferences `"com.velavoice.app_preferences"`: written/read by `src/native/ModelVerifierModule.kt:86-108,149-165` and read by `VoiceInputMethodService.kt:182-184` and `VoiceAccessibilityService.kt:346-347,400`.
- Storage architecture decision: `docs/adr/0002-model-storage-and-access-architecture.md` — models downloaded via the Expo layer into Scoped Storage, tracked with SQLite **and Shared Preferences**; Kotlin services retrieve paths from storage. The TypeScript `ModelManager.ts` explicitly stays in the Expo app (blueprint Appendix, lines 576-585).

### HeliBoard-vela fork — SharedPreferences (per blueprint)

- The blueprint's Phase 2 settings screen, `docs/blueprint-sdk-heliboard.md` §3.2 File 5 (lines 429-461), defines a `VoiceSettingsFragment` with a `voice_settings.xml` `PreferenceScreen`:
  - `whisper_model_path` (EditTextPreference, default `/sdcard/Models/ggml-tiny.en.bin`)
  - `use_llm_cleaner` (SwitchPreference)
  - `llm_model_path` (EditTextPreference)
  - The comment at blueprint lines 434-438 states the path is "Store[d] in SharedPreferences" and "KeyboardSwitcher reads this when building VelaTranscriber."
- `KeyboardSwitcher.showVelaVoicePane()` constructs the `VelaTranscriber.Builder` (blueprint §3.2 File 3, lines 392-401) with `loadWhisperModelPath()` commented as "load path from settings/preferences" (blueprint line 395).
- `CONTEXT.md:36` confirms the fork's real settings surface is broader (transcription engine mode, personal dictionary, language/threads/fillers), but the **storage mechanism per the blueprint is the fork's own SharedPreferences**, not SQLite. (Confirmation of the actual keys/storage in the fork requires the fork source — Assumption A3.)

### Can Vela-app `models.db` shortcuts be reached from the fork?

**No, not directly — this is the key constraint.** Android sandboxes each app's private storage:

- `context.getDatabasePath("models.db")` (`VoiceInputMethodService.kt:392`) resolves to the **Vela Voice app's** private data dir. A separate app (the fork) runs under a different package name and UID and cannot open that file; it would hit a permissions/SELinux denial.
- Cross-app SharedPreferences via `MODE_WORLD_READABLE` is deprecated since API 17 and **removed in API 24** (minSdk 23 per `CONTEXT.md:34`; fork targets NDK 27.1 / compileSdk 35) — not a viable bridge.
- The blueprint already assumes the fork manages **its own** model storage: §3.3 "Model Management for HeliBoard Users" tiers even propose *reusing Vela's ModelManager logic in Kotlin* inside the fork (blueprint lines 465-473), implying fork-local storage, not cross-app reads.
- Consequence: if the Vela app stores shortcuts in `models.db` (`personal_dictionary` table today, or a future `shortcuts` table), the fork cannot consume them by reading that DB. Any sharing must go through an explicit cross-app mechanism or duplicated storage.

**Bridging options (inference/recommendation, §7):**
1. **ContentProvider** in the Vela Voice app exposing dictionary/shortcut entries, protected by `android:exported` + `signature` permission so only the fork (same signing key) can read; the fork's `PersonalDictionary`/`ExpansionDictionary` implementation queries it. This preserves a single source of truth in the Vela app.
2. **Export/import via Storage Access Framework** (SAF) — user-initiated, no signature requirement, but manual and stale.
3. **Duplicated storage** — fork keeps its own dictionary/shortcut settings in its own SharedPreferences/DB (matches the blueprint's own-configuration design), giving users two separate dictionaries.

---

## 4. PersonalDictionary interface → mirroring/extending for shortcut expansions

The current substitution machinery is already a perfect fit for "trigger word → expanded text":

- `interface PersonalDictionary { fun getEntries(): List<Pair<String, String>> }` — `vela-cleaner/src/main/kotlin/com/velavoice/sdk/cleaner/PersonalDictionary.kt:3-6`; blueprint §2.2 (lines 191-201) notes it "Replaces SQLite Dependency."
- `TextCleaner.applyPersonalDictionary` (regex path) applies each pair **in returned order**, **case-insensitive**, on **word boundaries** (`TextCleaner.kt:78-92`): `Regex("(?i)\\b" + Regex.escape(original) + "\\b")` → replace with expansion.
- Entry ordering comes from the DB in the Vela app: `ORDER BY priority DESC, name ASC` (`ModelManager.ts:217`, `VoiceInputMethodService.kt:334`). The interface doc says "Applied in order" (`PersonalDictionary.kt:4`); `TextCleaner.kt:82` iterates in the returned order. Priority therefore matters for overlapping triggers.
- The separate `DictionaryKeywords` interface (`DictionaryKeywords.kt:13-16`) is the *recognition-hint* analog (words to preserve, not to replace) — a useful conceptual distinction for design: a shortcut is a **replacement**, a keyword is a **preservation hint**.

**Mirror/extend options for a shortcut-expansion config:**
- **(a) Reuse `PersonalDictionary` as-is.** Shortcuts are just `original_word = spoken trigger`, `replacement = expansion text`. Zero SDK change; the fork's implementation reads its own storage. Downside: no place to express ordering/priority or "only when spoken as a standalone phrase" semantics beyond the existing word-boundary behavior.
- **(b) New `ExpansionDictionary` interface in the SDK** (vela-cleaner), mirroring the `PersonalDictionary` shape, e.g. `getEntries(): List<ExpansionEntry>` where `ExpansionEntry(trigger: String, expansion: String, priority: Int = 1)`. Wire it through `CleanerConfig` + a `VelaTranscriber.Builder.expansionDictionary(...)` method (exactly how `dictionaryKeywords` was added post-blueprint: `VelaTranscriber.kt:24,35` vs blueprint lines 139-140). `TextCleaner` applies expansions in the same substitution step (`TextCleaner.kt:78-92`).
- (c) **Generalize** into a single provider interface (e.g. `DictionaryProvider`) exposing both corrections and expansions — cleaner API, but a breaking change to the already-consumed `PersonalDictionary` surface (`CONTEXT.md:35` says the fork consumes `vela-core:1.0.0` + `vela-voice-ui:1.0.0`), so additive (b) is safer for an in-the-field fork.

Because expansion is currently a **string-level post-Whisper substitution**, "voice-triggered" works via the transcription of the trigger word. The fork inserts `result.getCleanedTranscript()` (`blueprint` File 3, lines 406-408; Vela IME commits `cleanedTranscript` at `VoiceInputMethodService.kt:223-236`) — so the SDK already applies dictionary replacements before the text reaches `InputConnection.commitText()`.

---

## 5. Licensing / boundary — config ownership

- Boundary contract: `docs/blueprint-sdk-heliboard.md` §4 (lines 507-516): SDK is **Apache 2.0 / MIT**, HeliBoard fork is **GPL-3.0** (required by upstream HeliBoard/AOSP), `whisper.cpp` and `ggml` are MIT. "**Critical:** The SDK `.aar` must be a *separate* artifact. Do not copy SDK sources into the fork's source tree." (blueprint line 516).
- Dependency relationship: the fork consumes the SDK as a binary dependency — `implementation("com.velavoice:vela-transcription-sdk:1.0.0")` (blueprint §3.1, line 349; `CONTEXT.md:35` confirms `com.velavoice.sdk:vela-core:1.0.0` from mavenLocal). Risk register also mandates `-keep class com.velavoice.sdk.**` in the fork's ProGuard rules (blueprint line 529).
- **Config ownership:** the SDK owns *no* config. It only consumes whatever the host app passes into `VelaTranscriber.Builder` / `CleanerConfig`. Each app owns and stores its own config (Vela app: `models.db` + `"com.velavoice.app_preferences"`; fork: its own SharedPreferences per blueprint §3.2 File 5).
- **Interface boundary:** interfaces like `PersonalDictionary` (and a future `ExpansionDictionary`) are declared in the Apache-licensed SDK and *implemented by GPL code* in the fork (adapters over fork storage). That is a consumer relationship, not derivative works merged into the SDK — the GPL code stays in the fork layer, the SDK `.aar` stays separate, so the clean license boundary in blueprint §4 is preserved. A new SDK interface does not change this: it lives in `vela-cleaner` (Apache), the fork's implementation lives in the fork (GPL).
- Practical implication: a shortcut-expansion feature is safe to add to the SDK; the fork adds only glue (settings UI + an implementation of the interface). Do not move the fork's settings/DB code into the SDK, and do not vendor SDK sources into the fork — both would cross the boundary the blueprint explicitly draws.

---

## 6. Concrete recommendation

**Recommended pattern: additive `ExpansionDictionary` interface in the SDK, per-app implementations, fork loads it at `KeyboardSwitcher` build time.**

1. **SDK (vela-cleaner, Apache):** add
   - `interface ExpansionDictionary { fun getEntries(): List<ExpansionEntry> }` (mirror `PersonalDictionary.kt:3-6`)
   - `data class ExpansionEntry(val trigger: String, val expansion: String, val priority: Int = 1)`
   - Add `expansionDictionary: ExpansionDictionary? = null` to `CleanerConfig` (`CleanerConfig.kt:3-9`)
   - Add `fun expansionDictionary(d: ExpansionDictionary)` to `VelaTranscriber.Builder` and pass through in `build()` (`VelaTranscriber.kt:24,35,37-56`)
   - `TextCleaner.applyPersonalDictionary` (`TextCleaner.kt:78-92`) applies expansions in the same substitution step, in priority/return order.
   - This mirrors the proven post-blueprint addition of `dictionaryKeywords` — additive, no breaking change to `vela-core:1.0.0` consumers (`CONTEXT.md:35`).

2. **Vela Voice app:** add a `shortcuts` (or `text_expansions`) table to the `getDb()` schema (`ModelManager.ts:40-63`) + CRUD methods, mirroring `personal_dictionary` (`ModelManager.ts:214-261`). Optionally expose read access via a **signature-permission ContentProvider** if the fork should share the same data (option 1 in §3).

3. **HeliBoard-vela fork (GPL):** the fork implements `ExpansionDictionary` against **its own** SharedPreferences (per blueprint §3.2 File 5 pattern — extend `voice_settings.xml` with a shortcut list preference, extend `VoiceSettingsFragment` accordingly). It wires the implementation at the same place the SDK is already constructed: `KeyboardSwitcher.showVelaVoicePane()` where `VelaTranscriber.Builder` is built (blueprint §3.2 File 3, lines 392-401), alongside `.whisperModel(...)`, `.language(...)`, `.threads(...)`, and the LLM-cleaner toggle read from settings. If the fork must reflect Vela-app data instead, replace the SharedPreferences-backed implementation with a ContentProvider-backed one (same interface, different backing — the interface isolates the swap).

4. **Where the expansion output lands:** no extra hop. `VelaTranscriber.transcribe()` runs `textCleaner.clean(raw)` and returns `cleanedTranscript` (`VelaTranscriber.kt:60-65`); both hosts commit `cleanedTranscript` to the target field (blueprint File 3 lines 406-408; `VoiceInputMethodService.kt:223-236`). Expansions therefore reach the input field through the existing pipeline.

5. **Model path caveat for the fork:** the same settings path that supplies `whisperModel(...)` must be verified before building — `WhisperEngine` throws if the file is missing (`WhisperEngine.kt:22-25`) and the fork's `isVelaReady()` model-existence check (`CONTEXT.md:37`) should gate shortcut wiring too.

---

## 7. Assumptions, inferences & open questions

**Assumptions / inferences (fork source unavailable):**
- A1. The fork's config storage is the fork's own SharedPreferences, as specified by blueprint §3.2 File 5; the exact real keys and whether a personal-dictionary UI already ships in the fork (`CONTEXT.md:36` says personal dictionary is in the settings list) could not be verified.
- A2. The fork builds `VelaTranscriber` in `KeyboardSwitcher.showVelaVoicePane()` as in blueprint §3.2 File 3; actual class layout of the fork (HeliBoard 0.x) may differ.
- A3. "Custom shortcuts" is a **net-new feature** — the only "shortcut" string in this repo is HeliBoard's system-voice fallback `switchToShortcutIme` in blueprint line 373, which is unrelated. This document is therefore design research, not a description of existing behavior.
- A4. Cross-app reachability conclusions (§3) follow from the Android platform sandbox model, not from anything visible in this repo's code.

**Open questions:**
- Q1. Should shortcut data be a single source of truth in the Vela app (ContentProvider) or duplicated in the fork (separate settings)? The blueprint's own-configuration design favors duplication; a shared UX favors the provider.
- Q2. Should expansions be applied to the raw transcript, the cleaned transcript, or both? Today the pipeline only exposes the cleaned path (`VelaTranscriber.kt:60-65`); "Stop Raw" inserts the raw text in the Vela IME (`VoiceInputMethodService.kt:113,259-264`), which would bypass expansions.
- Q3. Does the fork's `minSdk 23` (`CONTEXT.md:34`) plus blueprint target `minSdk 26` (`blueprint` line 281) conflict on any SDK feature? None observed; recorded only for completeness.
- Q4. Should `ExpansionDictionary` fold into `PersonalDictionary` (option a) to avoid a second interface, or stay additive (option b)? Additive is recommended for ABI stability with the shipped `vela-core:1.0.0`.
