package com.xiaoxin.voicetotext.android

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import com.xiaoxin.voicetotext.android.debug.DebugLogger

class VoiceToTextApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLogger.initialize(this)
        logPreviousProcessExit()
        installCrashLogger()
        DebugLogger.log("LIFECYCLE", "Application 创建 pid=${android.os.Process.myPid()}")
    }

    override fun onTrimMemory(level: Int) {
        DebugLogger.log("MEMORY", "系统请求释放内存 level=$level (${trimLevelName(level)}) ${memorySummary()}")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        DebugLogger.log("MEMORY", "系统报告低内存 ${memorySummary()}")
        super.onLowMemory()
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLogger.log(
                "FATAL",
                "未捕获异常 thread=${thread.name} type=${error.javaClass.name} message=${error.message}",
                error,
            )
            previousHandler?.uncaughtException(thread, error)
        }
    }

    private fun logPreviousProcessExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = getSystemService(ActivityManager::class.java)
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return
        DebugLogger.startupDiagnostic(
            "reason=${exitReasonName(exit.reason)} status=${exit.status} importance=${exit.importance} " +
                "timestamp=${exit.timestamp} pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description}",
        )
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN_$reason"
    }

    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        else -> "UNKNOWN"
    }

    private fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        return "heapUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) / MIB} " +
            "heapTotalMb=${runtime.totalMemory() / MIB} heapMaxMb=${runtime.maxMemory() / MIB}"
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
