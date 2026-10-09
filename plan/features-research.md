# VelaBoard Feature Research — Cross-Competitive Scan

Created: 2026-10-09
Status: `researched` → awaiting prioritization decisions
Method: 5 parallel web-research agents (competitors / Android IME platform / on-device ASR-LLM / privacy-first AI / dictation productivity) → cross-cut synthesis. Raw outputs retained in agent transcripts; this doc is the durable distillation.

VelaBoard baseline: HeliBoard fork (`helium314.keyboard`), streaming on-device Whisper-tiny, personal dictionary + privacy quarantine, Drive sync, cloud fallbacks (Groq/OpenAI/Gemini), experimental Llama-3 "Scribe" rewrite, companion VelaVoice (Expo).

---

## Scoring

- **Fit**: alignment with VelaBoard's on-device/privacy positioning (High/Med/Low)
- **Demand**: how many independent sources/agents surfaced it (convergence = stronger signal)
- **Effort**: S ≤ 1wk · M 2–4wk · L 1–3mo · XL multi-quarter
- **Priority** = demand × fit ÷ effort, ranked within each tier

---

## Tier 1 — Quick wins (S effort, ship next cycle)

| # | Feature | What it does | Fit | Demand | Effort | Notes |
|---|---------|--------------|-----|--------|--------|-------|
| 1 | Spoken punctuation & edit commands | "period", "new line", "scratch that", "delete last word" → tokens/edits | High | ★★★ (every agent) | S | Deterministic; feeds existing cleaner pipeline |
| 2 | Filler-word stripping | Drop "um/uh/like" + resolve self-corrections ("no wait, seven" → 7) | High | ★★★ (Wispr Flow parity) | S | Regex/local; no model change |
| 3 | Spacebar push-to-talk | Hold space = record, release = finalize | High | ★★★ | S | Kills 700–1200ms end-of-speech latency feel; biggest perceived-speed win |
| 4 | Undo pill after insert/rewrite | Transient one-tap restore after any voice/Scribe replacement | High | ★★★ | S | Fixes "keyboard has a mind of its own" anxiety |
| 5 | Per-app Scribe presets | Casual in Signal, formal in Gmail, markdown in Obsidian — via `EditorInfo.packageName` | High | ★★ | S | `ScribeInput` already reads `packageName` |
| 6 | Share-sheet audio transcriber | `ACTION_SEND audio/*` → local Whisper → transcript | High | ★★ | S | WhatsApp/Telegram voice notes, privately; reuses WhisperEngine wholesale |
| 7 | Airgap killswitch + status shield | Cyan = on-device, amber = cloud on; one tap = hard network kill | High | ★★ (privacy agents) | S–M | Makes the quarantine story *visible* — marketable |
| 8 | Incognito dictation | RAM-only tier: zero flash writes, buffers zeroed | High | ★★ | M | Above current quarantine; zero-persistence mode |
| 9 | Spoken emoji + live translation | "fire emoji" → 🔥; Whisper `task="translate"` flag | Med | ★★ | S | Translation is essentially free (flag only) |
| 10 | Streaming waveform + haptics in strip | Live level meter in the keyboard action bar | Med | ★★★ | S | Masks Whisper-tiny latency with instant feedback |

## Tier 2 — Differentiators (M/L, plan over quarters)

| # | Feature | What it does | Fit | Demand | Effort |
|---|---------|--------------|-----|--------|--------|
| 11 | Dual-pass ASR | Ultra-low-latency streaming tokens (Moonshine/Zipformer <150ms) while speaking → Whisper polish on release | High | ★★ | L |
| 12 | Quantized Scribe | Qwen2.5-0.5B INT4 via ONNX Runtime GenAI (~350MB RAM, 35–50 tok/s) — instant rewrite pills | High | ★★ | L (`.gitignore` already tracks onnxruntime-genai work) |
| 13 | Context-aware `initial_prompt` biasing | Feed personal dictionary + user vocabulary into Whisper prompt → fixes names/acronyms (top transcription complaint) | High | ★★★ | M |
| 14 | Quick Settings PTT tile | Dictate from any screen / lockscreen | High | ★★ | M |
| 15 | Privacy vault upgrades | PII/secret redaction engine, clipboard auto-purge, per-app quarantine policies, egress audit ledger, zero-knowledge Drive sync (AES-GCM client-side) | High | ★★★ | M–XL (pick pieces) |
| 16 | On-device semantic search | FTS5 + local embeddings over the dictation archive | Med | ★★ | M |
| 17 | Task extraction chips | "todo: review contract Friday" → Todoist/Tasks.org intent | Med | ★★ | M |
| 18 | Grammar underlines | `SpellCheckerService` + `FLAG_GRAMMAR_ERROR`, on-device, Scribe-powered | Med | ★★ | M |
| 19 | Smart reply / quick-action pills in candidate bar | Rewrite/translate/extract-tasks as candidate-row chips | High | ★★ | M |
| 20 | Wake word / hands-free mode | Ambient "Hey Vela" → always-available PTT | Med | ★ | XL |
| 21 | Meeting diarization (VelaVoice side) | Speaker-labeled transcripts in companion app | Med | ★ | XL |

## Tier 3 — Platform catch-up (AUDITED 2026-10-09)

**Fork-diff audit result — do not build these; they already exist:**

| Item | Verdict | Evidence |
|------|---------|----------|
| Spacebar-trackpad cursor swipe | ✅ already in VelaBoard | `TouchpadHandler.java`, pre-fork |
| Clipboard history panel | ✅ already in VelaBoard | clipboard toolbar + `clipboard_bottom` layout, pre-fork; upstream only sped up suggestion display since |
| Split / foldable layout | ✅ already in VelaBoard | `PREF_ENABLE_SPLIT_KEYBOARD[_LANDSCAPE/_FOLDED*]`, `KeyboardSwitcher.toggleSplitKeyboardMode`, foldable-aware |
| Predictive back | ✅ already enabled | `android:enableOnBackInvokedCallback="true"` in AndroidManifest |
| Inline autofill chips | ✅ already in VelaBoard | `InlineAutofillUtils.java` + `LatinIME.onCreateInlineSuggestionsRequest` |
| Hardware-keyboard toggle | ❌ missing (post-fork upstream) | upstream `f4d278ce` (2026-10-04, #2251) |

**Fork baseline:** vendored 2026-08-19 from upstream `13307828` (2026-07-20) — verified by 96% blob-hash identity + exact match on the only 2 files changed upstream between Jul 20 and Aug 10. Upstream head `65aa8f46` (2026-10-09) = **45 commits / ~12 weeks ahead**.

**Upstream sync shortlist** (new upstream value worth porting, by signal):
1. Hardware keyboard support toggle (#2251) — the one genuinely missing Tier-3 item
2. Floating keyboard drag/resize handle reachability (#2692) + min-size increase
3. Recent-emoji clear/remove (#2603), emoji-from-suggestions→recents (#2062)
4. D-Pad key removal setting (#6ac2e014)
5. Gradle/targetSdk/dependency refresh (c9d917d5) — security/maintenance
6. Fixes: apps ignoring paste (257b4fbe), emoji search end (d3455b47), flags regional indicators (f0ef4931), auto-shift on gesture start (432a10a9)

**Merge-conflict risk: MEDIUM.** We modified 35 files vs fork; upstream touched 177; **13 overlap** including heavily-AI-modified cores (`LatinIME.java`, `Settings.java`, `KeyboardSwitcher.java`, `app/build.gradle.kts`, `strings.xml`). A full upstream sync is a planned half-week project (cherry-pick safe commits first: #2251, #2692, #2603 — low-overlap), not an ad-hoc merge.

---

## Out-of-scope / rejected leads

- Cloud-heavy features (web LLM call-per-keystroke, server-side diarization) — violates the privacy story
- Gboard feature parity for its own sake (Grammarly-style upsells, ad-adjacent) — no differentiation
- Custom wake-word model (needs second always-on model; battery cost > value until Tier 2 done)

---

## Decision log

| Date | Decision |
|------|----------|
| 2026-10-09 | Research complete; 21 candidates scored across 3 tiers. Next: (a) fork-diff audit vs upstream HeliBoard, (b) grill → cut Tier 1 to a first implementation batch. |
| 2026-10-09 | **Fork-diff audit done.** Fork point = upstream `13307828` (2026-07-20); 45 commits behind. Tier 3 collapsed: 5/6 items already exist in VelaBoard — build nothing there. Remaining upstream work = sync shortlist (6 items, conflict-risk M, 13 overlapping files). Tier 1 (voice-UX quick wins) confirmed as the real greenfield. |
| 2026-10-09 | Upstream sync to be planned as a **wayfinder map** (deferred); Tier 1 grilling session started to cut 10 candidates → first implementation batch. |
