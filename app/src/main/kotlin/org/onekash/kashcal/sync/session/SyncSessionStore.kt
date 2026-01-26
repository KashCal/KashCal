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
        return SyncSummaryStats(
            totalSyncs = sessions.size,
            totalPushed = sessions.sumOf { it.totalPushed },
            totalPulled = sessions.sumOf { it.totalPullChanges },
            issueCount = sessions.count { it.status != SyncStatus.SUCCESS }
        )
    }

    /**
     * Export all sessions as text for sharing.
     * Simplified format matching the UI display.
     */
    fun getExportText(): String {
        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        val sessions = _sessions.value
        val stats = getSummaryStats(sessions)

        return buildString {
            appendLine("KashCal Sync History")
            // Header: "X syncs   ↑Y pushed   ↓Z pulled" or with issues
            val headerParts = mutableListOf("${stats.totalSyncs} syncs")
            headerParts.add("↑${stats.totalPushed} pushed")
            headerParts.add("↓${stats.totalPulled} pulled")
            if (stats.issueCount > 0) {
                headerParts.add("⚠ ${stats.issueCount} issues")
            }
            appendLine(headerParts.joinToString("   "))
            appendLine()

            sessions.forEach { session ->
                val icon = when (session.status) {
                    SyncStatus.SUCCESS -> "✓"
                    SyncStatus.PARTIAL -> "⚠"
                    SyncStatus.FAILED -> "✗"
                }

                // Build change summary
                val changes = buildString {
                    if (session.status == SyncStatus.FAILED) {
                        append(session.errorMessage ?: session.errorType?.name ?: "Failed")
                    } else if (!session.hasAnyChanges) {
                        append("↑ 0   ↓ 0")
                    } else {
                        // Push summary
                        append("↑ ${session.totalPushed}   ")
                        // Pull summary with breakdown
                        val pullParts = mutableListOf<String>()
                        if (session.eventsWritten > 0) pullParts.add("+${session.eventsWritten}")
                        if (session.eventsUpdated > 0) pullParts.add("~${session.eventsUpdated}")
                        if (session.eventsDeleted > 0) pullParts.add("-${session.eventsDeleted}")
                        append("↓ ${if (pullParts.isEmpty()) "0" else pullParts.joinToString(" ")}")
                    }
                }

                appendLine("$icon  ${session.calendarName}  •  ${session.triggerSource.icon} ${session.syncType.name.lowercase().replaceFirstChar { it.uppercase() }}  •  ${dateFormat.format(Date(session.timestamp))}")
                appendLine("   $changes")

                // Issue lines
                if (session.hasParseFailures) {
                    appendLine("   ⚠ ${session.skippedParseError} failed to parse")
                }
                if (session.fallbackUsed) {
                    appendLine("   ⚡ Fallback${if (session.fetchFailedCount > 0) " (${session.fetchFailedCount} failed)" else ""}")
                }
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
    val totalPushed: Int,
    val totalPulled: Int,
    val issueCount: Int
)
