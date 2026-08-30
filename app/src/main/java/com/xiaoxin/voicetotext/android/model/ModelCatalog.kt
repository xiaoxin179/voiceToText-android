package com.xiaoxin.voicetotext.android.model

data class ModelDefinition(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val description: String,
)

object ModelCatalog {
    val all: List<ModelDefinition> = listOf(
        ModelDefinition(
            id = "tiny",
            displayName = "Tiny",
            fileName = "ggml-tiny.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true",
            description = "速度优先，适合低功耗手机",
        ),
        ModelDefinition(
            id = "base",
            displayName = "Base",
            fileName = "ggml-base.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true",
            description = "速度和识别效果均衡，建议首选",
        ),
        ModelDefinition(
            id = "small",
            displayName = "Small",
            fileName = "ggml-small.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true",
            description = "识别效果更好，但更耗电和内存",
        ),
    )

    fun find(id: String): ModelDefinition = all.firstOrNull { it.id == id } ?: all.first()
}
