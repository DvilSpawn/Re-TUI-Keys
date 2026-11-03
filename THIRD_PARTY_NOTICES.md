# Third-party notices

Re:TUI Keys is MIT licensed (see `LICENSE`). It includes the components below. The same texts ship
inside the app under **Settings → System & data → Open source licenses**.

## English word list — Android Open Source Project

`app/src/main/assets/latinime/en_US_wordlist.combined.gz` comes from AOSP LatinIME.
Copyright (c) 2008, The Android Open Source Project. Apache License 2.0; the notice is kept in
`app/src/main/assets/latinime/NOTICE`.

## AndroidX and Kotlin

AndroidX Activity and Core (The Android Open Source Project) and the Kotlin standard library
(JetBrains s.r.o. and Kotlin contributors) are used under the Apache License 2.0
(`LICENSES/Apache-2.0.txt`).

## sherpa-onnx 2.0.1

On-device speech recognition and voice activity detection runtime, by the k2-fsa authors
(Xiaomi Corporation and contributors). Apache License 2.0 (`LICENSES/Apache-2.0.txt`).
Source: https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1

## ONNX Runtime 1.28.2

Bundled inside sherpa-onnx as `libonnxruntime.so`. Copyright (c) Microsoft Corporation.
MIT License (`LICENSES/onnxruntime-MIT.txt`). ONNX Runtime includes further third-party
components; their notices are in ONNX Runtime's `ThirdPartyNotices.txt`, which the build fetches
(pinned by SHA-256) and packages into the app.
Source: https://github.com/microsoft/onnxruntime/tree/v1.28.2

## Silero VAD

`silero_vad.onnx`, used to detect speech while dictating. Copyright (c) 2020-present Silero Team.
MIT License (`LICENSES/Silero-VAD-MIT.txt`). The build fetches the model from the sherpa-onnx
model release, pinned by SHA-256.
Source: https://github.com/snakers4/silero-vad

## Voice packs

Voice packs are not part of the app; each `.retui-voice` file carries its model's own `LICENSE`.
The official English packs use Moonshine models, Copyright (c) 2025 Useful Sensors, Inc.
(dba Moonshine AI), MIT License. Moonshine models for other languages use a non-commercial license
and are not published as official packs.
