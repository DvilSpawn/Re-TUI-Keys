# Re-TUI-Keys

Re:TUI Keyboard is a local-first Android IME built to match the Re:TUI launcher
visual language while still behaving like a normal keyboard.

The project is intentionally offline. The keyboard has no `INTERNET`
permission, does not call a hosted model, and keeps learned words in local app
storage with manual backup and restore.

## Current Status

- Version: `0.2.0`
- Package: `com.dvil.retui.keyboard`
- Minimum Android version: API 26
- Target Android version: API 36
- Distribution: GitHub Releases

## Tech Stack

- Kotlin
- Native Android `InputMethodService`
- Programmatic Android Views for the keyboard and settings UI
- Gradle Android Plugin `8.10.0`
- Kotlin plugin `2.2.10`
- Android `SharedPreferences` for local settings and dictionary storage
- Android Storage Access Framework for dictionary backup and restore
- `org.json` for the local dictionary export format
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) `2.0.1` for on-device voice typing with
  importable voice packs (see `VOICE_PACKS.md`); the AAR is downloaded and checksum-verified at build time

There are no Compose, webview, backend, telemetry, or online suggestion-model
dependencies.

## Features

- Re:TUI styled keyboard surface with terminal/cyberdeck-inspired panels.
- QWERTY, numeric, and symbol layouts.
- Shift supports one-shot capitalization and caps lock by double tap.
- Smart sentence assist can start empty text fields with a capital, add spaces
  after `.`, `?`, and `!`, capitalize the next word, and turn double-space after
  a word into `. `.
- Long-press alternate characters with preview popups.
- Long-press `.` opens a scrollable local emoji keyboard using the Unicode
  fully-qualified emoji list in CLDR order.
- Local suggestion strip above the keyboard.
- User dictionary with visible local word list in settings.
- Manual dictionary add/remove, backup, and restore.
- Importable data-only language packs with an in-keyboard language switcher.
- Voice typing with the system recognizer, any installed speech app, or fully
  on-device voice packs that transcribe live and stop on silence.
- Theme sync from the launcher through IME-private data.
- Optional number row, arrow row, quick period, haptics, sound, sizing, margins,
  key gaps, stroke width, and background image controls.

## Local Suggestions

Suggestions are implemented entirely on-device in `LocalDictionary`.

The engine combines:

- a small built-in seed vocabulary for basic prefix suggestions;
- a local user dictionary stored as JSON in `SharedPreferences`;
- frequency and last-used timestamps for ranking learned words;
- case matching so suggestions follow the user's typed prefix.

When the user types, the IME reads the current word before the cursor and
refreshes the suggestion strip. Tapping a suggestion replaces the current word
and inserts a trailing space.

When the user finishes a custom word with a boundary such as space, punctuation,
or enter, the keyboard can learn that word locally. Common built-in words are not
added to the user dictionary automatically, which keeps the visible dictionary
focused on custom vocabulary.

If the current word is valid but unknown, the strip can show a `+ word` action.
That action manually adds the word to the local dictionary. When there are no
real suggestions or add-word actions, the strip stays empty instead of showing
placeholder status chips.

## Dictionary Settings

The settings screen includes a `DICTIONARY` section:

- `LOCAL WORDS` shows the number of learned user words.
- The add field lets users manually add a word.
- Each learned word is listed with its frequency and a remove button.
- `BACKUP DICTIONARY` writes a JSON file selected by the user.
- `RESTORE DICTIONARY` reads a JSON backup selected by the user.

Backup and restore are manual by design. The manifest disables Android backup
and includes explicit backup/data extraction exclusion rules, so dictionary data
stays local unless the user exports it.

## Language Packs

English ships with the app. Additional languages are `.retui-lang` ZIP files
containing `manifest.json`, `words.tsv`, and an optional `LICENSE` or `NOTICE`.
Users install a downloaded pack from `LANGUAGE PACKS` in Settings; the app
validates it, stores it privately, and exposes it through the language key.
The in-app **Browse language packs** action opens the
[Language Packs page](https://github.com/DvilSpawn/Re-TUI-Keys/blob/main/LANGUAGE_PACKS.md),
where every language links to its own GitHub Release. Deleting an installed
pack switches the keyboard back to English.

The first pack is Persian (`fa-IR`), built from `language-packs/fa-IR/` with
`language-packs/build.sh`. It includes an RTL Persian
layout, Persian digits and punctuation, zero-width non-joiner input, character
normalization, and 50,000 offline completion entries. Language packs contain
data only and cannot execute code.

## Voice Typing

The mic key uses Google's recognizer by default. **Voice input** in Settings can
switch to any installed speech app or to an on-device voice pack. Voice packs are
`.retui-voice` files holding a sherpa-onnx speech model (Moonshine, Whisper,
Parakeet or SenseVoice); they are imported like language packs, including via
**Open with** from a file manager. With a voice pack, text appears as you speak,
each phrase is finalized when you pause, and dictation stops after a few seconds
of silence. Audio never leaves the device. See [VOICE_PACKS.md](VOICE_PACKS.md)
for official packs and how to build your own.

## Launcher Theme Bridge

The keyboard can receive Re:TUI launcher theme data through standard IME
channels:

- `InputMethodManager.sendAppPrivateCommand`
- `EditorInfo.extras`
- `EditorInfo.privateImeOptions`

The IME reads color, border, typography, cyberdeck, CRT, and context values from
those payloads, applies them to the keyboard surface, and persists the latest
theme snapshot locally.

The keyboard still works without the launcher. In that case it uses its built-in
terminal theme defaults.

## Building

Install Android SDK 36 and set `ANDROID_HOME` (or `sdk.dir` in
`local.properties`) to your SDK location. The real Launcher visual contract is
included in `retui-contract/`; no Launcher checkout or private repository is
required.

```bash
./gradlew assembleDebug
```

For a minified release build:

```bash
./gradlew assembleRelease
```

APK builds produce one file per CPU type in `app/build/outputs/apk/<type>/`:
`app-arm64-v8a-*.apk` (most phones) and `app-armeabi-v7a-*.apk` (older 32-bit
phones). For Google Play, build the app bundle with `./gradlew bundleRelease`.

The first build downloads sherpa-onnx, the Silero VAD model and ONNX Runtime's
third-party notices into `.gradle/`, each pinned by SHA-256.

The debug APK is signed with the standard Android debug key and is suitable for
sideload testing. Release builds are signed when `local.properties` defines
`retui.release.storeFile`, `retui.release.storePassword`,
`retui.release.keyAlias` and `retui.release.keyPassword`; otherwise they are
unsigned.

## Project Layout

- `app/src/main/java/com/dvil/retui/keyboard/RetuiKeyboardService.kt`:
  IME service, keyboard layouts, key handling, theme bridge, and suggestion strip.
- `app/src/main/java/com/dvil/retui/keyboard/LocalDictionary.kt`:
  local dictionary, ranking, learning, import, and export.
- `app/src/main/java/com/dvil/retui/keyboard/KeyboardSettingsActivity.kt`:
  settings UI, preview surface, dictionary management, and backup/restore.
- `app/src/main/java/com/dvil/retui/keyboard/KeyboardPrefs.kt`:
  persisted keyboard layout and typing preferences.
- `app/src/debug/java/com/dvil/retui/keyboard/KeyboardProbeActivity.kt`:
  debug-only probe activity for previewing the IME.

## Privacy Model

- No network permission.
- Voice packs transcribe on the device; the microphone is used only while the
  mic key is active.
- No online suggestion service.
- No telemetry.
- No cloud backup.
- User dictionary backup and restore are explicit file-picker actions.

This keeps the keyboard aligned with the local-only Re:TUI launcher model.
