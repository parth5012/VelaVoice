# Graph Report - Vela Voice  (2026-07-25)

## Corpus Check
- 22 files · ~5,217 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 142 nodes · 174 edges · 20 communities (19 shown, 1 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 5 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `5e0f03a0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_VoiceInputMethodService|VoiceInputMethodService]]
- [[_COMMUNITY_package.json|package.json]]
- [[_COMMUNITY_ModelVerifierModule|ModelVerifierModule]]
- [[_COMMUNITY_WhisperEngine|WhisperEngine]]
- [[_COMMUNITY_App.tsx|App.tsx]]
- [[_COMMUNITY_expo|expo]]
- [[_COMMUNITY_WaveformView|WaveformView]]
- [[_COMMUNITY_Combined Issues Offline Transcription IME|Combined Issues: Offline Transcription IME]]
- [[_COMMUNITY_TextCleaner|TextCleaner]]
- [[_COMMUNITY_dependencies|dependencies]]
- [[_COMMUNITY_Spec Offline Transcription IME|Spec: Offline Transcription IME]]
- [[_COMMUNITY_withVoiceIme.js|withVoiceIme.js]]
- [[_COMMUNITY_tsconfig.json|tsconfig.json]]
- [[_COMMUNITY_CLAUDE|CLAUDE.md]]

## God Nodes (most connected - your core abstractions)
1. `VoiceInputMethodService` - 17 edges
2. `WhisperEngine` - 10 edges
3. `TextCleaner` - 7 edges
4. `WaveformView` - 7 edges
5. `Combined Issues: Offline Transcription IME` - 7 edges
6. `expo` - 6 edges
7. `scripts` - 6 edges
8. `Spec: Offline Transcription IME` - 6 edges
9. `ModelVerifierModule` - 5 edges
10. `ModelManager` - 5 edges

## Surprising Connections (you probably didn't know these)
- `VoiceInputMethodService` --references--> `WaveformView`  [EXTRACTED]
  src/native/VoiceInputMethodService.kt → src/native/WaveformView.kt

## Import Cycles
- None detected.

## Communities (20 total, 1 thin omitted)

### Community 0 - "VoiceInputMethodService"
Cohesion: 0.17
Nodes (11): AudioRecord, Button, InputMethodService, LinearLayout, Boolean, Context, String, View (+3 more)

### Community 1 - "package.json"
Cohesion: 0.12
Nodes (16): devDependencies, @babel/core, expo-build-properties, @types/react, @types/react-native, typescript, main, name (+8 more)

### Community 2 - "ModelVerifierModule"
Cohesion: 0.15
Nodes (10): List, NativeModule, Promise, ReactApplicationContext, ReactContextBaseJavaModule, ReactPackage, String, ModelVerifierModule (+2 more)

### Community 3 - "WhisperEngine"
Cohesion: 0.27
Nodes (6): ByteArray, FloatArray, Long, Boolean, String, WhisperEngine

### Community 4 - "App.tsx"
Cohesion: 0.31
Nodes (5): styles, DEFAULT_MODELS, getDb(), ModelInfo, ModelManager

### Community 5 - "expo"
Cohesion: 0.25
Nodes (7): package, expo, android, name, plugins, slug, version

### Community 6 - "WaveformView"
Cohesion: 0.25
Nodes (4): Canvas, Float, WaveformView, View

### Community 7 - "Combined Issues: Offline Transcription IME"
Cohesion: 0.25
Nodes (7): Combined Issues: Offline Transcription IME, Issue 01: Setup Project Scaffolding & Native Android IME Service, Issue 02: Model Downloader and Scoped Storage Manager, Issue 03: Native IME Voice Typing Pane and Waveform UI, Issue 04: On-device Transcriber Engine (Whisper) Integration, Issue 05: Hybrid Cleaner Pipeline (Regex + LLM), Issue 06: IME Commit Integration & Final End-to-End Testing

### Community 8 - "TextCleaner"
Cohesion: 0.50
Nodes (3): Boolean, String, TextCleaner

### Community 9 - "dependencies"
Cohesion: 0.29
Nodes (7): dependencies, expo, expo-file-system, expo-sqlite, expo-status-bar, react, react-native

### Community 10 - "Spec: Offline Transcription IME"
Cohesion: 0.29
Nodes (6): Core Domain Vocabulary, Core Features, Overview, Spec: Offline Transcription IME, Target Platform, UI/UX Design (Voice Typing Pane)

### Community 11 - "withVoiceIme.js"
Cohesion: 0.40
Nodes (3): fs, path, { withAndroidManifest, withDangerousMod }

### Community 12 - "tsconfig.json"
Cohesion: 0.50
Nodes (3): compilerOptions, strict, extends

## Knowledge Gaps
- **44 isolated node(s):** `styles`, `name`, `slug`, `version`, `package` (+39 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VoiceInputMethodService` connect `VoiceInputMethodService` to `WaveformView`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Why does `WhisperEngine` connect `WhisperEngine` to `VoiceInputMethodService`?**
  _High betweenness centrality (0.061) - this node is a cross-community bridge._
- **What connects `styles`, `name`, `slug` to the rest of the system?**
  _44 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.11764705882352941 - nodes in this community are weakly interconnected._