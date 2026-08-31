package com.xiaoxin.voicetotext.android.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMathTest {
    @Test
    fun audioRmsReportsSilenceAndSignalLevels() {
        assertEquals(0f, audioRms(FloatArray(160)), 0.000001f)
        assertEquals(0.5f, audioRms(FloatArray(160) { 0.5f }), 0.000001f)
        assertEquals(1f, audioRms(floatArrayOf(-1f, 1f)), 0.000001f)
    }

    @Test
    fun chunkerResamplesToWhisperRate() {
        val chunker = PcmChunker(inputRate = 48_000, chunkSeconds = 1)
        val chunks = chunker.append(FloatArray(48_000) { 0.25f })

        assertEquals(1, chunks.size)
        assertEquals(16_000, chunks.single().size)
        assertEquals(0.25f, audioRms(chunks.single()), 0.000001f)
    }

    @Test
    fun defaultChunkerEmitsEveryFiveSeconds() {
        val chunker = PcmChunker(inputRate = 16_000)

        assertEquals(0, chunker.append(FloatArray(64_000)).size)
        assertEquals(1, chunker.append(FloatArray(16_000)).size)
    }

    @Test
    fun pcmQueueRoundTripsSamples() {
        val file = kotlin.io.path.createTempFile(suffix = ".pcm").toFile()
        val samples = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

        writePcm16(file, samples)
        val restored = readPcm16(file)

        assertEquals(samples.size, restored.size)
        samples.forEachIndexed { index, sample ->
            assertEquals(sample, restored[index], 0.0001f)
        }
        file.delete()
    }
}
