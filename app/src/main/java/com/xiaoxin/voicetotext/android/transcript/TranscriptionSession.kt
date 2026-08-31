package com.xiaoxin.voicetotext.android.transcript

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TranscriptionState(
    val running: Boolean = false,
    val source: String = "",
    val modelName: String = "",
    val status: String = "未开始",
    val rawText: String = "",
    val inputRms: Float = 0f,
    val capturedSeconds: Int = 0,
    val processedChunks: Int = 0,
    val transcriptPath: String? = null,
    val error: String? = null,
)

object TranscriptionSession {
    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state

    fun started(source: String, modelName: String) {
        _state.value = TranscriptionState(
            running = true,
            source = source,
            modelName = modelName,
            status = "正在监听",
        )
    }

    fun status(message: String) {
        _state.update { it.copy(status = message) }
    }

    fun appendRaw(text: String) {
        if (text.isEmpty()) return
        _state.update { current ->
            val combined = if (current.rawText.isEmpty()) text else current.rawText + "\n" + text
            current.copy(rawText = combined)
        }
    }

    fun audioProgress(rms: Float, capturedSeconds: Int) {
        _state.update { current ->
            current.copy(inputRms = rms, capturedSeconds = capturedSeconds)
        }
    }

    fun chunkProcessed() {
        _state.update { current ->
            current.copy(processedChunks = current.processedChunks + 1)
        }
    }

    fun stopped(outputRoot: File) {
        val current = _state.value
        val transcriptDirectory = File(outputRoot, "transcripts")
        transcriptDirectory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(transcriptDirectory, "${stamp}-${current.source.ifEmpty { "audio" }}.txt")
        file.writeText(current.rawText, StandardCharsets.UTF_8)
        _state.value = current.copy(
            running = false,
            status = "监听结束",
            transcriptPath = file.absolutePath,
        )
    }

    fun failed(message: String) {
        _state.update { it.copy(running = false, status = "失败", error = message) }
    }

    fun clear() {
        _state.value = TranscriptionState()
    }
}
