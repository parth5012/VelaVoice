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

## Tier 3 — Platform catch-up (verify BEFORE building)

Cited by the platform agent as already present in upstream HeliBoard 4.0/4.2 or other open-source IMEs:

- Spacebar-trackpad cursor swipe
- Clipboard history panel
- Split / foldable layout
- Predictive back (`OnBackInvokedCallback`)
- Inline autofill chips
- Hardware-keyboard mini-toolbar (desktop mode)

**Flag:** we forked HeliBoard at an older base. Several Tier-3 items may be *merge upstream*, not *build*. First action before any Tier-3 work → **fork-diff audit** against upstream.

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
