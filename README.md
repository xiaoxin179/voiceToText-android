# voiceToText-android

Standalone Android app for local, offline speech transcription.

This is intentionally a separate project from the Windows/Python repository at
`voiceToText`. It does not depend on the desktop app, a computer API, MCP, CLI,
DeepSeek, or any cloud transcription service.

## Scope

- Select either the phone microphone or the phone's internal playback audio.
- Run Whisper locally on the Android device through `whisper.cpp`.
- Use the Vulkan GPU backend on 64-bit ARM phones when the device and driver
  support it, with automatic CPU fallback when Vulkan initialization fails.
- Select a local model and keep the selected model in the app's fixed model directory.
- Download models with progress reporting, pause/resume, HTTP Range based resume,
  and automatic fallback between the mainland-friendly `hf-mirror.com` endpoint
  and the official Hugging Face endpoint.
- Show download state immediately after tapping, including network wait and failures.
- Display download sizes with the correct binary units (KB, MB, and GB).
- Preserve the recognition text as returned by the local model.
- Save the raw transcript on the phone for later processing on a computer.
- Show live input-signal, captured-time, and processed-chunk diagnostics.
- Separate listening and local-model management into a two-tab bottom navigation.
- Spool captured chunks to a disk-backed queue so slow on-device inference does not interrupt audio capture.
- Enable persistent diagnostic logging from the profile tab, with a live event
  view plus copy, share, and clear actions. Logs cover UI operations,
  permissions, audio diagnostics, model downloads, inference timing, and the
  selected compute backend without storing transcript content.

The first implementation targets Android 10 or newer. The debug build includes
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` native libraries; arm64 is the
primary phone target and the only ABI that includes the Vulkan backend. The
other ABIs continue to use the CPU backend. The listening screen shows the
backend selected for the loaded model. System playback capture requires the
user to approve a MediaProjection request.
On Android 14 or newer, the app requests the entire default display so playback
from the app opened after authorization is not excluded by single-app sharing.
Some apps and protected media can refuse playback capture, which is an Android
platform limitation rather than a transcription setting.

## Project layout

```text
app/src/main/java/.../model       model catalog and resumable downloads
app/src/main/java/.../capture     AudioRecord and foreground capture service
app/src/main/java/.../asr         local Whisper JNI adapter
app/src/main/java/.../transcript  raw transcript state and file output
app/src/main/java/.../debug       opt-in diagnostic logging and log history
app/src/main/cpp                  JNI bridge and whisper.cpp build integration
```

The native speech engine source is vendored at
`third_party/whisper.cpp-v1.9.1`, from the official
[`ggml-org/whisper.cpp`](https://github.com/ggml-org/whisper.cpp) `v1.9.1`
release. Keeping this pinned source in the repository makes multi-ABI builds
reproducible without a build-time GitHub clone. The Android app owns the JNI
adapter and does not copy the Windows project's Python runtime or service
layer.

The Vulkan build also pins the official Khronos Vulkan and SPIR-V headers at
the matching `1.3.296` release under `third_party`. They are compile-time
dependencies and do not introduce a network or cloud runtime dependency.

The downloadable Whisper files come from the `ggerganov/whisper.cpp` model
repository on Hugging Face. The app tries `hf-mirror.com` first because direct
access to Hugging Face can time out on some mobile networks, then falls back to
the official Hugging Face URL. Both sources provide the same GGML model files;
the mirror is only a transport fallback, not a different model.

## Build

Install Android Studio with the Android SDK, NDK, and CMake 3.22.1. Building
the ARM64 Vulkan shaders on Windows also requires a native Windows C/C++
compiler. Set `VTT_HOST_TOOLCHAIN_BIN` to a directory containing
`x86_64-w64-mingw32-clang.exe` and `x86_64-w64-mingw32-clang++.exe`; the
llvm-mingw toolchain is supported. Then open this directory as an Android
project and run:

```powershell
./gradlew assembleDebug
```

The generated shader helper is statically linked, so its compiler runtime does
not need to be added to `PATH` while Gradle invokes it.

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
