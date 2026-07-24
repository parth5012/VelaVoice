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
