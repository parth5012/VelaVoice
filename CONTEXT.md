     Vela Client Offline Transcription IME

On-device Android keyboard provider that records audio, transcribes it offline using Whisper, and refines the transcript using an offline cleaner model.

Language

**Voice Input Method (IME)**:
Custom Android keyboard provider that handles audio capturing, transcription, and text insertion.
_Avoid_: Voice keyboard, transcription IME, custom keyboard

**Transcriber**:
Engine responsible for converting recorded audio into text offline on the device.
_Avoid_: Speech-to-text, STT engine, Whisper runner

**Raw Transcript**:
Direct, unformatted text output produced by the Transcriber from the audio.
_Avoid_: Initial transcript, rough text

**Cleaner**:
On-device model or parser that post-processes, refines, and formats the Raw Transcript.
_Avoid_: Post-processor, text cleaner, refiner

**Cleaned Transcript**:
Final formatted, grammatically correct, and filler-free text committed to the target text field.
_Avoid_: Final transcript, formatted text

## HeliBoard-vela (Keyboard Integration)

**Location**: `velaboard/` 

A fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) (privacy-focused AOSP/OpenBoard keyboard) with Vela Voice SDK integrated as the voice input engine.

**Key details**:
- **Build**: Uses NDK 27.1, compileSdk 35, minSdk 23, Kotlin 2.3.20
- **SDK dependency**: `com.velavoice.sdk:vela-core:1.0.0` + `vela-voice-ui:1.0.0` published to mavenLocal from the workspace modules (`sdk/vela-core/`, `sdk/vela-whisper/`, `sdk/vela-cleaner/`, `sdk/vela-voice-ui/`)
- **Voice settings** accessible at **Settings → Voice Input Settings**: model management, transcription engine mode (local/Groq/OpenAI), LLM cleaner toggle, personal dictionary, language/threads/fillers
- **Mic trigger**: `LatinIME.java` checks `isVelaReady()` (model file existence) → shows Vela voice pane or falls back to system voice IME
- **Current state**: Builds successfully. Requires Whisper model file (`ggml-tiny.en.bin`) on device at configured path to use Vela instead of Google STT


Always use `graphify query` commmand to  search through codebase and run `graphify update .` after making changes to it