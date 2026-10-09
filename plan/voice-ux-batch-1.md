# VelaBoard Voice-UX Batch 1 — "Dictation feels controllable"

Created: 2026-10-09
Status: `planned` → tracked as a wayfinder map (see below)
Origin: grilling session on 2026-10-09 (cut from `plan/features-research.md` Tier 1)

**Wayfinder map:** [#147 VelaBoard Voice-UX Batch 1 — dictation control (4.1)](https://github.com/parth5012/VelaVoice/issues/147) (label `wayfinder:map`). Implementation proceeds via its AFK task tickets; this doc is the durable record of the locked decisions.

## Map tickets (AFK handover-ready)

| Ticket | Title | Blocked by |
|--------|-------|------------|
| [#148](https://github.com/parth5012/VelaVoice/issues/148) | Spoken commands: lexicon module + unit tests | — (frontier) |
| [#149](https://github.com/parth5012/VelaVoice/issues/149) | Spoken commands: final-pass wiring + settings toggle | #148 |
| [#150](https://github.com/parth5012/VelaVoice/issues/150) | Spacebar PTT: gesture state machine + unit tests | — (frontier) |
| [#151](https://github.com/parth5012/VelaVoice/issues/151) | Spacebar PTT: transcriber wiring + ptt_spacebar pref | #150 |
| [#152](https://github.com/parth5012/VelaVoice/issues/152) | Undo pill: commit snapshot/restore logic + unit tests | — (frontier) |
| [#153](https://github.com/parth5012/VelaVoice/issues/153) | Undo pill: strip pill UI + dismissal rules | #152 |
| [#154](https://github.com/parth5012/VelaVoice/issues/154) | Batch integration: debug APKs + device-smoke checklist | #149, #151, #153 |
| [#155](https://github.com/parth5012/VelaVoice/issues/155) | VelaBoard 4.1 release cut (post-smoke) | #154 + maintainer sign-off (HITL) |

Handover: each ticket body carries locked constraints, file paths, acceptance criteria, and env/verification notes — a fresh agent can claim and execute it autonomously. Frontier now: #148, #150, #152 (parallel-safe).

## Destination

VelaBoard **4.1** ships three features — spoken punctuation/edit commands, spacebar push-to-talk, single-level undo pill — each unit-tested, device-smoked on hardware by the maintainer, then cut as a signed production release (`velaboard-v4.1`).

## Locked decisions (from grilling — 2026-10-09)

| # | Decision | Answer |
|---|----------|--------|
| 1 | Batch composition | Spoken punctuation/edit commands + spacebar PTT + undo pill (filler stripping dropped — already covered by SDK `TextCleaner` + `PREF_VELA_CUSTOM_FILLERS`) |
| 2 | Command logic locus | Deterministic post-process lexicon in the keyboard session layer, applied to raw ASR text **before** the SDK `TextCleaner`; works uniformly across all 5 transcription modes; zero model/API changes |
| 3 | PTT binding + conflict policy | Long-press space ≥350ms = PTT; horizontal swipe wins if it crosses cursor threshold first; suspends space-long-press language switch (globe key unaffected); pref `ptt_spacebar` in Voice Settings, **default ON**; haptic + existing recording indicator |
| 4 | Undo pill surface + scope | Single-level "↶ Undo" chip in suggestion strip (pinned-keys path, like VOICE key) after every transcription insert / final replace / Scribe rewrite; tap = restore pre-commit text of that region; auto-dismiss on next keypress, supersession, or 5s |
| 5 | Streamed-mode behavior | Commands + undo pill apply on the **final pass only** (one shared code path for instant + streamed modes; no mid-stream rendering) |
| 6 | Verification + release | Per-feature Kotlin unit tests for pure logic → `assembleDebug` → maintainer device smoke (2+ apps, local + ≥1 cloud mode; PTT coexistence; undo edge cases) → fixes → `versionName` 4.1, signed `assembleRelease`, GitHub release `velaboard-v4.1` |

## Explicit non-goals (v1)

- Mid-stream command rendering in streamed mode
- Multi-level undo history
- Filler/self-correction work (SDK cleaner covers fillers; self-correction audit is a separate half-day track)
- Whisper `initial_prompt` biasing
- Any TypeScript companion (`velavoice app/`) changes

## Architecture facts (verified during grilling)

- All live dictation is Kotlin in `velaboard/` — IME path: `VelaStreamingSession`, `KeyboardSwitcher`, `VelaTranscriber`; TS companion is not on the dictation path
- Streaming final pass already runs SDK `com.velavoice.sdk.cleaner.TextCleaner` (fillers + personal dictionary + Scribe + LLM) configured via `VelaStreamingSession.CleanupSpec`
- Voice key exists in suggestion strip (tap = record toggle, long-press = force Scribe); strip supports dynamic pinned-keys (`ToolbarKey.VOICE` visibility toggling)
- Space long-press default = language change (`PREF_SPACE_TO_CHANGE_LANG = true`); space swipe = touchpad cursor (`TouchpadHandler`)
- Environment: no `node_modules`; Kotlin tests via Gradle; no emulator — device smoke is human-only; `ANDROID_HOME=$HOME/Android/Sdk` must be exported for Gradle

## Deferred tracks (separate efforts)

- Upstream HeliBoard sync (45 commits behind; shortlist in `plan/features-research.md`) → its own wayfinder map, later
- Tier 2 differentiators (dual-pass ASR, quantized Scribe, initial_prompt biasing, privacy vault pieces)

## Decision log

| Date | Decision |
|------|----------|
| 2026-10-09 | Grilling completed; 6 decisions locked (table above). |
| 2026-10-09 | User ordered wayfinder map with AFK implementation tickets via gh CLI; execution carried into the map (effort override). |
| 2026-10-09 | Map #147 + 8 task tickets (#148–#155) created on GitHub; blocking via body convention + tasklist (repo lacks sub-issues API); frontier = #148/#150/#152. |
