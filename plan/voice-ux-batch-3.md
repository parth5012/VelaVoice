# VelaBoard Voice-UX Batch 3 — "Translate + accuracy + anywhere dictation"

Created: 2026-10-10
Status: `planned` → tracked as a wayfinder map (see below)
Origin: bulk grilling on 2026-10-10 (graduated from Batch 2 fog in `plan/voice-ux-batch-2.md`; candidates from `plan/features-research.md` Tier 1/2)

**Wayfinder map:** [#165 VelaBoard Voice-UX Batch 3 — translate + accuracy + anywhere dictation](https://github.com/parth5012/VelaVoice/issues/165) (label `wayfinder:map`). Implementation proceeds via AFK task tickets; this doc is the durable record of the locked decisions.

## Destination

VelaBoard ships three features — live translation (Whisper `task=translate`), `initial_prompt` biasing from personal dictionary, Quick Settings PTT tile — each unit-tested and device-smoked, then cut as a signed production release. Release version decided at build time based on what's current.

## Locked decisions (bulk grilling — 2026-10-10)

| # | Decision | Answer |
|---|----------|--------|
| 1 | Batch composition | Live translation + `initial_prompt` biasing + Quick Settings PTT tile. **Incognito dictation deferred** — needs naming disambiguation from HeliBoard typing-incognito + storage redesign; separate grilling session. |
| 2 | Translation scope | Whisper built-in `task=translate` (always outputs English). Supported source languages v1: **English, Hindi, Punjabi**. Language picker in Voice Settings. Note: English→Hindi/Punjabi output would require a different model/approach — out of scope v1. |
| 3 | initial_prompt source | Feed personal dictionary entries (user words + names) into Whisper's `initialPrompt` parameter. JNI already accepts it (`whisper-jni.cpp`); plumbing only. |
| 4 | QS tile output | **Clipboard** — dictated text lands on clipboard when no IME field context. Progress via notification. |
| 5 | Sequencing | Can proceed once Batch 2 ships (shares `VelaStreamingSession`/`KeyboardSwitcher` surface). Chart now; wiring tickets note Batch 2 dependency where applicable. |
| 6 | Release vehicle | Version decided at build time — check what's current on the day, choose on the moment. |

## Architecture facts (verified 2026-10-10)

- **Translate:** `whisper-jni.cpp` line 42 hardcodes `params.translate = false` — flipping this to a parameter is the entire native change. Stack: JNI → `WhisperEngine.transcribe()` → `WhisperConfig` → `LocalStreamingTranscriber` → `StreamingPipeline` → `VelaStreamingSession` → settings UI.
- **initial_prompt:** `WhisperEngine.transcribe(audioBytes, initialPrompt)` already accepts the param; JNI passes it through. Need: dictionary→prompt composer in the SDK layer + pipeline wiring to supply it per-session.
- **QS tile:** No existing `TileService` in the codebase. Current dictation pipeline is IME-session-bound (`VelaStreamingSession` via `KeyboardSwitcher`). Tile needs a decoupled background path: `TileService` → recording service → `LocalStreamingTranscriber` → clipboard. No IME field context.
- **Personal dictionary:** Stored via Android's `UserDictionary` / HeliBoard's own dictionary system. The SDK `TextCleaner` already reads it for cleanup — reuse that access pattern for prompt composition.

## Explicit non-goals (v1)

- Incognito dictation (deferred — separate effort)
- English→Hindi/Punjabi translation output (Whisper translate is →English only)
- Wake word / hands-free mode (Tier 2, XL)
- Any TypeScript companion (`velavoice app/`) changes
- Multi-language UI translation (app strings stay English)

## Map tickets (AFK handover-ready)

| Ticket | Title | Blocked by |
|--------|-------|------------|
| [#173](https://github.com/parth5012/VelaVoice/issues/173) | Live translation: translate flag + language picker (en/hi/pa) | — (frontier) |
| [#174](https://github.com/parth5012/VelaVoice/issues/174) | initial_prompt biasing: dictionary→prompt composer + wiring | — (frontier) |
| [#175](https://github.com/parth5012/VelaVoice/issues/175) | QS PTT tile: TileService + background dictation + clipboard | — (frontier) |
| [#176](https://github.com/parth5012/VelaVoice/issues/176) | Batch 3 integration: debug APKs + smoke checklist | #173, #174, #175 |
| [#177](https://github.com/parth5012/VelaVoice/issues/177) | Release cut (post-smoke, version TBD) | #176 + HITL sign-off |

Handover: each ticket body carries locked constraints, file paths, acceptance criteria, env/verification notes.

## Decision log

| Date | Decision |
|------|----------|
| 2026-10-10 | Bulk grilling completed; 6 decisions locked (table above). Incognito dictation deferred. Translation targets: En/Hi/Pa source → English output. |
| 2026-10-10 | Architecture verified: translate flag is a JNI param flip (line 42 of whisper-jni.cpp); initial_prompt already plumbed through JNI; QS tile needs new TileService + decoupled pipeline. |
| 2026-10-10 | Map #165 + 5 task tickets (#173–#177) charted on GitHub; blocking via body convention + tasklist; frontier = #173/#174/#175. |
