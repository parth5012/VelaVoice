# Spec: Offline Transcription IME

Status: ready-for-agent

## Overview
This specification details the architecture and features of an offline, free, and privacy-first Android Voice Input Method (IME) application inspired by "Yaps". Unlike cloud-based solutions, this application executes all audio capture, speech-to-text transcription, and post-transcription cleaning entirely on the device.

## Target Platform
- Android App built with Expo (React Native) acting as the configuration and model manager.
- Native Android `InputMethodService` implemented in Kotlin to run the keyboard background service.

## Core Domain Vocabulary
See `CONTEXT.md` for terms. Key components:
- **Voice Input Method (IME)**: Custom Android keyboard service.
- **Transcriber**: On-device Whisper engine converting audio to raw text.
- **Raw Transcript**: Direct, unformatted text from Whisper.
- **Cleaner**: Hybrid pre-processor regex/dictionary and optional on-device LLM (Gemma-2B/Llama-3.2-1B).
- **Cleaned Transcript**: Final text output committed to target input fields.

## Core Features
1. **Model Management**: Download and integrity-check (SHA-256) Whisper and LLM model files.
2. **Keyboard Service**: Background voice capture from any app using the keyboard.
3. **Voice Typing Pane**: Replaces keyboard layout with recording visualization and controls.
4. **Hybrid Cleaner Pipeline**: Fast regex cleansing by default; optional high-quality LLM cleanup.

## UI/UX Design (Voice Typing Pane)
- **Active Waveform**: Real-time visualization of mic audio volume levels.
- **Stop & Clean Button**: Stops recording, runs Whisper transcription followed by Cleaner formatting/cleaning, then inserts Cleaned Transcript.
- **Stop Raw Button**: Stops recording and inserts Raw Transcript immediately (skipping Cleaner to save battery/time).
- **Cancel Button**: Discards active audio capture buffer.
