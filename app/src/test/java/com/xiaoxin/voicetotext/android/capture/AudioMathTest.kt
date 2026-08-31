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
}
