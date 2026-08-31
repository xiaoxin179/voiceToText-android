package com.xiaoxin.voicetotext.android.asr

internal class WhisperNative private constructor() {
    companion object {
        @JvmStatic
        external fun nativeInit(modelPath: String): Long

        @JvmStatic
        external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String): String

        @JvmStatic
        external fun nativeGetBackend(handle: Long): String

        @JvmStatic
        external fun nativeFree(handle: Long)
    }
}

class LocalWhisperEngine : AutoCloseable {
    private var handle: Long = 0L
    private var nativeLoaded = false
    var backend: String = "未初始化"
        private set

    fun open(modelPath: String) {
        close()
        loadNativeLibrary()
        handle = WhisperNative.nativeInit(modelPath)
        check(handle != 0L) { "无法加载本地 Whisper 模型：$modelPath" }
        backend = WhisperNative.nativeGetBackend(handle)
    }

    fun transcribe(samples: FloatArray, language: String = "zh"): String {
        check(handle != 0L) { "本地 Whisper 模型尚未加载" }
        return WhisperNative.nativeTranscribe(handle, samples, language)
    }

    private fun loadNativeLibrary() {
        if (nativeLoaded) return
        System.loadLibrary("whisper_jni")
        nativeLoaded = true
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0L
        }
        backend = "未初始化"
    }
}
