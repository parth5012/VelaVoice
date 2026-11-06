# Multi-Engine Local Transcription — Benchmarks

Placeholder table (emulator benchmark run pending). One row per shipped model.

| Model | Engine | WER (20-clip) | First-partial latency (ms) | Notes |
|---|---|---|---|---|
| whisper-tiny-en | WHISPER | baseline | n/a (batch) | existing |
| whisper-tiny-en-q5_0 | WHISPER | TBD | n/a | |
| whisper-base-en | WHISPER | TBD | n/a | |
| whisper-small-en | WHISPER | TBD | n/a | |
| fluid-speech-mini | SHERPA | TBD | TBD | |
| parakeet-tdt-v3 | SHERPA | TBD | TBD | |
| moonshine-tiny | SHERPA | TBD | TBD | |

Security sweep: model paths containment enforced via ModelIntegrity.isInsideAllowedRoots; SHA-256 fail-closed; no transcript content in logcat per #79.
