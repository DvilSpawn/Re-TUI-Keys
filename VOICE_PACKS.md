# Voice Packs

Re:TUI Keys can transcribe speech on your phone with a voice pack instead of Google's
recognizer. Nothing leaves the device: the keyboard still has no internet permission.

## Install a pack

1. Download a pack `.zip` in your browser.
2. Open it from your downloads or file manager and choose **Install in Re:TUI Keys**, or go to
   **Settings → Voice input → Import voice pack** and pick the file.
3. The pack becomes the active engine. Tap the mic on the keyboard, speak, and tap again to insert
   the text.

Switch back to Google or another installed speech app any time under **Voice input**.

## Official packs

| Pack | Languages | Size | Notes |
|---|---|---|---|
| English (Moonshine tiny) | English | 29 MB | Fastest, good for short messages |
| English (Moonshine base) | English | 107 MB | Recommended |

Multilingual Whisper packs are planned.

## Bring your own model

Any [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) offline speech model of a supported type
can be turned into a pack. Download and extract a model from the
[sherpa-onnx ASR models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models), then run:

```sh
voice-packs/make-voice-pack.sh --type whisper --id whisper-base --name "Whisper base" \
  --lang en --lang de --lang fr ./sherpa-onnx-whisper-base my-whisper-base.zip
```

The script finds the model files by name, prefers int8 variants, and writes the manifest.
Check the model's license before sharing a pack.

### Supported types

| `type` | Models | Files |
|---|---|---|
| `moonshine` | Moonshine | `encoder`, `mergedDecoder`, `tokens` — or `preprocessor`, `encoder`, `uncachedDecoder`, `cachedDecoder`, `tokens` |
| `whisper` | Whisper, distil-whisper | `encoder`, `decoder`, `tokens` |
| `transducer` | Parakeet, Zipformer | `encoder`, `decoder`, `joiner`, `tokens` |
| `sense_voice` | SenseVoice | `model`, `tokens` |

Options (`--option key=value`):

- `whisper`: `language` (for example `de`; empty means auto-detect), `task` (`transcribe` or `translate`).
- `transducer`: `modelType` (`nemo_transducer` for Parakeet).
- `sense_voice`: `language` (default `auto`), `useInverseTextNormalization` (`true` or `false`).

Whisper listens to at most 30 seconds per recording; other types allow 60 seconds.

### Manifest format

A pack is a zip with `manifest.json` (put it first) and the files it names, either at the root or
inside one folder. Only the named files plus `LICENSE`, `NOTICE` and `README.md` are kept.

```json
{
  "schema": 1,
  "kind": "voice",
  "id": "moonshine-base-en",
  "name": "English (Moonshine base)",
  "version": 1,
  "languages": ["en"],
  "type": "moonshine",
  "files": {
    "encoder": "encoder_model.ort",
    "mergedDecoder": "decoder_model_merged.ort",
    "tokens": "tokens.txt"
  },
  "options": {}
}
```

- `id`: lowercase letters, digits, `.`, `_`, `-`; installing a pack with the same id replaces it.
- File names: letters, digits, `.`, `_`, `-`, no folders.
