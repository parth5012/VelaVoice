# VelaBoard Upstream HeliBoard Sync

Created: 2026-10-10
Status: `planned` → tracked as a wayfinder map (see below)
Origin: bulk grilling on 2026-10-10 (handoff from 2026-10-09 session; shortlist from `plan/features-research.md` Tier 3)

**Wayfinder map:** [#164 VelaBoard Upstream HeliBoard Sync — cherry-pick shortlist](https://github.com/parth5012/VelaVoice/issues/164) (label `wayfinder:map`). Implementation proceeds via AFK task tickets; this doc is the durable record of the locked decisions.

## Destination

The VelaBoard fork absorbs the 6-item upstream HeliBoard sync shortlist via cherry-picks, each verified by Gradle suite + full device smoke at end of PR, with zero regressions to the VelaVoice voice-UX features on Batch 1/2 maps (#147, #156).

## Locked decisions (bulk grilling — 2026-10-10)

| # | Decision | Answer |
|---|----------|--------|
| 1 | Sync strategy | **Cherry-pick only** — 6 items from shortlist; no full merge-base sync |
| 2 | Order | **Low → high overlap** (safe fixes → D-Pad + hardware keyboard → Gradle/SDK bump) |
| 3 | Sequencing vs Batch 1/2 | **Can go parallel** — safe low-overlap picks start immediately; high-overlap picks note conflict risk in ticket bodies |
| 4 | Verification | Gradle test suite per pick; **full device smoke at end of PR** |
| 5 | Release vehicle | Lands as part of **4.3** (with Batch 3) |

## Upstream commit inventory (verified 2026-10-10)

| Commit | PR | Description | Files touched | Overlap risk |
|--------|----|-------------|---------------|-------------|
| `257b4fbe` | — | Apps ignoring paste | ClipboardHistoryManager.kt, InputLogic.java | LOW |
| `d3455b47` | #2326 | Emoji search ending fix | EmojiSearchActivity.kt, LatinIME.java | LOW |
| `f0ef4931` | #2682 | Regional-indicator flags | FLAGS.txt, StringUtils.kt, MakeEmojiKeys, EmojiData | LOW |
| `432a10a9` | — | Auto-shift override on gesture start | InputLogic.java (1 line) | LOW |
| `27d9d3cb` | #2692 | Floating keyboard handle reachability | LatinIME.java, FloatingKeyboardUtils.kt | LOW |
| `b28307db` | #2603 | Recent-emoji clear/remove | PopupKeys*, DynamicGridKeyboard, EmojiPageKeyboardView, strings.xml | LOW–MED (strings.xml) |
| `6ac2e014` | — | D-Pad key removal setting | functional_keys*.json, KeyboardId, KeyboardLayoutSet, KeyboardSwitcher, KeyData, Defaults, Settings, SettingsValues | MED (Settings, KeyboardSwitcher) |
| `f4d278ce` | #2251 | Hardware-keyboard support toggle | KeyboardActionListenerImpl, ProductionFlags, Defaults, Settings, SettingsValues, AdvancedScreen, strings.xml | MED (Settings) |
| `c9d917d5` | — | Gradle/targetSdk/dependency refresh | build.gradle.kts, gradle wrapper, robolectric.properties | MED (build config) |

## Map tickets (AFK handover-ready)

| Ticket | Title | Blocked by |
|--------|-------|------------|
| [#166](https://github.com/parth5012/VelaVoice/issues/166) | Low-overlap cherry-picks (paste + emoji search + flags + auto-shift) | — (frontier) |
| [#167](https://github.com/parth5012/VelaVoice/issues/167) | Floating keyboard handle reachability (#2692) | — (frontier) |
| [#168](https://github.com/parth5012/VelaVoice/issues/168) | Recent-emoji clear/remove (#2603) | — (frontier) |
| [#169](https://github.com/parth5012/VelaVoice/issues/169) | D-Pad key removal setting | #166, #167, #168 |
| [#170](https://github.com/parth5012/VelaVoice/issues/170) | Hardware-keyboard support toggle (#2251) | #169 |
| [#171](https://github.com/parth5012/VelaVoice/issues/171) | Gradle/targetSdk/dependency refresh | #170 |
| [#172](https://github.com/parth5012/VelaVoice/issues/172) | Integration — full suite + device-smoke checklist + PR | #171 |

Handover: each ticket body carries locked constraints, upstream SHA, file paths, conflict-risk notes, acceptance criteria, env/verification notes. Frontier now: #166, #167, #168 (parallel-safe).

## Explicit non-goals

- Full merge-base sync (all 45 commits)
- Any new VelaVoice feature work (separate maps)
- Version bump / release cut (release vehicle decided at build time)

## Conflict watchlist

- `Settings.java`, `KeyboardSwitcher.java`, `LatinIME.java`, `strings.xml`, `app/build.gradle.kts` are all modified by our fork AND touched by upstream picks — cherry-pick may need manual conflict resolution
- Batch 1/2 in-flight work touches `KeyboardSwitcher.java` (#154 integration) and `VelaStreamingSession.java` — high-overlap picks (#2251, Gradle bump) should rebase onto latest main before final PR

## Decision log

| Date | Decision |
|------|----------|
| 2026-10-10 | Bulk grilling completed; 5 decisions locked (table above). Cherry-pick strategy, low→high order, parallel-safe, full smoke per PR, 4.3 vehicle. |
| 2026-10-10 | Map #164 + 7 task tickets (#166–#172) charted on GitHub; blocking via body convention + tasklist; frontier = #166/#167/#168. |
