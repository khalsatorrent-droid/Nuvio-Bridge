package com.example.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

enum class StepLogLevel {
    INFO, SUCCESS, WARNING, ERROR, DEBUG
}

data class ExecutionStepLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: String, // e.g. "RESOLVER", "SCRAPER", "DahmerMovies", "Cineby", "NETWORK", "FORMATTER", "SERVER"
    val step: String,     // e.g. "ID & Title Resolution", "Scraper Invocation", "Network Fetch", "Stream Extraction"
    val details: String,  // e.g. "Resolved tt0111161 -> TMDB: 278 (The Shawshank Redemption, 1994)"
    val level: StepLogLevel = StepLogLevel.INFO,
    val durationMs: Long? = null,
    val failureReason: String? = null
)

object AppLogger {
    private const val MAX_LOGS = 400
    private val logDeque = ConcurrentLinkedDeque<ExecutionStepLog>()
    private val _logsFlow = MutableStateFlow<List<ExecutionStepLog>>(emptyList())
    val logsFlow: StateFlow<List<ExecutionStepLog>> = _logsFlow.asStateFlow()

    fun log(
        category: String,
        step: String,
        details: String,
        level: StepLogLevel = StepLogLevel.INFO,
        durationMs: Long? = null,
        failureReason: String? = null
    ) {
        val entry = ExecutionStepLog(
            category = category,
            step = step,
            details = details,
            level = level,
            durationMs = durationMs,
            failureReason = failureReason
        )
        logDeque.addFirst(entry)
        while (logDeque.size > MAX_LOGS) {
            logDeque.pollLast()
        }
        _logsFlow.value = logDeque.toList()

        when (level) {
            StepLogLevel.ERROR -> android.util.Log.e("AppLogger", "[$category] ❌ $step: $details ${failureReason?.let { "(Reason: $it)" } ?: ""}")
            StepLogLevel.WARNING -> android.util.Log.w("AppLogger", "[$category] ⚠️ $step: $details ${failureReason?.let { "(Reason: $it)" } ?: ""}")
            StepLogLevel.SUCCESS -> android.util.Log.i("AppLogger", "[$category] ✓ $step: $details ${durationMs?.let { "(${it}ms)" } ?: ""}")
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

    fun clear() {
        logDeque.clear()
        _logsFlow.value = emptyList()
    }
}
