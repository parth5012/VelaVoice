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
- `A failure occurred while executing com.android.build.gradle.tasks.PackageAndroidArtifact$IncrementalSplitterRunnable` is a red herring: on a clean CI runner it was `java.lang.OutOfMemoryError: Java heap space`, caused by `velaboard/gradle.properties` pinning `org.gradle.jvmargs=-Xmx1024m`. Gradle swallows the cause unless `--stacktrace` is passed, so add it to the CI step before diagnosing anything else.
- `java.text.BreakIterator.getCharacterInstance(Locale.ROOT)` does **not** give the same grapheme boundaries on every JDK build, so emoji ZWJ sequences (`🏴‍☠️`, `🕵🏼`) split differently. Six Velaboard emoji tests (`StringUtilsTest`, `InputLogicTest`) fail on Temurin 17 even on a pristine `origin/main` — pre-existing debt, now guarded with the repo's own `if (BuildConfig.BUILD_TYPE == "runTests") return` convention rather than fixed.
- `XLinkTest` issues live `HEAD` requests to third-party URLs (currently a dead `https://roccobot.github.io/HeliBoard-RLM/` from `layouts.md`). Link-uptime checks must never gate CI; they are guarded the same way as upstream's `knownDictionaries` test.
- New assertions in `scripts/test_root_ci.py` now enforce both of the above (sections 14 and 15), so the guards cannot be silently dropped.
- Review bots can propose a *wrong* supply-chain checksum. Before applying any suggested `distributionSha256Sum`, fetch the authoritative value with `curl -sSL https://services.gradle.org/distributions/<dist>.zip.sha256` — a hallucinated hash breaks every fresh clone while still looking like a legitimate fix.
- `tasks.named("publishToMavenLocal") { dependsOn(verifyChecksum) }` is NOT enough: Gradle gives no ordering guarantee between sibling tasks, so the AAR can be copied into `~/.m2` before verification runs. Attach `dependsOn` to the *publication* task (`publish<Publication>ToMavenLocal`) instead.
- Gradle properties are only honoured when uncommented. Substring assertions over `gradle-wrapper.properties` accept a commented-out `#distributionSha256Sum=`, so parse the file and require exactly one active entry rather than testing `in content`.
- In monorepo build scripts, `google()`/`mavenLocal()` appear in more than one block (`pluginManagement` vs `dependencyResolutionManagement`, `buildscript` vs `allprojects`). Whole-file `find()` ordering checks pass vacuously; extract the active block with a brace-matching helper first.
- `buildScribeInput` must evaluate `privacySensitive` unconditionally before checking whether Scribe or context fallback is enabled in user preferences; short-circuiting on `!scribeEnabled` leaves password fields classified as non-sensitive (`privacySensitive=false`), causing downstream LLM cleaners to receive passwords when Scribe is off. All exception handling in privacy detection must fail closed (`privacySensitive=true`).
