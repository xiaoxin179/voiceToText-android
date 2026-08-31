package com.xiaoxin.voicetotext.android.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class ComputeModeTest {
    @Test
    fun restoresSavedModes() {
        assertEquals(ComputeMode.GPU, ComputeMode.fromId("gpu"))
        assertEquals(ComputeMode.CPU, ComputeMode.fromId("cpu"))
    }

    @Test
    fun defaultsToGpuForUnknownValues() {
        assertEquals(ComputeMode.GPU, ComputeMode.fromId(null))
        assertEquals(ComputeMode.GPU, ComputeMode.fromId("unknown"))
    }
}
