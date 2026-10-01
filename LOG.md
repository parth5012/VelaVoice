# Log

> Every iteration logged with status, what changed, and verification result.

## Format

Each entry:

- **Date**: YYYY-MM-DD HH:MM
- **Status**: Done | Blocked | Budget | Compacted | Stuck | Outage | Review
- **What**: Brief description of the task/change
- **Verified**: What was run to verify (tests, typecheck, etc.)
- **Notes**: Any decisions or learnings

## Entries

| Date | Status | What | Verified | Notes |
|------|--------|------|----------|-------|
| —    | —      | —    | —        | —     |
| 2026-09-05 12:00 | Done | US-VELA-042: Gemini 2.0 Flash provider settings, API key management & connection test | GeminiService.test.ts (8/8 green), editCalculator.test.ts, npm run ts:check clean | Added GeminiService, GeminiSettings, SecureStore key management, ScribeAI integration |
| 2026-09-05 14:25 | Done | US-VELA-042: Gemini 2.0 Flash transcription provider in vela-core | :vela-core:testDebugUnitTest (89/89 green) | Implemented GeminiTranscriptionProvider with REST STT, WAV conversion, Base64 fallback, and StreamingTranscriber integration |
| 2026-09-05 15:30 | Done | US-VELA-042: Integrated E2E test verification for Gemini 2.0 Flash transcription flow | geminiE2E.test.ts (29/29 green), GeminiService.test.ts (8/8 green), npm run ts:check clean, :vela-core:testDebugUnitTest green | Verified full flow: key config, preflight gate, PCM 16kHz WAV RIFF header, verbatim instruction payload, response parser, edge cases (429 rate limit, 403 invalid key, empty audio, timeout), multi-step user journey |

| 2026-09-06 04:35 | Done | Upgrade default Gemini model to Gemini 3.6 voice model with dynamic model configuration | GeminiService.test.ts (10/10 green), geminiE2E.test.ts (29/29 green), :vela-core:testDebugUnitTest (90/90 green), npm run ts:check clean | Set default model to gemini-3.6 with support for gemini-3.6-flash, gemini-2.0-flash, and custom models across Android SDK (GeminiTranscriptionProvider), native IME service (VoiceAccessibilityService), companion app (GeminiSettings, GeminiService), and ScribeAI |
| 2026-10-01 10:15 | Review | [AFK 1.4] Build-script hygiene: remove force(), cache-tracked .env, escape BuildConfig, root .gitignore (#69) | scripts/test_build_script_hygiene.py (43/43 green), scripts/test_dependency_integrity.py (24/24 green) | Corrected androidx.core:core-ktx to 1.15.0 and removed force() block; converted .env loading to providers.fileContents; added quote-stripping and escaping for BuildConfig fields; extended root .gitignore for keys/artifacts |