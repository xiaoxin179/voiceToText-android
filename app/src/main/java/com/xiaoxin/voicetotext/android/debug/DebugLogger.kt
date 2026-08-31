package com.xiaoxin.voicetotext.android.debug

import android.content.Context
import android.os.Build
import com.xiaoxin.voicetotext.android.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DebugLogState(
    val enabled: Boolean = false,
    val eventCount: Int = 0,
    val lastEventAt: String? = null,
    val filePath: String? = null,
    val recentEntries: List<String> = emptyList(),
    val error: String? = null,
)

object DebugLogger {
    private data class StartupDiagnostic(val message: String, val details: String?)

    private const val PREFERENCES_NAME = "voice_to_text_preferences"
    private const val ENABLED_KEY = "debug_logging_enabled"
    private const val CAPTURE_ACTIVE_KEY = "debug_capture_active"
    private const val CAPTURE_DESCRIPTION_KEY = "debug_capture_description"
    private const val MAX_RECENT_ENTRIES = 120
    private const val MAX_LOG_FILES = 5
    private val fileStampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val eventStampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val initialized = AtomicBoolean(false)
    private val fileLock = Any()
    private val pendingStartupDiagnostics = mutableListOf<StartupDiagnostic>()
    private val _state = MutableStateFlow(DebugLogState())
    private lateinit var appContext: Context
    @Volatile
    private var interruptedCaptureAtStartup = false
    @Volatile
    private var currentFile: File? = null

    val state: StateFlow<DebugLogState> = _state

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, 0)
        interruptedCaptureAtStartup = preferences.getBoolean(CAPTURE_ACTIVE_KEY, false)
        val enabled = preferences.getBoolean(ENABLED_KEY, false)
        if (enabled) {
            startSession("应用进程启动")
            reportInterruptedCapture()
            flushStartupDiagnostics()
        } else {
            restoreLatestLog()
        }
    }

    fun setEnabled(enabled: Boolean) {
        check(initialized.get()) { "DebugLogger 尚未初始化" }
        if (enabled == _state.value.enabled) return
        appContext.getSharedPreferences(PREFERENCES_NAME, 0)
            .edit()
            .putBoolean(ENABLED_KEY, enabled)
            .apply()
        if (enabled) {
            startSession("用户开启调试日志")
            reportInterruptedCapture()
            flushStartupDiagnostics()
        } else {
            log("LOGGER", "用户关闭调试日志")
            _state.update { it.copy(enabled = false) }
        }
    }

    fun log(category: String, message: String, error: Throwable? = null) {
        if (!_state.value.enabled) return
        val timestamp = LocalDateTime.now().format(eventStampFormatter)
        val normalizedMessage = message.replace(Regex("[\\r\\n]+"), " ").trim()
        val entry = "$timestamp | ${Thread.currentThread().name} | $category | $normalizedMessage"
        _state.update { current ->
            current.copy(
                eventCount = current.eventCount + 1,
                lastEventAt = timestamp,
                recentEntries = (current.recentEntries + entry).takeLast(MAX_RECENT_ENTRIES),
            )
        }
        val stackTrace = error?.let(::stackTrace)
        val target = currentFile ?: return
        synchronized(fileLock) {
            runCatching {
                target.appendText(
                    buildString {
                        appendLine(entry)
                        if (stackTrace != null) appendLine(stackTrace)
                    },
                )
            }.onFailure { writeError ->
                _state.update { it.copy(error = "日志写入失败：${writeError.message}") }
            }
        }
    }

    fun startupDiagnostic(message: String, details: String? = null) {
        if (_state.value.enabled) {
            log("PREVIOUS_EXIT", message)
            appendDiagnosticDetails(details)
        } else {
            synchronized(pendingStartupDiagnostics) {
                pendingStartupDiagnostics += StartupDiagnostic(message, details)
            }
        }
    }

    fun hadInterruptedCaptureAtStartup(): Boolean = interruptedCaptureAtStartup

    fun markCaptureActive(description: String) {
        if (!initialized.get()) return
        appContext.getSharedPreferences(PREFERENCES_NAME, 0).edit()
            .putBoolean(CAPTURE_ACTIVE_KEY, true)
            .putString(CAPTURE_DESCRIPTION_KEY, description)
            .commit()
        log("DIAGNOSTIC", "写入监听活动标记 $description")
    }

    fun markCaptureFinished(description: String, reason: String) {
        if (!initialized.get()) return
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, 0)
        if (preferences.getString(CAPTURE_DESCRIPTION_KEY, null) != description) {
            log("DIAGNOSTIC", "忽略过期监听结束标记 session=$description reason=$reason")
            return
        }
        preferences.edit()
            .putBoolean(CAPTURE_ACTIVE_KEY, false)
            .remove(CAPTURE_DESCRIPTION_KEY)
            .commit()
        log("DIAGNOSTIC", "清除监听活动标记 session=$description reason=$reason")
    }

    fun clear() {
        val target = currentFile ?: return
        _state.update { it.copy(eventCount = 0, lastEventAt = null, recentEntries = emptyList(), error = null) }
        synchronized(fileLock) {
            runCatching { target.writeText(sessionHeader("日志已清空")) }
                .onFailure { writeError ->
                    _state.update { it.copy(error = "日志清空失败：${writeError.message}") }
                }
        }
        if (_state.value.enabled) log("LOGGER", "用户清空调试日志")
    }

    fun currentLogFile(): File? = currentFile?.takeIf { it.isFile }

    fun readCurrentLog(): String = currentLogFile()?.let { file ->
        runCatching { file.readText() }.getOrDefault("")
    }.orEmpty()

    private fun startSession(reason: String) {
        val file = runCatching {
            val directory = File(appContext.filesDir, "logs")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建日志目录" }
            pruneOldLogs(directory)
            File(directory, "debug-${LocalDateTime.now().format(fileStampFormatter)}.log").apply {
                check(createNewFile()) { "日志文件已存在" }
            }
        }.getOrElse { createError ->
            currentFile = null
            appContext.getSharedPreferences(PREFERENCES_NAME, 0)
                .edit()
                .putBoolean(ENABLED_KEY, false)
                .apply()
            _state.value = DebugLogState(error = "无法创建日志：${createError.message}")
            return
        }
        currentFile = file
        _state.value = DebugLogState(enabled = true, filePath = file.absolutePath)
        synchronized(fileLock) {
            runCatching { file.writeText(sessionHeader(reason)) }
                .onFailure { writeError ->
                    _state.update { it.copy(error = "日志初始化失败：${writeError.message}") }
                }
        }
        log("LOGGER", reason)
    }

    private fun reportInterruptedCapture() {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, 0)
        if (!preferences.getBoolean(CAPTURE_ACTIVE_KEY, false)) return
        val description = preferences.getString(CAPTURE_DESCRIPTION_KEY, "未知监听会话")
        log(
            "ABNORMAL_EXIT",
            "检测到上一次监听没有正常结束，进程可能崩溃、被系统回收或被强制停止；session=$description",
        )
        preferences.edit()
            .putBoolean(CAPTURE_ACTIVE_KEY, false)
            .remove(CAPTURE_DESCRIPTION_KEY)
            .commit()
    }

    private fun flushStartupDiagnostics() {
        val diagnostics = synchronized(pendingStartupDiagnostics) {
            pendingStartupDiagnostics.toList().also { pendingStartupDiagnostics.clear() }
        }
        diagnostics.forEach { diagnostic ->
            log("PREVIOUS_EXIT", diagnostic.message)
            appendDiagnosticDetails(diagnostic.details)
        }
    }

    private fun appendDiagnosticDetails(details: String?) {
        if (details.isNullOrBlank()) return
        val target = currentFile ?: return
        synchronized(fileLock) {
            runCatching {
                target.appendText(
                    buildString {
                        appendLine("----- previous process trace begin -----")
                        appendLine(details.take(MAX_DIAGNOSTIC_DETAILS_CHARS))
                        appendLine("----- previous process trace end -----")
                    },
                )
            }.onFailure { writeError ->
                _state.update { it.copy(error = "崩溃堆栈写入失败：${writeError.message}") }
            }
        }
    }

    private fun sessionHeader(reason: String): String = buildString {
        appendLine("voiceToText Android debug log")
        appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("session=$reason")
        appendLine("transcript_content=not_recorded")
        appendLine()
    }

    private fun pruneOldLogs(directory: File) {
        directory.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_LOG_FILES - 1)
            ?.forEach(File::delete)
    }

    private fun restoreLatestLog() {
        val latest = File(appContext.filesDir, "logs")
            .listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.maxByOrNull(File::lastModified)
            ?: return
        currentFile = latest
        val allEntries = runCatching {
            latest.readLines().filter { line -> line.length >= 23 && line[4] == '-' && " | " in line }
        }.getOrDefault(emptyList())
        val entries = allEntries.takeLast(MAX_RECENT_ENTRIES)
        _state.value = DebugLogState(
            enabled = false,
            eventCount = allEntries.size,
            lastEventAt = entries.lastOrNull()?.take(23),
            filePath = latest.absolutePath,
            recentEntries = entries,
        )
    }

    private fun stackTrace(error: Throwable): String {
        val buffer = StringWriter()
        error.printStackTrace(PrintWriter(buffer))
        return buffer.toString().lineSequence().take(40).joinToString("\n")
    }

    private const val MAX_DIAGNOSTIC_DETAILS_CHARS = 256 * 1024
}
