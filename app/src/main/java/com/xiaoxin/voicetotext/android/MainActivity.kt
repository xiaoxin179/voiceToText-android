package com.xiaoxin.voicetotext.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxin.voicetotext.android.capture.AudioCaptureService
import com.xiaoxin.voicetotext.android.debug.DebugLogger
import com.xiaoxin.voicetotext.android.ui.VoiceToTextApp
import com.xiaoxin.voicetotext.android.ui.VoiceToTextTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingStart: PendingStart? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted) {
            DebugLogger.log("PERMISSION", "麦克风权限已授权")
            pendingStart?.let { pending ->
                pendingStart = null
                startCaptureAfterPermission(pending)
            }
        } else {
            DebugLogger.log("PERMISSION", "麦克风权限被拒绝")
            pendingStart = null
            toast("需要麦克风权限才能开始监听")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = pendingStart
        pendingStart = null
        if (result.resultCode != Activity.RESULT_OK || result.data == null || pending == null) {
            DebugLogger.log("PERMISSION", "系统音频捕获授权失败 resultCode=${result.resultCode}")
            toast("未获得系统音频采集授权")
            return@registerForActivityResult
        }
        DebugLogger.log("PERMISSION", "系统音频捕获已授权")
        startAudioService(pending, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLogger.initialize(applicationContext)
        DebugLogger.log("LIFECYCLE", "MainActivity 创建")
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        setContent {
            val selectedModelId = viewModel.selectedModelId.collectAsStateWithLifecycle().value
            val downloadState = viewModel.downloadState.collectAsStateWithLifecycle().value
            val transcriptionState = viewModel.transcriptionState.collectAsStateWithLifecycle().value
            val debugLogState = viewModel.debugLogState.collectAsStateWithLifecycle().value
            VoiceToTextTheme {
                VoiceToTextApp(
                    models = viewModel.models,
                    selectedModelId = selectedModelId,
                    downloadState = downloadState,
                    transcriptionState = transcriptionState,
                    debugLogState = debugLogState,
                    modelDirectory = viewModel.modelDirectory,
                    isInstalled = viewModel::isInstalled,
                    modelSizeBytes = viewModel::modelSizeBytes,
                    onModelSelected = viewModel::selectModel,
                    onDownloadModel = viewModel::downloadModel,
                    onPauseDownload = viewModel::pauseDownload,
                    onStart = ::requestStart,
                    onStop = ::stopCapture,
                    onClear = viewModel::clearTranscript,
                    onDebugLoggingChanged = viewModel::setDebugLogging,
                    onClearDebugLog = viewModel::clearDebugLog,
                    onCopyDebugLog = ::copyDebugLog,
                    onShareDebugLog = ::shareDebugLog,
                    onUiEvent = viewModel::logUiEvent,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DebugLogger.log("LIFECYCLE", "MainActivity 进入前台")
    }

    override fun onStop() {
        DebugLogger.log("LIFECYCLE", "MainActivity 进入后台")
        super.onStop()
    }

    override fun onDestroy() {
        DebugLogger.log("LIFECYCLE", "MainActivity 销毁 changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }

    private fun requestStart(source: String) {
        DebugLogger.log("CAPTURE", "用户请求开始监听 source=$source")
        val modelPath = viewModel.modelPath()
        if (modelPath == null) {
            DebugLogger.log("CAPTURE", "开始监听失败：模型未安装")
            toast("请先安装并选择一个本地模型")
            return
        }
        val pending = PendingStart(source, modelPath)
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLogger.log("PERMISSION", "请求麦克风权限")
            pendingStart = pending
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permissionLauncher.launch(permissions)
            return
        }
        startCaptureAfterPermission(pending)
    }

    private fun startCaptureAfterPermission(pending: PendingStart) {
        if (pending.source == AudioCaptureService.SOURCE_SYSTEM) {
            DebugLogger.log("PERMISSION", "请求系统音频捕获授权")
            pendingStart = pending
            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay(),
                )
            } else {
                mediaProjectionManager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(captureIntent)
        } else {
            startAudioService(pending)
        }
    }

    private fun startAudioService(
        pending: PendingStart,
        projectionResult: Int = 0,
        projectionData: Intent? = null,
    ) {
        DebugLogger.log("CAPTURE", "启动音频服务 source=${pending.source}")
        AudioCaptureService.start(
            context = this,
            source = pending.source,
            modelPath = pending.modelPath,
            projectionResult = projectionResult,
            projectionData = projectionData,
        )
    }

    private fun stopCapture() {
        DebugLogger.log("CAPTURE", "用户请求停止监听")
        AudioCaptureService.stop(this)
        toast("已停止采集，正在完成剩余识别")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyDebugLog() {
        val content = DebugLogger.readCurrentLog()
        if (content.isBlank()) {
            toast("当前没有可复制的日志")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("voiceToText 调试日志", content))
        DebugLogger.log("UI", "复制调试日志")
        toast("日志已复制")
    }

    private fun shareDebugLog() {
        val file = DebugLogger.currentLogFile()
        if (file == null) {
            toast("当前没有可分享的日志")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("voiceToText 调试日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        DebugLogger.log("UI", "分享调试日志 file=${file.name}")
        startActivity(Intent.createChooser(intent, "分享调试日志"))
    }

    private data class PendingStart(val source: String, val modelPath: String)
}
