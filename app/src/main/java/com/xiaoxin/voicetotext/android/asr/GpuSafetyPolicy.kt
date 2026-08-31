package com.xiaoxin.voicetotext.android.asr

import android.content.Context
import com.xiaoxin.voicetotext.android.BuildConfig
import com.xiaoxin.voicetotext.android.debug.DebugLogger

object GpuSafetyPolicy {
    private const val PREFERENCES_NAME = "gpu_safety_policy"
    private const val LAST_VERSION_KEY = "last_version_code"
    private const val SAFE_MODE_KEY = "cpu_safe_mode"

    @Volatile
    private var cpuSafeMode = false

    fun initialize(context: Context, previousExitReason: Int?) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, 0)
        val lastVersion = preferences.getInt(LAST_VERSION_KEY, -1)
        val sameVersion = lastVersion == BuildConfig.VERSION_CODE
        val nativeCrashDuringCapture = sameVersion &&
            previousExitReason == REASON_CRASH_NATIVE &&
            DebugLogger.hadInterruptedCaptureAtStartup()

        cpuSafeMode = resolveCpuSafeMode(
            sameVersion = sameVersion,
            nativeCrashDuringCapture = nativeCrashDuringCapture,
            existingSafeMode = preferences.getBoolean(SAFE_MODE_KEY, false),
        )
        preferences.edit()
            .putInt(LAST_VERSION_KEY, BuildConfig.VERSION_CODE)
            .putBoolean(SAFE_MODE_KEY, cpuSafeMode)
            .commit()

        DebugLogger.startupDiagnostic(
            "gpuPolicy=${if (cpuSafeMode) "CPU_SAFE_MODE" else "VULKAN"} " +
                "lastVersion=$lastVersion currentVersion=${BuildConfig.VERSION_CODE} " +
                "nativeCrashDuringCapture=$nativeCrashDuringCapture",
        )
    }

    fun shouldUseGpu(): Boolean = !cpuSafeMode

    private const val REASON_CRASH_NATIVE = 5
}

internal fun resolveCpuSafeMode(
    sameVersion: Boolean,
    nativeCrashDuringCapture: Boolean,
    existingSafeMode: Boolean,
): Boolean = when {
    nativeCrashDuringCapture -> true
    !sameVersion -> false
    else -> existingSafeMode
}
