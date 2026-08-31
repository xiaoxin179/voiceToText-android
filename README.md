# voiceToText-android

Standalone Android app for local, offline speech transcription.

This is intentionally a separate project from the Windows/Python repository at
`voiceToText`. It does not depend on the desktop app, a computer API, MCP, CLI,
DeepSeek, or any cloud transcription service.

## Scope

- Select either the phone microphone or the phone's internal playback audio.
- Run Whisper locally on the Android device through `whisper.cpp`.
- Select a local model and keep the selected model in the app's fixed model directory.
- Download models with progress reporting, pause/resume, HTTP Range based resume,
  and automatic fallback between the mainland-friendly `hf-mirror.com` endpoint
  and the official Hugging Face endpoint.
- Show download state immediately after tapping, including network wait and failures.
- Preserve the recognition text as returned by the local model.
- Save the raw transcript on the phone for later processing on a computer.

The first implementation targets Android 10 or newer. The debug build includes
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` native libraries; arm64 is the
primary phone target. System playback capture requires the user to approve a
MediaProjection request.
Some apps and protected media can refuse playback capture, which is an Android
platform limitation rather than a transcription setting.

## Project layout

```text
app/src/main/java/.../model       model catalog and resumable downloads
app/src/main/java/.../capture     AudioRecord and foreground capture service
app/src/main/java/.../asr         local Whisper JNI adapter
app/src/main/java/.../transcript  raw transcript state and file output
app/src/main/cpp                  JNI bridge and whisper.cpp build integration
```

The native speech engine source is vendored at
`third_party/whisper.cpp-v1.9.1`, from the official
[`ggml-org/whisper.cpp`](https://github.com/ggml-org/whisper.cpp) `v1.9.1`
release. Keeping this pinned source in the repository makes multi-ABI builds
reproducible without a build-time GitHub clone. The Android app owns the JNI
adapter and does not copy the Windows project's Python runtime or service
layer.

The downloadable Whisper files come from the `ggerganov/whisper.cpp` model
repository on Hugging Face. The app tries `hf-mirror.com` first because direct
access to Hugging Face can time out on some mobile networks, then falls back to
the official Hugging Face URL. Both sources provide the same GGML model files;
the mirror is only a transport fallback, not a different model.

## Build

Install Android Studio with the Android SDK, NDK, and CMake 3.22.1. Then open
this directory as an Android project and run:

```powershell
./gradlew assembleDebug
```

The generated debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## First use

1. Install the APK on an Android 10+ arm64 device.
2. Download `Tiny` or `Base` in the model section.
3. Select the downloaded model.
4. Select `系统音频` for audio currently playing on the phone, or `麦克风`.
5. Tap `开始监听` and approve the requested Android permissions.
6. Tap `停止` to write the raw transcript under the app's files directory.

## License and upstream

The native engine is an external dependency from
[`ggml-org/whisper.cpp`](https://github.com/ggml-org/whisper.cpp). Review its
license and model licensing terms before redistributing an APK with models.
