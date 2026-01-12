package org.onekash.kashcal.sync.session

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent store for sync session history.
 *
 * Features:
 * - JSON file persistence (survives app restart)
 * - 48-hour retention with auto-cleanup
 * - StateFlow for reactive UI updates
 * - Export functionality for debugging
 */
@Singleton
class SyncSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SyncSessionStore"
        private const val FILE_NAME = "sync_sessions.json"
        private const val RETENTION_MS = 48 * 60 * 60 * 1000L  // 48 hours
        private const val MAX_SESSIONS = 200  // Safety limit
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val gson: Gson = GsonBuilder().create()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessions = MutableStateFlow<List<SyncSession>>(emptyList())
    val sessions: StateFlow<List<SyncSession>> = _sessions.asStateFlow()

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    /**
     * Add a new sync session to the store.
     * Automatically handles retention and persistence.
     */
    suspend fun add(session: SyncSession) = mutex.withLock {
        val current = _sessions.value.toMutableList()
        current.add(0, session)  // Newest first

        // Apply retention policy
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val filtered = current
            .filter { it.timestamp > cutoff }
            .take(MAX_SESSIONS)

        _sessions.value = filtered
        saveToDisk(filtered)

        Log.d(TAG, "Added session for ${session.calendarName}: ${session.status}")
    }

    /**
     * Load sessions from disk on startup.
     */
    private suspend fun loadFromDisk() = mutex.withLock {
        if (!file.exists()) {
            Log.d(TAG, "No session file found, starting fresh")
            return@withLock
        }

        try {
            val json = file.readText()
            val type = object : TypeToken<List<SyncSession>>() {}.type
            val loaded: List<SyncSession> = gson.fromJson(json, type) ?: emptyList()

            // Filter out old entries
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val filtered = loaded.filter { it.timestamp > cutoff }

            _sessions.value = filtered
            Log.d(TAG, "Loaded ${filtered.size} sessions from disk (${loaded.size - filtered.size} expired)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions from disk", e)
            _sessions.value = emptyList()
        }
    }

    /**
     * Save sessions to disk.
     */
    private fun saveToDisk(sessions: List<SyncSession>) {
        try {
            file.writeText(gson.toJson(sessions))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions to disk", e)
        }
    }

    /**
     * Get summary statistics for display.
     */
    fun getSummaryStats(sessions: List<SyncSession> = _sessions.value): SyncSummaryStats {
        val successCount = sessions.count { it.status == SyncStatus.SUCCESS }
        val partialCount = sessions.count { it.status == SyncStatus.PARTIAL }
        val failedCount = sessions.count { it.status == SyncStatus.FAILED }

        val backgroundMissing = sessions.count {
            it.triggerSource.isBackground && it.hasMissingEvents
        }
        val foregroundMissing = sessions.count {
            it.triggerSource.isForeground && it.hasMissingEvents
        }

        val totalEvents = sessions.sumOf { it.eventsWritten + it.eventsUpdated }

        return SyncSummaryStats(
            totalSyncs = sessions.size,
            successCount = successCount,
            partialCount = partialCount,
            failedCount = failedCount,
            totalEventsProcessed = totalEvents,
            backgroundMissingCount = backgroundMissing,
            foregroundMissingCount = foregroundMissing
        )
    }

    /**
     * Export all sessions as text for sharing/debugging.
     */
    fun getExportText(): String {
        val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

        return buildString {
            appendLine("=== KashCal Sync History ===")
            appendLine("Exported: ${dateFormat.format(Date())}")
            appendLine("Sessions: ${_sessions.value.size}")
            appendLine()

            _sessions.value.forEach { session ->
                appendLine("─".repeat(40))
                appendLine("${dateFormat.format(Date(session.timestamp))} - ${session.calendarName}")
                appendLine("${session.syncType} | ${session.triggerSource.icon} ${session.triggerSource.displayName} | ${session.durationMs}ms")
                appendLine("Server: ${session.hrefsReported} → Fetched: ${session.eventsFetched} → Written: ${session.eventsWritten}")

                if (session.eventsUpdated > 0) appendLine("Updated: ${session.eventsUpdated}")
                if (session.eventsDeleted > 0) appendLine("Deleted: ${session.eventsDeleted}")
                if (session.hasMissingEvents) appendLine("⚠️ Missing: ${session.missingCount} (token ${if (session.tokenAdvanced) "advanced" else "NOT advanced"})")
                if (session.abandonedParseErrors > 0) appendLine("❌ Abandoned: ${session.abandonedParseErrors} events (parse failed after max retries)")
                if (session.totalSkipped > 0) {
                    appendLine("Skipped: ${session.totalSkipped}")
                    if (session.skippedParseError > 0) appendLine("  • Parse error: ${session.skippedParseError}")
                    if (session.skippedOrphanedException > 0) appendLine("  • No master: ${session.skippedOrphanedException}")
                    if (session.skippedPendingLocal > 0) appendLine("  • Pending local: ${session.skippedPendingLocal}")
                    if (session.skippedEtagUnchanged > 0) appendLine("  • ETag unchanged: ${session.skippedEtagUnchanged}")
                }
                if (session.errorType != null) appendLine("❌ Error: ${session.errorType} at ${session.errorStage}")
                appendLine()
            }
        }
    }

    /**
     * Clear all session history.
     */
    fun clear() {
        _sessions.value = emptyList()
        file.delete()
        Log.d(TAG, "Cleared all sessions")
    }
}

/**
 * Summary statistics for the sync history header.
 */
data class SyncSummaryStats(
    val totalSyncs: Int,
    val successCount: Int,
    val partialCount: Int,
    val failedCount: Int,
    val totalEventsProcessed: Int,
    val backgroundMissingCount: Int,
    val foregroundMissingCount: Int
)
