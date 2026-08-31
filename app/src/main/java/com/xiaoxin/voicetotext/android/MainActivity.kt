package com.xiaoxin.voicetotext.android

import android.Manifest
import android.app.Activity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxin.voicetotext.android.capture.AudioCaptureService
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
            pendingStart?.let { pending ->
                pendingStart = null
                startCaptureAfterPermission(pending)
            }
        } else {
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
            toast("未获得系统音频采集授权")
            return@registerForActivityResult
        }
        startAudioService(pending, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        setContent {
            val selectedModelId = viewModel.selectedModelId.collectAsStateWithLifecycle().value
            val downloadState = viewModel.downloadState.collectAsStateWithLifecycle().value
            val transcriptionState = viewModel.transcriptionState.collectAsStateWithLifecycle().value
            VoiceToTextTheme {
                VoiceToTextApp(
                    models = viewModel.models,
                    selectedModelId = selectedModelId,
                    downloadState = downloadState,
                    transcriptionState = transcriptionState,
                    modelDirectory = viewModel.modelDirectory,
                    isInstalled = viewModel::isInstalled,
                    modelSizeBytes = viewModel::modelSizeBytes,
                    onModelSelected = viewModel::selectModel,
                    onDownloadModel = viewModel::downloadModel,
                    onPauseDownload = viewModel::pauseDownload,
                    onStart = ::requestStart,
                    onStop = ::stopCapture,
                    onClear = viewModel::clearTranscript,
                )
            }
        }
    }

    private fun requestStart(source: String) {
        val modelPath = viewModel.modelPath()
        if (modelPath == null) {
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
        AudioCaptureService.start(
            context = this,
            source = pending.source,
            modelPath = pending.modelPath,
            projectionResult = projectionResult,
            projectionData = projectionData,
        )
    }

    private fun stopCapture() {
        AudioCaptureService.stop(this)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class PendingStart(val source: String, val modelPath: String)
}
