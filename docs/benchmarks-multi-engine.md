# Multi-Engine Local Transcription — Benchmarks

Placeholder table (emulator benchmark run pending). One row per shipped model.

| Model | Engine | WER (20-clip) | First-partial latency (ms) | Notes |
|---|---|---|---|---|
| whisper-tiny-en | WHISPER | baseline | n/a (batch) | existing |
| whisper-tiny-en-q5_0 | WHISPER | TBD | n/a | |
| whisper-base-en | WHISPER | TBD | n/a | |
| whisper-small-en | WHISPER | TBD | n/a | |
| fluid-speech-mini | SHERPA | TBD | n/a (batch) | url pin pending (809 closeout) |
| nemotron-streaming-en | SHERPA | TBD | TBD (streaming) | Nemotron-3.5-ASR-Streaming-0.6b; engine landed in 803 (bdd02b82) |
| parakeet-tdt-v3 | SHERPA | TBD | n/a (offline-only) | Parakeet realtime-EOU not streamable in sherpa-onnx (803 verdict) |
| moonshine-tiny | SHERPA | TBD | n/a (batch) | url pin pending (809 closeout) |

Security sweep: model paths containment enforced via ModelIntegrity.isInsideAllowedRoots; SHA-256 fail-closed; no transcript content in logcat per #79.
