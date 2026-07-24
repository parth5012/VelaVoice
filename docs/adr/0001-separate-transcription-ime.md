Separate Application for Offline Transcription IME

We decided to build the offline transcription keyboard as a separate Android application rather than integrating it directly into the Vela Client. This provides a clean separation of concerns, avoids bloat in the main chat assistant app, keeps native library dependencies separate, and simplifies Google Play Store reviews for IME permissions.
