# VelaVoice: Project PRD & Design Brief

## 1. Project Overview
VelaVoice is a premium Android-native voice recording and transcription application designed for power users who require high-fidelity, private, and intelligent audio processing. The app distinguishes itself by combining **on-device STT (Speech-to-Text)** with a **local AI cleanup model** that removes filler words, fixes grammar, and structures text in real-time.

### Core Value Proposition
- **Privacy First:** On-device processing ensures sensitive audio never leaves the device unless Cloud Boost is explicitly enabled.
- **Magical Cleanup:** Transforming "um-heavy" raw audio into polished, professional text with visual transparency.
- **OLED Efficiency:** A design system built for battery longevity and high-contrast visual clarity.

---

## 2. Target Audience
- **Product Managers & Executives:** For capturing meeting syncs and strategy rants.
- **Developers:** For recording "dev jams" and technical notes (supported by custom dictionaries).
- **Content Creators:** For drafting scripts and "brain dumps" that need instant structural polishing.

---

## 3. Product Features & Scope

### Phase 1: Core Experience (Current)
- **The Voice Hub:** Centralized recording interface with liquid audio visualization and persistent library access.
- **The Studio:** Interactive transcript canvas with "Raw vs. Cleaned" toggle and AI-highlighted refinements.
- **The Engine Room:** Configuration center for local models (Whisper Tiny/Base/Small), custom domain dictionaries, and API key management for cloud fallbacks.
- **Seamless Morphing UI:** FAB-to-Sheet transitions that maintain user context during recording.

### Phase 2: Advanced Iteration (Planned)
- **Reprocess Intensity:** Options for *Literal*, *Concise*, or *Professional Email* output styles.
- **Batch Processing:** Ability to run cleanup models on multiple imported files.
- **Holographic Shimmer Transition:** A high-fidelity visual effect that "paints" cleaned text over raw transcripts.

---

## 4. Design System (Lumina Sonic)
- **Color Palette:**
    - Background: Deep Obsidian (`#0B0C10`)
    - Primary Accent: Neon Cyan (`#66FCF1`) - *Active states, recording.*
    - Secondary Accent: Electric Indigo (`#4B0082`) - *AI processing, cleanup.*
- **Typography:** Plus Jakarta Sans (Headers), Inter (Body/Mono).
- **Visual Style:** Glassmorphism (8% white opacity + 24dp blur), high-contrast borders, and tactile haptics.

---

## 5. Technical Requirements
- **OS:** Android (Jetpack Compose / Flutter).
- **Processing:** 
    - Local: Quantized Whisper models for STT.
    - Cloud: GPT-4o / Groq / Anthropic (BYOK).
- **Battery:** Optimized for OLED (True Black `#000000` surfaces where applicable).

---

## 6. Success Metrics
- **Processing Latency:** Time from "Stop" to "Cleaned Reveal" < 3 seconds for 1-minute clips.
- **User Retention:** Engagement with the "Reprocess" feature.
- **Accuracy:** Reduction in Word Error Rate (WER) using Custom Dictionary hits.
