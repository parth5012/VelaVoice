Model Storage and Native Access Architecture

We decided to download model files through the Expo application layer into Scoped Storage (context.filesDir) and track them with SQLite and Shared Preferences. This allows the React Native UI to safely manage downloads and verify integrity via SHA-256, while the Kotlin InputMethodService retrieves file paths directly from storage to initialize the Whisper and Cleaner engines.
