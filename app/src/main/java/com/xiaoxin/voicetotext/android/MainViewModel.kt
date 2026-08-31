package com.xiaoxin.voicetotext.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.xiaoxin.voicetotext.android.debug.DebugLogState
import com.xiaoxin.voicetotext.android.debug.DebugLogger
import com.xiaoxin.voicetotext.android.model.ModelCatalog
import com.xiaoxin.voicetotext.android.model.ModelDefinition
import com.xiaoxin.voicetotext.android.model.ModelDownloadManager
import com.xiaoxin.voicetotext.android.model.ModelDownloadState
import com.xiaoxin.voicetotext.android.transcript.TranscriptionSession
import com.xiaoxin.voicetotext.android.transcript.TranscriptionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val modelManager = ModelDownloadManager(application)
    private val _selectedModelId = MutableStateFlow(
        preferences.getString(SELECTED_MODEL_KEY, "base") ?: "base",
    )

    init {
        DebugLogger.initialize(application)
    }

    val selectedModelId: StateFlow<String> = _selectedModelId
    val downloadState: StateFlow<ModelDownloadState> = modelManager.state
    val transcriptionState: StateFlow<TranscriptionState> = TranscriptionSession.state
    val debugLogState: StateFlow<DebugLogState> = DebugLogger.state
    val models: List<ModelDefinition> = ModelCatalog.all
    val modelDirectory: String
        get() = modelManager.modelDirectory.absolutePath

    fun selectModel(modelId: String) {
        if (ModelCatalog.all.none { it.id == modelId }) return
        _selectedModelId.value = modelId
        preferences.edit().putString(SELECTED_MODEL_KEY, modelId).apply()
        DebugLogger.log("MODEL", "选择模型 id=$modelId")
    }

    fun selectedModel(): ModelDefinition = ModelCatalog.find(_selectedModelId.value)

    fun modelPath(model: ModelDefinition = selectedModel()): String? {
        if (!modelManager.isInstalled(model)) return null
        return modelManager.modelFile(model).absolutePath
    }

    fun isInstalled(model: ModelDefinition): Boolean = modelManager.isInstalled(model)

    fun modelSizeBytes(model: ModelDefinition): Long =
        if (modelManager.isInstalled(model)) modelManager.modelFile(model).length() else 0L

    fun downloadModel(modelId: String) {
        val model = ModelCatalog.all.firstOrNull { it.id == modelId } ?: return
        DebugLogger.log("MODEL", "请求下载模型 id=$modelId")
        modelManager.startOrResume(model)
    }

    fun pauseDownload() {
        DebugLogger.log("MODEL", "请求暂停模型下载")
        modelManager.pause()
    }

    fun clearTranscript() {
        DebugLogger.log("UI", "清空文字稿")
        TranscriptionSession.clear()
    }

    fun setDebugLogging(enabled: Boolean) {
        DebugLogger.setEnabled(enabled)
    }

    fun clearDebugLog() {
        DebugLogger.clear()
    }

    fun logUiEvent(message: String) {
        DebugLogger.log("UI", message)
    }

    override fun onCleared() {
        modelManager.close()
        super.onCleared()
    }

    companion object {
        private const val PREFERENCES_NAME = "voice_to_text_preferences"
        private const val SELECTED_MODEL_KEY = "selected_model"
    }
}
