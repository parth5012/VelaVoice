# Learnings

> Key learnings, edge cases, and concepts worth remembering for this project.

## Project-Specific Learnings

- The repo is a workspace of several independent projects: `sdk/` (Android Kotlin library modules), `velaboard/` (HeliBoard IME fork consuming the SDK via mavenLocal), `velavoice app/` (React Native / Expo app), `ui/` (design/PRD assets), `scripts/` (Python helpers), `docs/` (ADRs, agents config, research).
- The SDK (`sdk/`) is `vela-transcription-sdk` with 4 Gradle modules published to mavenLocal as `com.velavoice.sdk:*`: `vela-core` (facade), `vela-whisper` (native JNI), `vela-cleaner` (rule-based + optional ONNX LLM), `vela-voice-ui` (recording UI).
- `vela-cleaner` requires minSdk 24 (enforced by `onnxruntime-genai-android`); all other SDK modules target minSdk 23. Java/Kotlin 17 everywhere.
- `onnxruntime-genai-android:0.15.0` is **not on Maven Central** — it must be downloaded as an AAR and published to mavenLocal manually (see README "One-time local setup"). The AAR is gitignored at `sdk/vela-cleaner/libs/`.
- The graphify knowledge graph lives at `graphify-out/graph.json`; `velaboard/` has its own nested `graphify-out/`. Use `graphify query` for codebase questions and `graphify update .` after edits.
- `velaboard/` also carries its own AGENTS.md and CLAUDE-style docs (`BUILD_REFERENCE.md`, `REVIEW.md`, `AI_USAGE.md`).
- Builds are Windows-first (`gradlew.bat`); prefer `--no-daemon` for verification builds since the Gradle daemon can stall.

## Decision Log

> Why decisions were made, alternatives considered, and consequences.

- Cleaner pipeline order is rule-based first, then optional LLM cleanup, then Scribe rewrite. If the LLM model is missing/fails, `TextCleaner` silently falls back to rule-based output (verified by tests) — a deliberate resilience choice.

## Edge Cases

> Known edge cases, gotchas, and non-obvious behaviors discovered during work.

- The LLM model path can point at a directory with `genai_config.json` + ONNX files, or a single `.onnx`. HuggingFace repos like `onnx-community/Llama-3.2-1B-Instruct-ONNX` use external data files — the full `model.onnx_data*` set must be downloaded.
- The GenAI AAR bundles a Microsoft telemetry SDK; `GenAI.setTelemetry(false)` is called before model load, but the AAR's `INTERNET` permission may still merge into the consuming app manifest.
- `velaboard` mic trigger checks `isVelaReady()` (model file existence) before showing the Vela voice pane; without a Whisper model on device it falls back to system voice IME.
- In React Native Expo test environments running on Node (via tsx/esbuild), `react-native` package contains Flow types (`typeof` syntax) causing parser errors. Intercepting package resolution in `Module._resolveFilename` or isolating native modules with safe fallback (`SecureStore.isAvailableAsync()`) ensures tests run deterministically in Node while preserving native behavior on mobile.
- When implementing cloud providers with Base64 audio encoding in Android SDK modules, local unit tests running on JVM lack ndroid.util.Base64 stub implementations unless Robolectric is used or a reflection fallback to java.util.Base64 is provided. The reflection fallback guarantees testability across all runner environments.
- Gemini 2.0 Flash generateContent audio STT requires wrapping raw PCM in a RIFF/WAV header (44 bytes for 16kHz 16-bit mono) before Base64 encoding. Markdown fences (e.g. `	ext or `markdown) may wrap output despite system instructions and must be sanitized.
