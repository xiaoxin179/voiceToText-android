package com.xiaoxin.voicetotext.android.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSafetyPolicyTest {
    @Test
    fun nativeCrashDuringCaptureEnablesSafeMode() {
        assertTrue(resolveCpuSafeMode(true, true, false))
    }

    @Test
    fun versionUpgradeRetriesGpu() {
        assertFalse(resolveCpuSafeMode(false, false, true))
    }

    @Test
    fun unrelatedExitDoesNotEnableSafeMode() {
        assertFalse(resolveCpuSafeMode(true, false, false))
    }

    @Test
    fun safeModeRemainsEnabledWithinSameVersion() {
        assertTrue(resolveCpuSafeMode(true, false, true))
    }
}
