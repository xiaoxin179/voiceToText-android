package com.xiaoxin.voicetotext.android.asr

enum class ComputeMode(val id: String, val displayName: String) {
    GPU("gpu", "GPU (Vulkan)"),
    CPU("cpu", "CPU"),
    ;

    companion object {
        fun fromId(id: String?): ComputeMode = entries.firstOrNull { it.id == id } ?: GPU
    }
}
