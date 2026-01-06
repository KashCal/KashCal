package org.onekash.kashcal.sync.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory debug log collector for sync diagnostics.
 *
 * Usage:
 * - Call SyncDebugLog.log("TAG", "message") instead of Log.i()
 * - View logs in Settings > Sync Debug Logs
 * - Logs are kept in memory (cleared on app restart)
 */
object SyncDebugLog {

    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    data class LogEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
        val level: Level
    ) {
        fun formatted(): String {
            val time = dateFormat.format(Date(timestamp))
            return "[$time] $tag: $message"
        }
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * Log a debug message (also logs to logcat).
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addEntry(tag, message, Level.DEBUG)
    }

    /**
     * Log an info message (also logs to logcat).
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addEntry(tag, message, Level.INFO)
    }

    /**
     * Log a warning message (also logs to logcat).
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        addEntry(tag, message, Level.WARN)
    }

    /**
     * Log an error message (also logs to logcat).
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        addEntry(tag, message, Level.ERROR)
    }

    private fun addEntry(tag: String, message: String, level: Level) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            level = level
        )

        val current = _entries.value.toMutableList()
        current.add(entry)

        // Trim to max entries
        while (current.size > MAX_ENTRIES) {
            current.removeAt(0)
        }

        _entries.value = current
    }

    /**
     * Clear all log entries.
     */
    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Get all logs as a single string (for copying).
     */
    fun getAllAsText(): String {
        return _entries.value.joinToString("\n") { it.formatted() }
    }

    /**
     * Get log count.
     */
    fun count(): Int = _entries.value.size
}
