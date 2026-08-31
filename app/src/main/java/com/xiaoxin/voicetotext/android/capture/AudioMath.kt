package com.xiaoxin.voicetotext.android.capture

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun audioRms(input: FloatArray): Float {
    if (input.isEmpty()) return 0f
    var sumSquares = 0.0
    for (sample in input) {
        sumSquares += sample.toDouble() * sample.toDouble()
    }
    return sqrt(sumSquares / input.size.toDouble()).toFloat()
}

internal fun resampleLinear(input: FloatArray, inputRate: Int, targetRate: Int): FloatArray {
    if (inputRate == targetRate || input.isEmpty()) return input
    val outputSize = (input.size.toDouble() * targetRate.toDouble() / inputRate.toDouble()).roundToInt()
    if (outputSize <= 1) return FloatArray(maxOf(outputSize, 0))
    val output = FloatArray(outputSize)
    val scale = (input.size - 1).toDouble() / (outputSize - 1).toDouble()
    for (index in output.indices) {
        val position = index * scale
        val left = position.toInt().coerceIn(0, input.lastIndex)
        val right = (left + 1).coerceAtMost(input.lastIndex)
        val fraction = position - left.toDouble()
        output[index] = (input[left] * (1.0 - fraction) + input[right] * fraction).toFloat()
    }
    return output
}

internal class PcmChunker(
    private val inputRate: Int,
    private val chunkSeconds: Int = 5,
) {
    private val targetRate = 16_000
    private val targetChunkSize = targetRate * chunkSeconds
    private var pending = FloatArray(0)

    fun append(input: FloatArray): List<FloatArray> {
        val normalized = resampleLinear(input, inputRate, targetRate)
        if (normalized.isEmpty()) return emptyList()
        pending += normalized
        val chunks = mutableListOf<FloatArray>()
        while (pending.size >= targetChunkSize) {
            chunks += pending.copyOfRange(0, targetChunkSize)
            pending = pending.copyOfRange(targetChunkSize, pending.size)
        }
        return chunks
    }

    fun flush(): FloatArray? {
        if (pending.size < targetRate / 2) return null
        val result = pending
        pending = FloatArray(0)
        return result
    }
}
