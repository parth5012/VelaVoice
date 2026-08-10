# Patterns & Conventions

> Code patterns, naming conventions, and idioms specific to this project.

## Naming Conventions

- **Package**: `com.velavoice.sdk.*` for all SDK modules (`com.velavoice.sdk.cleaner`, `.whisper`, `.ui`).
- **SDK facade**: `VelaTranscriber` with a `Builder`; result/callback types prefixed `Vela`/`Transcription` (`TranscriptionResult`, `VelaException`, `VelaRecordingCallback`).
- **Config objects**: `*Config` suffix (`WhisperConfig`, `CleanerConfig`).
- **UI components**: classic View naming — `VoiceRecordingPane`, `WaveformView`.
- **velaboard**: Java/Kotlin under `helium314.keyboard.*` (upstream HeliBoard package, unchanged).
- **Tests**: `*Test.kt` colocated in `src/test/kotlin/` mirroring the main package path; RN app tests are `*.test.ts` / `*.test.js` next to source.

## Code Patterns

- **Builder pattern** for the SDK facade: `VelaTranscriber.Builder(context)...build()`.
- **Engine/Config separation**: engines (`WhisperEngine`, `TextCleaner`) take config objects, keeping options declarative and testable.
- **Streaming abstraction**: `StreamingTranscriber` interface with `LocalStreamingTranscriber` and `CloudStreamingTranscriber` implementations feeding `StreamingPipeline` (strategy pattern).
- **Rule-based pipeline with graceful degradation**: `TextCleaner` runs rule-based → optional LLM → Scribe, and silently falls back to rule-based output when the LLM is unavailable (resilience-by-design, not error).
- **Native JNI bridging**: Kotlin `System.loadLibrary("whisper")` + JNI entry point `whisper-jni.cpp`; vendored native sources in `cpp/`.

## Testing Patterns

- **JUnit unit tests** in each SDK module under `src/test/kotlin/` (34 tests in `vela-cleaner` alone).
- **Pure-logic tests** avoid Android framework dependencies where possible (e.g. `TextCleanerTest`, `PersonalDictionaryTest`, `AudioConverterTest`).
- **RN app**: jest-style tests (`api.test.ts`, `e2eCorrectionTracking.test.ts`, `editCalculator.test.ts`) alongside services/utils.
- **Python**: pytest-style (`scripts/test_export_corrections.py`).

## Import/Module Conventions

- Gradle modules named `vela-*`; published via `maven-publish` to mavenLocal as `com.velavoice.sdk:<module>`.
- `velaboard` consumes SDK via `mavenLocal()` (already declared in its repositories).
- Build files are Kotlin DSL (`build.gradle.kts`) in the SDK; `velaboard` root still uses Groovy DSL (`settings.gradle`, `build.gradle.kts` hybrid).
- Native code is built through CMake (`sdk/vela-whisper/src/main/cpp/CMakeLists.txt`).

## Documentation Conventions

- ADRs in `docs/adr/` numbered `NNNN-topic.md`.
- Domain glossary in root `CONTEXT.md` (single-context layout) — use its canonical terms, avoid listed "Avoid" synonyms.
- Agent config in `docs/agents/` (issue tracker, triage labels, domain layout).
- Log work in `LOG.md`; track blockers in `BLOCKED.md`; record debt in `TECH_DEBT.md`; capture learnings in `LEARNINGS.md`.
