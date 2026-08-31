package com.xiaoxin.voicetotext.android.model

import android.content.Context
import com.xiaoxin.voicetotext.android.debug.DebugLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
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

private class HttpStatusException(val statusCode: Int) : IOException("模型服务器返回 HTTP $statusCode")

private enum class DownloadAttemptResult {
    COMPLETED,
    PAUSED,
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

    fun isInstalled(model: ModelDefinition): Boolean {
        val file = modelFile(model)
        return file.isFile && file.length() in 1L..model.maxDownloadBytes
    }

    fun startOrResume(model: ModelDefinition) {
        val destination = modelFile(model)
        if (destination.isFile && destination.length() > model.maxDownloadBytes) {
            destination.delete()
        }
        if (isInstalled(model)) {
            DebugLogger.log("DOWNLOAD", "模型已安装，跳过下载 id=${model.id} bytes=${destination.length()}")
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.COMPLETED,
                downloadedBytes = destination.length(),
                totalBytes = destination.length(),
            )
            return
        }

        if (_state.value.modelId == model.id && _state.value.phase == DownloadPhase.DOWNLOADING) {
            return
        }

        val control = DownloadControl()
        val oldControl = currentControl.getAndSet(control)
        oldControl?.pauseRequested?.set(true)
        val partial = partialFile(model)
        val previousState = _state.value
        val downloadedBytes = sanitizePartial(partial, model)
        DebugLogger.log("DOWNLOAD", "开始或继续下载 id=${model.id} resumedBytes=$downloadedBytes")
        _state.value = ModelDownloadState(
            modelId = model.id,
            phase = DownloadPhase.DOWNLOADING,
            downloadedBytes = downloadedBytes,
            totalBytes = if (previousState.modelId == model.id) previousState.totalBytes else -1L,
        )
        try {
            executor.submit { download(model, control) }
        } catch (error: RuntimeException) {
            DebugLogger.log("ERROR", "无法提交模型下载任务 id=${model.id}", error)
            currentControl.compareAndSet(control, null)
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.FAILED,
                downloadedBytes = sanitizePartial(partial, model),
                totalBytes = -1L,
                error = error.message ?: "无法启动模型下载",
            )
        }
    }

    fun pause() {
        DebugLogger.log("DOWNLOAD", "暂停下载")
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
        val partial = partialFile(model)
        var lastError: Exception? = null

        try {
            for ((index, url) in model.downloadUrls.withIndex()) {
                if (control.pauseRequested.get()) {
                    publishPaused(model, partial, -1L)
                    return
                }

                try {
                    DebugLogger.log(
                        "DOWNLOAD",
                        "连接下载源 id=${model.id} source=${index + 1}/${model.downloadUrls.size} host=${URL(url).host}",
                    )
                    when (downloadFromUrl(model, url, partial, destination, control)) {
                        DownloadAttemptResult.COMPLETED -> return
                        DownloadAttemptResult.PAUSED -> return
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    publishPaused(model, partial, -1L)
                    return
                } catch (error: Exception) {
                    DebugLogger.log(
                        "ERROR",
                        "下载源失败 id=${model.id} source=${index + 1}/${model.downloadUrls.size}",
                        error,
                    )
                    lastError = error
                    if (index < model.downloadUrls.lastIndex) {
                        _state.value = ModelDownloadState(
                            modelId = model.id,
                            phase = DownloadPhase.DOWNLOADING,
                            downloadedBytes = sanitizePartial(partial, model),
                            totalBytes = -1L,
                        )
                    }
                }
            }
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.FAILED,
                downloadedBytes = sanitizePartial(partial, model),
                totalBytes = -1L,
                error = userFacingError(lastError),
            )
            DebugLogger.log("ERROR", "所有模型下载源均失败 id=${model.id}", lastError)
        } finally {
            currentControl.compareAndSet(control, null)
        }
    }

    private fun downloadFromUrl(
        model: ModelDefinition,
        url: String,
        partial: File,
        destination: File,
        control: DownloadControl,
    ): DownloadAttemptResult {
        var connection: HttpURLConnection? = null
        try {
            var existingBytes = sanitizePartial(partial, model)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "voiceToText-android/0.1")
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val responseCode = connection.responseCode
            DebugLogger.log("DOWNLOAD", "服务器响应 id=${model.id} code=$responseCode resumedBytes=$existingBytes")
            if (responseCode !in 200..299) {
                throw HttpStatusException(responseCode)
            }

            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                existingBytes = 0L
                if (partial.exists() && !partial.delete()) {
                    throw IOException("无法重置临时模型文件")
                }
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (contentLength >= 0L && existingBytes <= model.maxDownloadBytes - contentLength) {
                existingBytes + contentLength
            } else {
                -1L
            }
            if (totalBytes > model.maxDownloadBytes) {
                throw IOException("模型文件大小异常：${formatBytes(totalBytes)}")
            }
            _state.value = ModelDownloadState(
                modelId = model.id,
                phase = DownloadPhase.DOWNLOADING,
                downloadedBytes = existingBytes,
                totalBytes = totalBytes,
            )

            var downloaded = existingBytes
            var paused = false
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (control.pauseRequested.get()) {
                            paused = true
                            break
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        if (downloaded > model.maxDownloadBytes) {
                            throw IOException("模型文件大小超过限制：${formatBytes(model.maxDownloadBytes)}")
                        }
                        if (totalBytes > 0L && downloaded > totalBytes) {
                            throw IOException("模型下载大小异常：$downloaded/$totalBytes")
                        }
                        output.write(buffer, 0, count)
                        _state.value = ModelDownloadState(
                            modelId = model.id,
                            phase = DownloadPhase.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                        )
                    }
                    output.fd.sync()
                }
            }

            if (paused) {
                _state.value = ModelDownloadState(
                    modelId = model.id,
                    phase = DownloadPhase.PAUSED,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                )
                DebugLogger.log("DOWNLOAD", "模型下载已暂停 id=${model.id} bytes=$downloaded")
                return DownloadAttemptResult.PAUSED
            }
            if (downloaded <= 0L) {
                throw IOException("模型下载结果为空")
            }
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
            DebugLogger.log("DOWNLOAD", "模型下载完成 id=${model.id} bytes=$downloaded")
            return DownloadAttemptResult.COMPLETED
        } finally {
            connection?.disconnect()
        }
    }

    private fun partialFile(model: ModelDefinition): File = File(modelFile(model).path + ".part")

    private fun sanitizePartial(file: File, model: ModelDefinition): Long {
        val length = file.length()
        if (length <= model.maxDownloadBytes) return length
        file.delete()
        return file.length().takeIf { it <= model.maxDownloadBytes } ?: 0L
    }

    private fun publishPaused(model: ModelDefinition, partial: File, totalBytes: Long) {
        _state.value = ModelDownloadState(
            modelId = model.id,
            phase = DownloadPhase.PAUSED,
            downloadedBytes = sanitizePartial(partial, model),
            totalBytes = totalBytes,
        )
        DebugLogger.log("DOWNLOAD", "模型下载已暂停 id=${model.id} bytes=${partial.length()}")
    }

    private fun userFacingError(error: Exception?): String {
        val detail = error?.message?.takeIf { it.isNotBlank() } ?: "未知网络错误"
        return when (error) {
            is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
            is UnknownHostException -> "无法连接模型下载源，请切换网络后重试：$detail"
            else -> "模型下载失败：$detail"
        }
    }

    override fun close() {
        currentControl.get()?.pauseRequested?.set(true)
        executor.shutdownNow()
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit += 1
            }
            return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
        }
    }
}
