# VelaBoard Voice-UX Batch 2 — "Privacy shield + share + feedback"

Created: 2026-10-09
Status: `planned` → tracked as a wayfinder map (see below)
Origin: bulk grilling on 2026-10-09 (remainder of Tier 1 from `plan/features-research.md`, after fact-checks dropped already-built features)

**Wayfinder map:** [#156 VelaBoard Voice-UX Batch 2 — privacy shield + share + feedback (4.2)](https://github.com/parth5012/VelaVoice/issues/156) (label `wayfinder:map`). Implementation proceeds via its AFK task tickets; this doc is the durable record of the locked decisions.

## Destination

VelaBoard **4.2** ships three features — airgap killswitch + status shield, share-sheet audio transcriber, streaming waveform in the voice pane — each unit-tested, device-smoked on hardware by the maintainer, then cut as a signed production release (`velaboard-v4.2`). The spoken-emoji lexicon extension lands on Batch 1's map as a follow-up ticket.

## Locked decisions (bulk grilling — 2026-10-09)

| # | Decision | Answer |
|---|----------|--------|
| 1 | Batch 2 composition | Airgap killswitch + status shield, share-sheet audio transcriber, streaming waveform + haptics (voice pane). Spoken emoji folds into Batch 1 lexicon as follow-up ticket on map #147. Deferred to Batch 3: live translation (`task=translate`), incognito dictation (naming collision with HeliBoard typing-incognito + storage redesign), `initial_prompt` biasing, Quick Settings PTT tile. |
| 2 | Sequencing vs Batch 1 | Chart Batch 2 map now; **wiring tickets blocked on Batch 1's #154** (shared `KeyboardSwitcher`/session files). Share-sheet track is the exception (new activity, near-zero overlap) — starts immediately. |
| 3 | Killswitch depth | **Hard gate at the transcription/cloud-client layer** (all 4 cloud modes + Drive sync refuse when airgapped; local Whisper unaffected) + **shield chip** in voice pane/toolbar: cyan = on-device only, amber = cloud enabled. NOT a process-wide network kill. |
| 4 | Share-sheet output UX | New `ShareTranscribeActivity` (`ACTION_SEND` audio/*) → local Whisper → result dialog with **Copy / Share-as-text / Save** (into existing `TranscriptionStorage`). No auto-insert back into originating app; no VelaVoice deep-link as primary. |
| 5 | Waveform scope | **Voice pane only**, amplitude from existing recorder/transcriber callbacks; visual-only in v1 (haptics already covered by Batch 1's PTT ticket #151). |
| 6 | Release + companion | Same playbook as Batch 1: per-feature debug APKs → maintainer device smoke → signed **VelaBoard 4.2**. Kotlin-only; zero TS-companion changes. |

## Fact-check outcomes that shaped this batch

- **Per-app Scribe presets already exist** (`KeyboardSwitcher` resolves per-app style override in `buildVelaTranscriber`) — dropped from planning
- HeliBoard has a pre-existing typing-incognito concept — blocked "incognito dictation" from S-effort status
- Quarantine already auto-routes per-field via `isPrivacySensitiveEditor(editorInfo)`; airgap killswitch is the global override on top

## Explicit non-goals (v1 of this batch)

- Process-wide network kill (breaks model downloads, over-invasive for an IME)
- Waveform outside the voice pane; haptics in the waveform feature
- Auto-insert of share-transcripts into the originating app
- Any TypeScript companion (`velavoice app/`) changes

## Map tickets (AFK handover-ready)

| Ticket | Title | Blocked by |
|--------|-------|------------|
| [#157](https://github.com/parth5012/VelaVoice/issues/157) | Airgap gate: pref + gate helper + unit tests | — (frontier) |
| [#158](https://github.com/parth5012/VelaVoice/issues/158) | Airgap: wire gate into cloud/Drive sites + shield chip | #157 + Batch 1 #154 |
| [#159](https://github.com/parth5012/VelaVoice/issues/159) | Share-sheet transcriber: activity + pipeline + result dialog | — (frontier, immediate) |
| [#160](https://github.com/parth5012/VelaVoice/issues/160) | Waveform: amplitude logic + voice pane render | Batch 1 #154 |
| [#161](https://github.com/parth5012/VelaVoice/issues/161) | Batch 2 integration: debug APKs + smoke checklist | #158, #159, #160 |
| [#162](https://github.com/parth5012/VelaVoice/issues/162) | VelaBoard 4.2 release cut (post-smoke) | #161 + sign-off (HITL) |
| [#163](https://github.com/parth5012/VelaVoice/issues/163) (on map #147) | Spoken emoji lexicon extension | #148 |

Handover: each ticket body carries locked constraints, file paths, acceptance criteria, and env/verification notes. Frontier now: #157, #159 (+ #163 on map #147). #158/#160 wait on Batch 1's #154.

## Decision log

| Date | Decision |
|------|----------|
| 2026-10-09 | Bulk grilling completed; 6 decisions locked (table above). Per-app Scribe presets confirmed already-built and dropped. |
| 2026-10-09 | Map + AFK tickets charted on GitHub; follow-up emoji ticket added to Batch 1 map #147. |
| 2026-10-09 | Ticket numbers landed #157–#163 (emoji = #163, not pre-referenced #157); cross-refs in ticket bodies + both map bodies fixed; map bodies restored after a gh-stdin mishap briefly blanked them. |
