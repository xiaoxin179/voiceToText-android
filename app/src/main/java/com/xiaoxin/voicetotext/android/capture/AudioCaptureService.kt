package com.xiaoxin.voicetotext.android.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xiaoxin.voicetotext.android.R
import com.xiaoxin.voicetotext.android.asr.LocalWhisperEngine
import com.xiaoxin.voicetotext.android.asr.GpuSafetyPolicy
import com.xiaoxin.voicetotext.android.debug.DebugLogger
import com.xiaoxin.voicetotext.android.model.ModelCatalog
import com.xiaoxin.voicetotext.android.transcript.TranscriptionSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

class AudioCaptureService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val recognitionDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-recognition")
    }.asCoroutineDispatcher()
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private val stopRequested = AtomicBoolean(false)
    private val releasingProjection = AtomicBoolean(false)
    private val terminationReason = AtomicReference("capture_completed")

    override fun onCreate() {
        super.onCreate()
        DebugLogger.initialize(applicationContext)
        DebugLogger.log("SERVICE", "AudioCaptureService 创建")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.log("SERVICE", "收到命令 action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopCapture(stopService = false)
        stopRequested.set(false)
        releasingProjection.set(false)
        terminationReason.set("capture_completed")
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SYSTEM
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        DebugLogger.log("CAPTURE", "准备监听 source=$source model=${modelPath?.let { File(it).name }}")
        if (modelPath.isNullOrBlank()) {
            DebugLogger.log("ERROR", "没有选择可用的本地模型")
            TranscriptionSession.failed("没有选择可用的本地模型")
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log("ERROR", "服务启动时缺少录音权限")
            TranscriptionSession.failed("没有获得录音权限")
            stopSelf()
            return
        }

        val foregroundType = if (source == SOURCE_SYSTEM) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), foregroundType)
            DebugLogger.log("SERVICE", "前台服务启动 foregroundType=$foregroundType")
        } catch (error: Exception) {
            DebugLogger.log("ERROR", "无法启动前台监听服务", error)
            TranscriptionSession.failed("无法启动前台监听服务：${error.message}")
            stopSelf()
            return
        }

        val projectionResult = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0)
        val projectionData = intent.getProjectionDataCompat(EXTRA_PROJECTION_DATA)
        val useGpu = GpuSafetyPolicy.shouldUseGpu()
        val sessionDescription = "source=$source model=${File(modelPath).name} gpuRequested=$useGpu " +
            "startedAt=${System.currentTimeMillis()}"
        DebugLogger.markCaptureActive(sessionDescription)
        captureJob = serviceScope.launch {
            try {
                runCapture(source, modelPath, useGpu, projectionResult, projectionData)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "audio capture failed", error)
                terminationReason.set("capture_error:${error.javaClass.simpleName}")
                DebugLogger.log("ERROR", "音频监听异常", error)
                TranscriptionSession.failed(error.message ?: "音频监听失败")
            } finally {
                DebugLogger.log("SERVICE", "释放音频采集资源 reason=${terminationReason.get()}")
                audioRecord?.release()
                audioRecord = null
                releasingProjection.set(true)
                mediaProjectionCallback?.let { callback ->
                    runCatching { mediaProjection?.unregisterCallback(callback) }
                }
                mediaProjectionCallback = null
                mediaProjection?.stop()
                mediaProjection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                DebugLogger.markCaptureFinished(sessionDescription, terminationReason.get())
                stopSelf()
            }
        }
    }

    private suspend fun runCapture(
        source: String,
        modelPath: String,
        useGpu: Boolean,
        projectionResult: Int,
        projectionData: Intent?,
    ) = coroutineScope {
        val projection = if (source == SOURCE_SYSTEM) {
            requireNotNull(projectionData) { "没有获得系统音频采集授权" }
            val manager = getSystemService(MediaProjectionManager::class.java)
            manager.getMediaProjection(projectionResult, projectionData).also {
                mediaProjection = it
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (releasingProjection.get()) {
                            DebugLogger.log("PROJECTION", "MediaProjection 随服务清理正常停止")
                            return
                        }
                        terminationReason.set("media_projection_revoked")
                        DebugLogger.log(
                            "PROJECTION",
                            "MediaProjection 被系统或用户撤销，系统音频采集将结束",
                        )
                        TranscriptionSession.failed("系统已停止屏幕/音频捕获授权")
                        stopRequested.set(true)
                        audioRecord?.let { record -> runCatching { record.stop() } }
                    }
                }
                mediaProjectionCallback = callback
                it.registerCallback(callback, Handler(Looper.getMainLooper()))
                DebugLogger.log("CAPTURE", "MediaProjection 已建立")
            }
        } else {
            null
        }
        val record = buildAudioRecord(source, projection)
        DebugLogger.log(
            "AUDIO",
            "AudioRecord 已初始化 source=$source sampleRate=${record.sampleRate} channelCount=${record.channelCount}",
        )
        audioRecord = record
        val queueDirectory = File(cacheDir, "transcription-queue").apply {
            deleteRecursively()
            mkdirs()
        }
        val channel = Channel<File>(capacity = Channel.UNLIMITED)
        val chunker = PcmChunker(record.sampleRate)
        val outputRoot = filesDir
        val modelName = ModelCatalog.all.firstOrNull { it.fileName == File(modelPath).name }?.displayName ?: File(modelPath).name
        TranscriptionSession.started(source, modelName)
        DebugLogger.log("CAPTURE", "监听会话开始 source=$source model=$modelName")

        val recognizer = launch(recognitionDispatcher) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            LocalWhisperEngine().use { engine ->
                val loadStartedAt = SystemClock.elapsedRealtime()
                DebugLogger.log("ASR", "开始加载模型 model=$modelName gpuRequested=$useGpu flashAttention=false")
                engine.open(modelPath, useGpu)
                TranscriptionSession.backend(engine.backend)
                DebugLogger.log(
                    "ASR",
                    "模型加载完成 model=$modelName backend=${engine.backend} elapsedMs=${SystemClock.elapsedRealtime() - loadStartedAt}",
                )
                for (chunkFile in channel) {
                    TranscriptionSession.status("本地识别中")
                    val startedAt = SystemClock.elapsedRealtime()
                    val rawText = try {
                        engine.transcribe(readPcm16(chunkFile))
                    } finally {
                        chunkFile.delete()
                    }
                    val inferenceMillis = SystemClock.elapsedRealtime() - startedAt
                    TranscriptionSession.chunkProcessed(inferenceMillis)
                    DebugLogger.log(
                        "ASR",
                        "片段识别完成 file=${chunkFile.name} elapsedMs=$inferenceMillis chars=${rawText.length} blank=${rawText.isBlank()}",
                    )
                    if (rawText.isBlank()) {
                        TranscriptionSession.status("收到音频，但当前片段未识别出文字")
                    } else {
                        TranscriptionSession.appendRaw(rawText)
                        TranscriptionSession.status(
                            if (stopRequested.get()) "正在完成剩余片段" else "正在监听",
                        )
                    }
                }
            }
        }

        val reader = launch(Dispatchers.IO) {
            val buffer = ShortArray(maxOf(record.sampleRate / 10, 1024))
            var capturedSamples = 0L
            var lastReportedSecond = 0
            var reportRms = 0f
            var signalDetected = false
            var noSignalWarningLogged = false
            var readFailureLogged = false
            var chunkIndex = 0L
            record.startRecording()
            while (isActive && !stopRequested.get()) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (!readFailureLogged) {
                        DebugLogger.log("AUDIO", "AudioRecord 读取失败 code=$count")
                        readFailureLogged = true
                    }
                    continue
                }
                val samples = FloatArray(count) { index -> buffer[index] / 32768.0f }
                val rms = audioRms(samples)
                capturedSamples += count
                reportRms = maxOf(reportRms, rms)
                val capturedSeconds = (capturedSamples / record.sampleRate).toInt()
                if (capturedSeconds > lastReportedSecond) {
                    TranscriptionSession.audioProgress(reportRms, capturedSeconds)
                    if (capturedSeconds % DEBUG_AUDIO_INTERVAL_SECONDS == 0) {
                        DebugLogger.log(
                            "AUDIO",
                            "采集进度 seconds=$capturedSeconds rms=$reportRms queued=${TranscriptionSession.state.value.queuedChunks}",
                        )
                    }
                    reportRms = 0f
                    lastReportedSecond = capturedSeconds
                }
                if (!signalDetected && rms >= MIN_AUDIO_RMS) {
                    signalDetected = true
                    DebugLogger.log("AUDIO", "首次检测到有效信号 seconds=$capturedSeconds rms=$rms")
                    TranscriptionSession.status("已检测到音频，正在监听")
                } else if (!signalDetected && capturedSeconds >= NO_SIGNAL_WARNING_SECONDS) {
                    val message = if (source == SOURCE_SYSTEM) {
                        "未检测到系统音频；请确认授权整个屏幕，或当前应用允许音频捕获"
                    } else {
                        "未检测到麦克风声音"
                    }
                    if (!noSignalWarningLogged) {
                        DebugLogger.log("AUDIO", "$message seconds=$capturedSeconds rms=$rms")
                        noSignalWarningLogged = true
                    }
                    TranscriptionSession.status(message)
                }
                for (chunk in chunker.append(samples)) {
                    if (audioRms(chunk) >= MIN_AUDIO_RMS) {
                        val chunkFile = File(queueDirectory, "%08d.pcm".format(chunkIndex++))
                        writePcm16(chunkFile, chunk)
                        TranscriptionSession.chunkQueued()
                        DebugLogger.log(
                            "AUDIO",
                            "片段入队 index=$chunkIndex samples=${chunk.size} rms=${audioRms(chunk)}",
                        )
                        channel.send(chunkFile)
                    }
                }
            }
        }

        val monitor = launch(Dispatchers.Default) {
            while (isActive) {
                delay(DEBUG_HEARTBEAT_INTERVAL_MILLIS)
                val runtime = Runtime.getRuntime()
                val state = TranscriptionSession.state.value
                DebugLogger.log(
                    "HEARTBEAT",
                    "serviceAlive=true source=$source audioState=${audioRecord?.recordingState} " +
                        "capturedSeconds=${state.capturedSeconds} queued=${state.queuedChunks} " +
                        "processed=${state.processedChunks} rms=${state.inputRms} " +
                        "heapUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) / MIB} " +
                        "heapMaxMb=${runtime.maxMemory() / MIB}",
                )
            }
        }

        try {
            reader.join()
        } finally {
            monitor.cancel()
            reader.cancel()
            runCatching { record.stop() }
            chunker.flush()
                ?.takeIf { audioRms(it) >= MIN_AUDIO_RMS }
                ?.let { chunk ->
                    val chunkFile = File(queueDirectory, "%08d.pcm".format(Long.MAX_VALUE))
                    writePcm16(chunkFile, chunk)
                    TranscriptionSession.chunkQueued()
                    DebugLogger.log("AUDIO", "尾部片段入队 samples=${chunk.size} rms=${audioRms(chunk)}")
                    if (channel.trySend(chunkFile).isFailure) chunkFile.delete()
                }
            channel.close()
            if (TranscriptionSession.state.value.queuedChunks > 0) {
                TranscriptionSession.status("正在完成剩余片段")
            }
            recognizer.join()
            queueDirectory.deleteRecursively()
            TranscriptionSession.stopped(outputRoot)
            DebugLogger.log(
                "CAPTURE",
                "监听会话结束 seconds=${TranscriptionSession.state.value.capturedSeconds} chunks=${TranscriptionSession.state.value.processedChunks}",
            )
        }
    }

    private fun buildAudioRecord(source: String, projection: MediaProjection?): AudioRecord {
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "没有获得录音权限"
        }
        val rates = intArrayOf(48_000, 44_100, 16_000)
        for (sampleRate in rates) {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val record = try {
                if (source == SOURCE_SYSTEM) {
                    val config = AudioPlaybackCaptureConfiguration.Builder(requireNotNull(projection))
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBuffer * 2)
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                } else {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBuffer * 2)
                        .build()
                }
            } catch (error: Exception) {
                Log.w(TAG, "cannot open audio record at $sampleRate", error)
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                DebugLogger.log("AUDIO", "采集设备可用 sampleRate=$sampleRate source=$source")
                return record
            }
            DebugLogger.log("AUDIO", "采集设备初始化失败 sampleRate=$sampleRate source=$source")
            record.release()
        }
        throw IllegalStateException("无法打开${if (source == SOURCE_SYSTEM) "系统播放音频" else "麦克风"}采集设备")
    }

    private fun stopCapture(stopService: Boolean = true) {
        DebugLogger.log("CAPTURE", "停止请求 stopService=$stopService active=${captureJob != null}")
        terminationReason.set(if (stopService) "user_stop" else "service_restart_or_destroy")
        stopRequested.set(true)
        audioRecord?.let { runCatching { it.stop() } }
        if (stopService) {
            if (captureJob == null) {
                stopSelf()
            } else {
                TranscriptionSession.captureStopping()
            }
        } else {
            captureJob?.cancel()
            captureJob = null
        }
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("本地语音转文字")
            .setContentText("正在监听并在手机本地识别")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "本地转写监听", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        DebugLogger.log("SERVICE", "AudioCaptureService 销毁")
        stopCapture(stopService = false)
        serviceScope.coroutineContext.cancel()
        recognitionDispatcher.close()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLogger.log("SERVICE", "应用任务从最近任务中移除，前台监听服务仍应继续运行")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        val runtime = Runtime.getRuntime()
        DebugLogger.log(
            "MEMORY",
            "AudioCaptureService onTrimMemory level=$level heapUsedMb=" +
                "${(runtime.totalMemory() - runtime.freeMemory()) / MIB} heapMaxMb=${runtime.maxMemory() / MIB}",
        )
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        DebugLogger.log("MEMORY", "AudioCaptureService 收到 onLowMemory")
        super.onLowMemory()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.xiaoxin.voicetotext.android.action.START"
        const val ACTION_STOP = "com.xiaoxin.voicetotext.android.action.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val SOURCE_MIC = "mic"
        const val SOURCE_SYSTEM = "system"

        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioCaptureService"
        private const val MIN_AUDIO_RMS = 0.001f
        private const val NO_SIGNAL_WARNING_SECONDS = 3
        private const val DEBUG_AUDIO_INTERVAL_SECONDS = 5
        private const val DEBUG_HEARTBEAT_INTERVAL_MILLIS = 5_000L
        private const val MIB = 1024L * 1024L

        fun start(
            context: Context,
            source: String,
            modelPath: String,
            projectionResult: Int = 0,
            projectionData: Intent? = null,
        ) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_MODEL_PATH, modelPath)
                .putExtra(EXTRA_PROJECTION_RESULT, projectionResult)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun Intent.getProjectionDataCompat(key: String): Intent? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
