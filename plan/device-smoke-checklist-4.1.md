# VelaBoard 4.1 — Device Smoke Checklist (ticket #154)

Debug APK: `velaboard/app/build/outputs/apk/debug/HeliBoard_4.1-debug.apk`

## Build verification (agent)
- [x] `./gradlew assembleDebug` succeeds (HeliBoard_4.1-debug.apk produced)
- [x] `./gradlew testDebugUnitTest` — new voice tests green (SpokenCommands, PttGestureStateMachine, UndoPillManager)

## Device smoke (maintainer — HITL)
### Spoken commands (all 5 transcription modes)
- [ ] "hello period" → "hello."
- [ ] "hello comma world question mark" → "hello, world?"
- [ ] "scratch that" removes last clause
- [ ] "delete last word" / "delete last sentence"
- [ ] "new line" / "new paragraph"
- [ ] "periodic" not converted (word boundary)
- [ ] commands disabled via Voice Settings toggle → no conversion

### Spacebar PTT
- [ ] long-press space ≥350ms starts recording (haptic + indicator)
- [ ] swipe crosses cursor threshold first → no recording, cursor moves
- [ ] globe key unaffected
- [ ] pref `ptt_spacebar` default ON; disabling restores lang-switch long-press
- [ ] coexistence with space-long-press lang switch when disabled

### Undo pill
- [ ] "↶ Undo" chip appears after transcription insert/final replace/Scribe rewrite
- [ ] tap restores pre-commit text
- [ ] auto-dismiss on next keypress / supersession / 5s
- [ ] edge cases: field switch mid-recording, IME restart with pending pill

### Cross-app
- [ ] 2+ apps (e.g. notes, messaging)
- [ ] local + ≥1 cloud mode
- [ ] PTT coexistence with undo edge cases

## Sign-off
Maintainer device smoke complete: [ ]  Date: ____  Notes: ____
