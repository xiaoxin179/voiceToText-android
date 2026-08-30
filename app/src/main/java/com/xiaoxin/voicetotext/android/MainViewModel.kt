package com.xiaoxin.voicetotext.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.xiaoxin.voicetotext.android.model.DownloadPhase
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

    val selectedModelId: StateFlow<String> = _selectedModelId
    val downloadState: StateFlow<ModelDownloadState> = modelManager.state
    val transcriptionState: StateFlow<TranscriptionState> = TranscriptionSession.state
    val models: List<ModelDefinition> = ModelCatalog.all
    val modelDirectory: String
        get() = modelManager.modelDirectory.absolutePath

    fun selectModel(modelId: String) {
        if (ModelCatalog.all.none { it.id == modelId }) return
        _selectedModelId.value = modelId
        preferences.edit().putString(SELECTED_MODEL_KEY, modelId).apply()
    }

    fun selectedModel(): ModelDefinition = ModelCatalog.find(_selectedModelId.value)

    fun modelPath(model: ModelDefinition = selectedModel()): String? {
        if (!modelManager.isInstalled(model)) return null
        return modelManager.modelFile(model).absolutePath
    }

    fun isInstalled(model: ModelDefinition): Boolean = modelManager.isInstalled(model)

    fun downloadSelected() {
        modelManager.startOrResume(selectedModel())
    }

    fun pauseDownload() {
        modelManager.pause()
    }

    fun clearTranscript() {
        TranscriptionSession.clear()
    }

    fun selectedDownloadIsActive(): Boolean =
        downloadState.value.modelId == selectedModel().id &&
            downloadState.value.phase == DownloadPhase.DOWNLOADING

    override fun onCleared() {
        modelManager.close()
        super.onCleared()
    }

    companion object {
        private const val PREFERENCES_NAME = "voice_to_text_preferences"
        private const val SELECTED_MODEL_KEY = "selected_model"
    }
}
