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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xiaoxin.voicetotext.android.R
import com.xiaoxin.voicetotext.android.asr.LocalWhisperEngine
import com.xiaoxin.voicetotext.android.model.ModelCatalog
import com.xiaoxin.voicetotext.android.transcript.TranscriptionSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

class AudioCaptureService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        stopCapture(stopService = false)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SYSTEM
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank()) {
            TranscriptionSession.failed("没有选择可用的本地模型")
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
        } catch (error: Exception) {
            TranscriptionSession.failed("无法启动前台监听服务：${error.message}")
            stopSelf()
            return
        }

        val projectionResult = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0)
        val projectionData = intent.getProjectionDataCompat(EXTRA_PROJECTION_DATA)
        captureJob = serviceScope.launch {
            try {
                runCapture(source, modelPath, projectionResult, projectionData)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "audio capture failed", error)
                TranscriptionSession.failed(error.message ?: "音频监听失败")
            } finally {
                audioRecord?.release()
                audioRecord = null
                mediaProjection?.stop()
                mediaProjection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runCapture(
        source: String,
        modelPath: String,
        projectionResult: Int,
        projectionData: Intent?,
    ) = coroutineScope {
        val projection = if (source == SOURCE_SYSTEM) {
            requireNotNull(projectionData) { "没有获得系统音频采集授权" }
            val manager = getSystemService(MediaProjectionManager::class.java)
            manager.getMediaProjection(projectionResult, projectionData).also { mediaProjection = it }
        } else {
            null
        }
        val record = buildAudioRecord(source, projection)
        audioRecord = record
        val channel = Channel<FloatArray>(capacity = 4)
        val chunker = PcmChunker(record.sampleRate)
        val outputRoot = filesDir
        val modelName = ModelCatalog.all.firstOrNull { it.fileName == File(modelPath).name }?.displayName ?: File(modelPath).name
        TranscriptionSession.started(source, modelName)

        val recognizer = launch(Dispatchers.Default) {
            LocalWhisperEngine().use { engine ->
                engine.open(modelPath)
                for (chunk in channel) {
                    TranscriptionSession.status("本地识别中")
                    val rawText = engine.transcribe(chunk)
                    TranscriptionSession.chunkProcessed()
                    if (rawText.isBlank()) {
                        TranscriptionSession.status("收到音频，但当前片段未识别出文字")
                    } else {
                        TranscriptionSession.appendRaw(rawText)
                        TranscriptionSession.status("正在监听")
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
            record.startRecording()
            while (isActive) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                val samples = FloatArray(count) { index -> buffer[index] / 32768.0f }
                val rms = audioRms(samples)
                capturedSamples += count
                reportRms = maxOf(reportRms, rms)
                val capturedSeconds = (capturedSamples / record.sampleRate).toInt()
                if (capturedSeconds > lastReportedSecond) {
                    TranscriptionSession.audioProgress(reportRms, capturedSeconds)
                    reportRms = 0f
                    lastReportedSecond = capturedSeconds
                }
                if (!signalDetected && rms >= MIN_AUDIO_RMS) {
                    signalDetected = true
                    TranscriptionSession.status("已检测到音频，正在监听")
                } else if (!signalDetected && capturedSeconds >= NO_SIGNAL_WARNING_SECONDS) {
                    val message = if (source == SOURCE_SYSTEM) {
                        "未检测到系统音频；请确认授权整个屏幕，或当前应用允许音频捕获"
                    } else {
                        "未检测到麦克风声音"
                    }
                    TranscriptionSession.status(message)
                }
                for (chunk in chunker.append(samples)) {
                    if (audioRms(chunk) >= MIN_AUDIO_RMS) {
                        channel.send(chunk)
                    }
                }
            }
        }

        try {
            reader.join()
        } finally {
            reader.cancel()
            runCatching { record.stop() }
            chunker.flush()
                ?.takeIf { audioRms(it) >= MIN_AUDIO_RMS }
                ?.let { channel.trySend(it) }
            channel.close()
            recognizer.join()
            TranscriptionSession.stopped(outputRoot)
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
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        throw IllegalStateException("无法打开${if (source == SOURCE_SYSTEM) "系统播放音频" else "麦克风"}采集设备")
    }

    private fun stopCapture(stopService: Boolean = true) {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.let { runCatching { it.stop() } }
        if (stopService) stopSelf()
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
        stopCapture(stopService = false)
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
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
