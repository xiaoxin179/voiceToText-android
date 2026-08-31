package com.xiaoxin.voicetotext.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoxin.voicetotext.android.capture.AudioCaptureService
import com.xiaoxin.voicetotext.android.model.DownloadPhase
import com.xiaoxin.voicetotext.android.model.ModelDefinition
import com.xiaoxin.voicetotext.android.model.ModelDownloadManager
import com.xiaoxin.voicetotext.android.model.ModelDownloadState
import com.xiaoxin.voicetotext.android.transcript.TranscriptionState
import java.util.Locale

private val SwissBlue = Color(0xFF002FA7)
private val Paper = Color(0xFFF7F7F8)
private val White = Color(0xFFFFFFFF)
private val Ink = Color(0xFF111113)
private val Muted = Color(0xFF626269)
private val Hairline = Color(0xFFD9D9DE)
private val SoftBlue = Color(0xFFE8EEFF)
private val ErrorRed = Color(0xFFC6283D)

private enum class AppTab(val label: String, val icon: ImageVector) {
    LISTEN("监听", Icons.Default.GraphicEq),
    PROFILE("我的", Icons.Default.Person),
}

@Composable
fun VoiceToTextApp(
    models: List<ModelDefinition>,
    selectedModelId: String,
    downloadState: ModelDownloadState,
    transcriptionState: TranscriptionState,
    modelDirectory: String,
    isInstalled: (ModelDefinition) -> Boolean,
    modelSizeBytes: (ModelDefinition) -> Long,
    onModelSelected: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onPauseDownload: () -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.LISTEN) }
    var source by rememberSaveable { mutableStateOf(AudioCaptureService.SOURCE_SYSTEM) }
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.first()

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            BottomNavigation(selectedTab) { selectedTab = it }
        },
    ) { contentPadding ->
        when (selectedTab) {
            AppTab.LISTEN -> ListenPage(
                modifier = Modifier.padding(contentPadding),
                selectedModel = selectedModel,
                modelInstalled = isInstalled(selectedModel),
                source = source,
                transcriptionState = transcriptionState,
                onSourceChanged = { source = it },
                onManageModels = { selectedTab = AppTab.PROFILE },
                onStart = onStart,
                onStop = onStop,
                onClear = onClear,
            )

            AppTab.PROFILE -> ModelsPage(
                modifier = Modifier.padding(contentPadding),
                models = models,
                selectedModelId = selectedModel.id,
                downloadState = downloadState,
                modelDirectory = modelDirectory,
                isInstalled = isInstalled,
                modelSizeBytes = modelSizeBytes,
                onModelSelected = onModelSelected,
                onDownloadModel = onDownloadModel,
                onPauseDownload = onPauseDownload,
            )
        }
    }
}

@Composable
private fun BottomNavigation(selected: AppTab, onSelected: (AppTab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(White)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = Hairline)
        NavigationBar(containerColor = White, tonalElevation = 0.dp) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selected == tab,
                    onClick = { onSelected(tab) },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SwissBlue,
                        selectedTextColor = SwissBlue,
                        indicatorColor = SoftBlue,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ListenPage(
    modifier: Modifier,
    selectedModel: ModelDefinition,
    modelInstalled: Boolean,
    source: String,
    transcriptionState: TranscriptionState,
    onSourceChanged: (String) -> Unit,
    onManageModels: () -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "本地语音转文字", section = "监听")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow("当前模型")
                Text(
                    selectedModel.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(onClick = onManageModels, shape = RoundedCornerShape(4.dp)) {
                Text("管理模型")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Hairline, RoundedCornerShape(6.dp)),
        ) {
            SourceButton(
                modifier = Modifier.weight(1f),
                selected = source == AudioCaptureService.SOURCE_SYSTEM,
                label = "系统音频",
                icon = Icons.Default.Headphones,
            ) { onSourceChanged(AudioCaptureService.SOURCE_SYSTEM) }
            SourceButton(
                modifier = Modifier.weight(1f),
                selected = source == AudioCaptureService.SOURCE_MIC,
                label = "麦克风",
                icon = Icons.Default.Mic,
            ) { onSourceChanged(AudioCaptureService.SOURCE_MIC) }
        }

        RuntimePanel(transcriptionState, source, selectedModel.displayName)

        if (!modelInstalled) {
            Surface(
                color = SoftBlue,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "当前模型尚未安装",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                    Button(onClick = onManageModels, shape = RoundedCornerShape(4.dp)) {
                        Text("前往模型")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    if (transcriptionState.running) onStop() else onStart(source)
                },
                enabled = transcriptionState.running || modelInstalled,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SwissBlue),
            ) {
                Icon(
                    if (transcriptionState.running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (transcriptionState.running) "停止监听" else "开始监听")
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier
                    .size(52.dp)
                    .border(1.dp, Hairline, RoundedCornerShape(4.dp)),
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "清空文字")
            }
        }

        TranscriptPanel(transcriptionState)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SourceButton(
    modifier: Modifier,
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(if (selected) SwissBlue else White)
            .clickable(onClick = onClick)
            .height(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) White else Ink,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) White else Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RuntimePanel(
    state: TranscriptionState,
    selectedSource: String,
    selectedModelName: String,
) {
    Surface(
        color = Ink,
        contentColor = White,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        formatElapsed(state.capturedSeconds),
                        fontSize = 48.sp,
                        lineHeight = 50.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        state.status,
                        color = if (state.running) Color(0xFF9FB7FF) else Color(0xFFB8B8BE),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Box(
                    modifier = Modifier
                        .background(if (state.running) SwissBlue else Color(0xFF303034), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(if (state.running) "识别中" else "待机", fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = Color(0xFF3A3A3E))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric("输入", inputLevelLabel(state.inputRms))
                Metric("采集", "${state.capturedSeconds}s")
                Metric("识别", "${state.processedChunks} 段")
            }
            Text(
                "${sourceLabel(if (state.running) state.source else selectedSource)} · " +
                    (if (state.running) state.modelName else selectedModelName),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8B8BE),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF929298), style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TranscriptPanel(state: TranscriptionState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            SectionTitle("原始文字稿")
            Text("${state.rawText.length} 字", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Surface(
            color = White,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .border(1.dp, Hairline, RoundedCornerShape(6.dp)),
        ) {
            SelectionContainer {
                Text(
                    text = state.rawText.ifEmpty { "暂无文字稿" },
                    modifier = Modifier.padding(16.dp),
                    color = if (state.rawText.isEmpty()) Muted else Ink,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        state.transcriptPath?.let {
            Text("已保存：$it", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelsPage(
    modifier: Modifier,
    models: List<ModelDefinition>,
    selectedModelId: String,
    downloadState: ModelDownloadState,
    modelDirectory: String,
    isInstalled: (ModelDefinition) -> Boolean,
    modelSizeBytes: (ModelDefinition) -> Long,
    onModelSelected: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onPauseDownload: () -> Unit,
) {
    val installedCount = models.count(isInstalled)
    val selectedModel = models.first { it.id == selectedModelId }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "本地模型", section = "我的")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Eyebrow("已安装")
                Text(
                    "$installedCount / ${models.size}",
                    fontSize = 36.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "当前：${selectedModel.displayName}",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(color = Hairline)

        models.forEachIndexed { index, model ->
            ModelRow(
                index = index + 1,
                model = model,
                installed = isInstalled(model),
                installedBytes = modelSizeBytes(model),
                selected = selectedModelId == model.id,
                downloadState = downloadState.takeIf { it.modelId == model.id },
                onSelect = { onModelSelected(model.id) },
                onDownload = { onDownloadModel(model.id) },
                onPause = onPauseDownload,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("模型目录")
            Text(modelDirectory, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ModelRow(
    index: Int,
    model: ModelDefinition,
    installed: Boolean,
    installedBytes: Long,
    selected: Boolean,
    downloadState: ModelDownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
) {
    Surface(
        color = White,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) SwissBlue else Hairline, RoundedCornerShape(6.dp)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    String.format(Locale.ROOT, "%02d", index),
                    color = SwissBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.displayName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(model.description, color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
                if (installed) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "已安装",
                        tint = SwissBlue,
                    )
                }
            }

            if (downloadState != null && downloadState.phase in setOf(
                    DownloadPhase.DOWNLOADING,
                    DownloadPhase.PAUSED,
                    DownloadPhase.FAILED,
                )
            ) {
                if (downloadState.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = SwissBlue,
                        trackColor = SoftBlue,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = SwissBlue,
                        trackColor = SoftBlue,
                    )
                }
                Text(downloadProgressLabel(downloadState), color = Muted, style = MaterialTheme.typography.bodySmall)
                downloadState.error?.let {
                    Text("下载失败：$it", color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (installed) ModelDownloadManager.formatBytes(installedBytes) else "尚未安装",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    installed && selected -> {
                        Text(
                            "当前使用",
                            color = SwissBlue,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    installed -> {
                        OutlinedButton(onClick = onSelect, shape = RoundedCornerShape(4.dp)) {
                            Text("使用")
                        }
                    }
                    downloadState?.phase == DownloadPhase.DOWNLOADING -> {
                        OutlinedButton(onClick = onPause, shape = RoundedCornerShape(4.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("暂停")
                        }
                    }
                    downloadState?.phase == DownloadPhase.PAUSED -> {
                        Button(onClick = onDownload, shape = RoundedCornerShape(4.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("继续")
                        }
                    }
                    downloadState?.phase == DownloadPhase.FAILED -> {
                        Button(onClick = onDownload, shape = RoundedCornerShape(4.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                    else -> {
                        Button(onClick = onDownload, shape = RoundedCornerShape(4.dp)) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("下载")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, section: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(section.uppercase(), color = SwissBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            title,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

private fun sourceLabel(source: String): String = when (source) {
    AudioCaptureService.SOURCE_MIC -> "麦克风"
    AudioCaptureService.SOURCE_SYSTEM -> "系统音频"
    else -> source
}

private fun inputLevelLabel(rms: Float): String = when {
    rms >= 0.01f -> "有信号"
    rms >= 0.001f -> "较弱"
    else -> "无信号"
}

private fun formatElapsed(seconds: Int): String = String.format(
    Locale.ROOT,
    "%02d:%02d",
    seconds / 60,
    seconds % 60,
)

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
fun VoiceToTextTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = SwissBlue,
            onPrimary = White,
            background = Paper,
            onBackground = Ink,
            surface = White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFF0F0F2),
            onSurfaceVariant = Muted,
            outline = Hairline,
            error = ErrorRed,
        ),
        typography = MaterialTheme.typography.copy(
            displayLarge = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.SansSerif),
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.SansSerif),
            labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        ),
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(6.dp),
            large = RoundedCornerShape(8.dp),
        ),
        content = content,
    )
}
