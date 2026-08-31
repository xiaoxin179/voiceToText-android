package com.xiaoxin.voicetotext.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxin.voicetotext.android.capture.AudioCaptureService
import com.xiaoxin.voicetotext.android.model.DownloadPhase
import com.xiaoxin.voicetotext.android.model.ModelDefinition
import com.xiaoxin.voicetotext.android.model.ModelDownloadManager
import com.xiaoxin.voicetotext.android.model.ModelDownloadState
import com.xiaoxin.voicetotext.android.transcript.TranscriptionState

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingStart: PendingStart? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
            val selectedModelId by viewModel.selectedModelId.collectAsStateWithLifecycle()
            val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
            val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
            VoiceToTextTheme {
                VoiceToTextScreen(
                    models = viewModel.models,
                    selectedModelId = selectedModelId,
                    downloadState = downloadState,
                    transcriptionState = transcriptionState,
                    modelDirectory = viewModel.modelDirectory,
                    isInstalled = viewModel::isInstalled,
                    onModelSelected = viewModel::selectModel,
                    onDownload = viewModel::downloadSelected,
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
            toast("请先下载并选择一个本地模型")
            return
        }
        val pending = PendingStart(source, modelPath)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VoiceToTextScreen(
    models: List<ModelDefinition>,
    selectedModelId: String,
    downloadState: ModelDownloadState,
    transcriptionState: TranscriptionState,
    modelDirectory: String,
    isInstalled: (ModelDefinition) -> Boolean,
    onModelSelected: (String) -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    var source by rememberSaveable { mutableStateOf(AudioCaptureService.SOURCE_SYSTEM) }
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.first()
    val selectedInstalled = isInstalled(selectedModel)
    val selectedDownloading = downloadState.modelId == selectedModel.id && downloadState.phase == DownloadPhase.DOWNLOADING
    val selectedDownloadInProgress = downloadState.modelId == selectedModel.id && downloadState.phase in setOf(
        DownloadPhase.DOWNLOADING,
        DownloadPhase.PAUSED,
        DownloadPhase.FAILED,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("本地语音转文字", fontWeight = FontWeight.Bold)
                        Text("手机端离线识别", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SectionTitle("本地模型")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModelSelector(models, selectedModel, onModelSelected)
                    Text(selectedModel.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "模型目录：$modelDirectory",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedDownloadInProgress) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            downloadProgressLabel(downloadState),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (downloadState.modelId == selectedModel.id && downloadState.error != null) {
                        Text(
                            "下载失败：${downloadState.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            selectedDownloading -> Button(onClick = onPauseDownload) { Text("暂停下载") }
                            selectedInstalled -> OutlinedButton(onClick = onDownload) { Text("已下载，重新校验") }
                            else -> Button(onClick = onDownload) { Text("下载模型") }
                        }
                        if (downloadState.modelId == selectedModel.id && downloadState.phase == DownloadPhase.PAUSED) {
                            Button(onClick = onDownload) { Text("继续下载") }
                        }
                    }
                }
            }

            SectionTitle("监听来源")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceOption("系统音频", AudioCaptureService.SOURCE_SYSTEM, source) { source = it }
                        SourceOption("麦克风", AudioCaptureService.SOURCE_MIC, source) { source = it }
                    }
                    Text(
                        if (source == AudioCaptureService.SOURCE_SYSTEM) {
                            "采集手机正在播放的音频；开始时需要授权系统音频捕获。"
                        } else {
                            "采集手机麦克风输入；开始时需要授权麦克风。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = selectedInstalled && !transcriptionState.running,
                            onClick = { onStart(source) },
                        ) {
                            Text("开始监听")
                        }
                        Button(
                            enabled = transcriptionState.running,
                            onClick = onStop,
                        ) {
                            Text("停止")
                        }
                        TextButton(onClick = onClear) { Text("清空文字") }
                    }
                }
            }

            SectionTitle("运行状态")
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(transcriptionState.status, fontWeight = FontWeight.Bold)
                    if (transcriptionState.running) {
                        Text("来源：${sourceLabel(transcriptionState.source)}")
                        Text("模型：${transcriptionState.modelName}")
                    }
                    transcriptionState.transcriptPath?.let {
                        Text("已保存：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    transcriptionState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            SectionTitle("原始文字稿")
            Card {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = transcriptionState.rawText.ifEmpty { "这里显示未经处理的原始识别文字" },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ModelSelector(
    models: List<ModelDefinition>,
    selected: ModelDefinition,
    onSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("当前模型：${selected.displayName}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text("${model.displayName}  ·  ${model.description}") },
                    onClick = {
                        onSelected(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceOption(
    label: String,
    value: String,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .selectable(
                selected = value == selected,
                onClick = { onSelected(value) },
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = value == selected, onClick = null)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider()
}

private fun sourceLabel(source: String): String = when (source) {
    AudioCaptureService.SOURCE_MIC -> "麦克风"
    AudioCaptureService.SOURCE_SYSTEM -> "系统音频"
    else -> source
}

private fun downloadProgressLabel(state: ModelDownloadState): String {
    val downloaded = ModelDownloadManager.formatBytes(state.downloadedBytes)
    val total = if (state.totalBytes > 0L) ModelDownloadManager.formatBytes(state.totalBytes) else "未知大小"
    val percent = if (state.totalBytes > 0L) " ${(state.progress * 100).toInt()}%" else ""
    val phase = when (state.phase) {
        DownloadPhase.PAUSED -> "已暂停"
        DownloadPhase.FAILED -> "等待重试"
        else -> "下载中"
    }
    return "$phase：$downloaded / $total$percent"
}

@Composable
private fun VoiceToTextTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
