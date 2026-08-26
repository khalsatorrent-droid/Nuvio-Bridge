package com.example.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class StepLogLevel {
    INFO, SUCCESS, WARNING, ERROR, DEBUG
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val timeFormatted: String,
    val level: StepLogLevel,
    val tag: String,
    val message: String,
    val reason: String? = null
) {
    val fullText: String by lazy {
        val levelStr = when (level) {
            StepLogLevel.SUCCESS -> "[OK]"
            StepLogLevel.ERROR -> "[ERR]"
            StepLogLevel.WARNING -> "[WARN]"
            StepLogLevel.DEBUG -> "[DBG]"
            StepLogLevel.INFO -> "[INFO]"
        }
        val base = "[$timeFormatted] $levelStr [$tag] $message"
        if (!reason.isNullOrEmpty()) "$base (Reason: $reason)" else base
    }
}

object AppLogger {
    private const val MAX_LOGS = 300
    private val counter = AtomicLong(0L)
    private val isDirty = AtomicBoolean(false)
    private val bufferLock = Any()
    private val buffer = ArrayList<LogEntry>(MAX_LOGS)

    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // High efficiency UI throttler: polls every 250ms only when dirty
        scope.launch {
            while (isActive) {
                delay(250)
                if (isDirty.compareAndSet(true, false)) {
                    val snapshot = synchronized(bufferLock) {
                        ArrayList(buffer)
                    }
                    _logsFlow.value = snapshot
                }
            }
        }
    }

    fun log(
        category: String,
        step: String,
        details: String,
        level: StepLogLevel = StepLogLevel.INFO,
        durationMs: Long? = null,
        failureReason: String? = null
    ) {
        val now = System.currentTimeMillis()
        val formattedTime = timeFormatter.get()?.format(Date(now)) ?: "00:00:00"
        val message = buildString {
            append(step)
            if (details.isNotEmpty()) {
                append(": ")
                append(details)
            }
            if (durationMs != null) {
                append(" (")
                append(durationMs)
                append("ms)")
            }
        }

        val entry = LogEntry(
            id = counter.incrementAndGet(),
            timestamp = now,
            timeFormatted = formattedTime,
            level = level,
            tag = category,
            message = message,
            reason = failureReason
        )

        synchronized(bufferLock) {
            if (buffer.size >= MAX_LOGS) {
                buffer.removeAt(0)
            }
            buffer.add(entry)
        }
        isDirty.set(true)

        when (level) {
            StepLogLevel.ERROR -> android.util.Log.e("AppLogger", "[$category] $step: $details (Reason: $failureReason)")
            StepLogLevel.WARNING -> android.util.Log.w("AppLogger", "[$category] $step: $details (Reason: $failureReason)")
            StepLogLevel.SUCCESS -> android.util.Log.i("AppLogger", "[$category] $step: $details")
            else -> android.util.Log.d("AppLogger", "[$category] $step: $details")
        }
    }

    fun success(category: String, step: String, details: String, durationMs: Long? = null) {
        log(category, step, details, StepLogLevel.SUCCESS, durationMs = durationMs)
    }

    fun info(category: String, step: String, details: String) {
        log(category, step, details, StepLogLevel.INFO)
    }

    fun warn(category: String, step: String, details: String, reason: String? = null) {
        log(category, step, details, StepLogLevel.WARNING, failureReason = reason)
    }

    fun error(category: String, step: String, details: String, reason: String? = null) {
        log(category, step, details, StepLogLevel.ERROR, failureReason = reason)
    }

    fun getAllText(): String {
        return synchronized(bufferLock) {
            buffer.joinToString("\n") { it.fullText }
        }
    }

    fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
        }
        isDirty.set(false)
        _logsFlow.value = emptyList()
    }
}
