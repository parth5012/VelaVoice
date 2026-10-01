# Learnings

> Key learnings, edge cases, and concepts worth remembering for this project.

## Project-Specific Learnings

- The repo is a workspace of several independent projects: `sdk/` (Android Kotlin library modules), `velaboard/` (HeliBoard IME fork consuming the SDK via mavenLocal), `velavoice app/` (React Native / Expo app), `ui/` (design/PRD assets), `scripts/` (Python helpers), `docs/` (ADRs, agents config, research).
- The SDK (`sdk/`) is `vela-transcription-sdk` with 4 Gradle modules published to mavenLocal as `com.velavoice.sdk:*`: `vela-core` (facade), `vela-whisper` (native JNI), `vela-cleaner` (rule-based + optional ONNX LLM), `vela-voice-ui` (recording UI).
- `vela-cleaner` requires minSdk 24 (enforced by `onnxruntime-genai-android`); all SDK modules and velaboard/app are now normalised to minSdk 24, compileSdk 35, and Java 17 everywhere.
- `onnxruntime-genai-android:0.15.0` is **not on Maven Central** — it must be downloaded as an AAR and published to mavenLocal manually (see README "One-time local setup"). The AAR is gitignored at `sdk/vela-cleaner/libs/`.
- The graphify knowledge graph lives at `graphify-out/graph.json`; `velaboard/` has its own nested `graphify-out/`. Use `graphify query` for codebase questions and `graphify update .` after edits.
- `velaboard/` also carries its own AGENTS.md and CLAUDE-style docs (`BUILD_REFERENCE.md`, `REVIEW.md`, `AI_USAGE.md`).
- Builds are Windows-first (`gradlew.bat`); prefer `--no-daemon` for verification builds since the Gradle daemon can stall.

## Decision Log

> Why decisions were made, alternatives considered, and consequences.

- Cleaner pipeline order is rule-based first, then optional LLM cleanup, then Scribe rewrite. If the LLM model is missing/fails, `TextCleaner` silently falls back to rule-based output (verified by tests) — a deliberate resilience choice.
- Kotlin version drift: `sdk/gradle/libs.versions.toml` stays on Kotlin 1.9.23 while `velaboard` is on 2.3.20; `sdk/` pins AGP 8.4.0 which targets Kotlin 1.9.x/2.0.x and the upgrade cannot be compile-verified in this offline environment. Follow-up is to standardise when CI (#68) can actually run Gradle.

## Edge Cases

> Known edge cases, gotchas, and non-obvious behaviors discovered during work.

- The LLM model path can point at a directory with `genai_config.json` + ONNX files, or a single `.onnx`. HuggingFace repos like `onnx-community/Llama-3.2-1B-Instruct-ONNX` use external data files — the full `model.onnx_data*` set must be downloaded.
- The GenAI AAR bundles a Microsoft telemetry SDK; `GenAI.setTelemetry(false)` is called before model load, but the AAR's `INTERNET` permission may still merge into the consuming app manifest.
- `velaboard` mic trigger checks `isVelaReady()` (model file existence) before showing the Vela voice pane; without a Whisper model on device it falls back to system voice IME.
- In Gradle builds with configuration cache enabled (`org.gradle.configuration-cache=true`), reading `.env` files via raw `File.readLines()` bypasses Gradle input tracking, causing rotated credentials to be silently reused from cache and plaintext secrets to be captured in `.gradle/configuration-cache/`. Using `providers.fileContents(...).asText` declares the file as a configuration input so any modification automatically invalidates the cache.
- Gradle `buildConfigField` interpolates string values directly into generated Java (`BuildConfig.java`). Values loaded from `.env` must be stripped of surrounding quotes and escaped (`\\`, `\"`, `\n`, `\r`) to prevent syntax errors and code injection.
- Using `resolutionStrategy.force(...)` silently overrides declared dependency versions and masks genuine dependency incompatibilities; declaring the exact required version (e.g. `androidx.core:core-ktx:1.15.0`) allows Gradle's standard conflict resolution to select the appropriate artifact transparently.
- In React Native Expo test environments running on Node (via tsx/esbuild), `react-native` package contains Flow types (`typeof` syntax) causing parser errors. Intercepting package resolution in `Module._resolveFilename` or isolating native modules with safe fallback (`SecureStore.isAvailableAsync()`) ensures tests run deterministically in Node while preserving native behavior on mobile.
- When implementing cloud providers with Base64 audio encoding in Android SDK modules, local unit tests running on JVM lack ndroid.util.Base64 stub implementations unless Robolectric is used or a reflection fallback to java.util.Base64 is provided. The reflection fallback guarantees testability across all runner environments.
- Gemini 2.0 Flash generateContent audio STT requires wrapping raw PCM in a RIFF/WAV header (44 bytes for 16kHz 16-bit mono) before Base64 encoding. Markdown fences (e.g. `	ext or `markdown) may wrap output despite system instructions and must be sanitized.
- Setting native linker hardening flags: in CMake, linker flags like `-Wl,-z,relro` and `-Wl,-z,now` must be attached to the target (`target_link_options(whisper PRIVATE ...)`) rather than solely `CMAKE_CXX_FLAGS` to ensure they reach the linker invocation for shared libraries, guaranteeing the `BIND_NOW` flag is emitted in ELF headers.
- GitHub Actions in nested directories: Workflows placed anywhere other than the repository root's `.github/workflows/` directory (e.g. `velaboard/.github/workflows/`) are completely inert and never executed by GitHub Actions. Monorepo architectures must centralize root workflows or trigger sub-project tasks with explicit `working-directory` settings.
- Gradle dependency ordering in monorepo CI: Downstream consumers in subdirectories (such as `velaboard/`) that resolve internal dependencies via `mavenLocal()` require preceding upstream projects (`sdk/`) to explicitly run `./gradlew publishToMavenLocal`. Furthermore, libraries requiring pre-built binary dependencies (such as `sdk/vela-cleaner` depending on `onnxruntime-genai-android`) must execute prerequisite bootstrap verification scripts before any Gradle build or test tasks are triggered.
