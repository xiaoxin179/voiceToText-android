package com.xiaoxin.voicetotext.android.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DownloadPhase {
    IDLE,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

data class ModelDownloadState(
    val modelId: String? = null,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

private class DownloadControl {
    val pauseRequested = AtomicBoolean(false)
}

class ModelDownloadManager(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val currentControl = AtomicReference<DownloadControl?>(null)
    private val modelsDirectory = File(appContext.filesDir, "models")
    private val _state = MutableStateFlow(ModelDownloadState())

    val state: StateFlow<ModelDownloadState> = _state
    val modelDirectory: File
        get() = modelsDirectory

    init {
        modelsDirectory.mkdirs()
    }

    fun modelFile(model: ModelDefinition): File = File(modelsDirectory, model.fileName)

    fun isInstalled(model: ModelDefinition): Boolean = modelFile(model).isFile && modelFile(model).length() > 0L

    fun startOrResume(model: ModelDefinition) {
        if (isInstalled(model)) {
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.COMPLETED,
                downloadedBytes = modelFile(model).length(),
                totalBytes = modelFile(model).length(),
            )
            return
        }

        val control = DownloadControl()
        val oldControl = currentControl.getAndSet(control)
        oldControl?.pauseRequested?.set(true)
        executor.submit { download(model, control) }
    }

    fun pause() {
        currentControl.get()?.pauseRequested?.set(true)
    }

    fun sha256(model: ModelDefinition): String? {
        val file = modelFile(model)
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(model: ModelDefinition, control: DownloadControl) {
        val destination = modelFile(model)
        val partial = File(destination.path + ".part")
        var connection: HttpURLConnection? = null

        try {
            var existingBytes = partial.length()
            connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "voiceToText-android/0.1")
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                existingBytes = 0L
                if (partial.exists() && !partial.delete()) {
                    throw IOException("无法重置临时模型文件")
                }
            }
            if (responseCode !in 200..299) {
                throw IOException("模型服务器返回 HTTP $responseCode")
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (contentLength >= 0L) existingBytes + contentLength else -1L
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = existingBytes,
                totalBytes = totalBytes,
            )

            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existingBytes
                    while (true) {
                        if (control.pauseRequested.get()) {
                            _state.value = ModelDownloadState(
                                modelId = model.id,
                                phase = DownloadPhase.PAUSED,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                            )
                            return
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloaded += count
                        _state.value = ModelDownloadState(
                            modelId = model.id,
                            phase = DownloadPhase.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                        )
                    }
                    output.fd.sync()
                    if (totalBytes > 0L && downloaded < totalBytes) {
                        throw IOException("模型下载提前结束：$downloaded/$totalBytes")
                    }
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("无法替换旧模型文件")
                    }
                    if (!partial.renameTo(destination)) {
                        throw IOException("无法完成模型文件安装")
                    }
                    _state.value = ModelDownloadState(
                        modelId = model.id,
                        phase = DownloadPhase.COMPLETED,
                        downloadedBytes = downloaded,
                        totalBytes = if (totalBytes > 0L) totalBytes else downloaded,
                    )
                }
            }
        } catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            _state.value = ModelDownloadState(model.id, DownloadPhase.PAUSED, partial.length(), -1L)
        } catch (error: Exception) {
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.FAILED,
                downloadedBytes = partial.length(),
                totalBytes = _state.value.totalBytes,
                error = error.message ?: "模型下载失败",
            )
        } finally {
            connection?.disconnect()
            currentControl.compareAndSet(control, null)
        }
    }

    override fun close() {
        currentControl.get()?.pauseRequested?.set(true)
        executor.shutdownNow()
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit += 1
            }
            return "%.1f %s".format(value, units[unit])
        }
    }
}
