package com.xiaoxin.voicetotext.android.model

data class ModelDefinition(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val fallbackUrls: List<String> = emptyList(),
    val maxDownloadBytes: Long,
    val description: String,
) {
    val downloadUrls: List<String>
        get() = listOf(url) + fallbackUrls
}

object ModelCatalog {
    private const val MIRROR_BASE = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main"
    private const val OFFICIAL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val all: List<ModelDefinition> = listOf(
        ModelDefinition(
            id = "tiny",
            displayName = "Tiny",
            fileName = "ggml-tiny.bin",
            url = "$MIRROR_BASE/ggml-tiny.bin?download=true",
            fallbackUrls = listOf("$OFFICIAL_BASE/ggml-tiny.bin?download=true"),
            maxDownloadBytes = 256L * 1024L * 1024L,
            description = "速度优先，适合低功耗手机",
        ),
        ModelDefinition(
            id = "base",
            displayName = "Base",
            fileName = "ggml-base.bin",
            url = "$MIRROR_BASE/ggml-base.bin?download=true",
            fallbackUrls = listOf("$OFFICIAL_BASE/ggml-base.bin?download=true"),
            maxDownloadBytes = 512L * 1024L * 1024L,
            description = "速度和识别效果均衡，建议首选",
        ),
        ModelDefinition(
            id = "small",
            displayName = "Small",
            fileName = "ggml-small.bin",
            url = "$MIRROR_BASE/ggml-small.bin?download=true",
            fallbackUrls = listOf("$OFFICIAL_BASE/ggml-small.bin?download=true"),
            maxDownloadBytes = 1L * 1024L * 1024L * 1024L,
            description = "识别效果更好，但更耗电和内存",
        ),
    )

    fun find(id: String): ModelDefinition = all.firstOrNull { it.id == id } ?: all.first()
}
