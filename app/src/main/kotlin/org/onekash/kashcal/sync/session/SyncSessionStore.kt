package org.onekash.kashcal.sync.session

import android.content.Context
import android.util.Log
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
import kotlinx.serialization.json.Json
import org.onekash.kashcal.util.DateTimeUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the sync session history in a JSON file in app storage, exposed as [sessions].
 *
 * Sessions older than 48 hours are dropped on load and on each [add]; [add] also keeps only the
 * [MAX_SESSIONS] newest.
 */
@Singleton
class SyncSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SyncSessionStore"
        private const val FILE_NAME = "sync_sessions.json"
        private const val RETENTION_MS = 48 * 60 * 60 * 1000L  // 48 hours
        private const val MAX_SESSIONS = 200
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessions = MutableStateFlow<List<SyncSession>>(emptyList())
    val sessions: StateFlow<List<SyncSession>> = _sessions.asStateFlow()

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    /** Adds [session] as the newest entry, applies retention and writes the file. */
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

    /** Loads the file at startup, dropping expired sessions; an unreadable file starts empty. */
    private suspend fun loadFromDisk() = mutex.withLock {
        if (!file.exists()) {
            Log.d(TAG, "No session file found, starting fresh")
            return@withLock
        }

        try {
            val jsonStr = file.readText()
            val loaded: List<SyncSession> = json.decodeFromString(jsonStr)

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

    /** Writes [sessions] to the file; a write failure is logged and the in-memory list kept. */
    private fun saveToDisk(sessions: List<SyncSession>) {
        try {
            file.writeText(json.encodeToString(sessions))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions to disk", e)
        }
    }

    /** Returns the header totals; an issue is any session not SUCCESS. */
    fun getSummaryStats(sessions: List<SyncSession> = _sessions.value): SyncSummaryStats {
        return SyncSummaryStats(
            totalSyncs = sessions.size,
            totalPushed = sessions.sumOf { it.totalPushed },
            totalPulled = sessions.sumOf { it.totalPullChanges },
            issueCount = sessions.count { it.status != SyncStatus.SUCCESS }
        )
    }

    /** Formats every session as plain text for sharing, in the Sync History sheet's layout. */
    fun getExportText(): String {
        val dateFormat = SimpleDateFormat(DateTimeUtils.localizedPattern("MMMdHmm"), Locale.getDefault())
        val sessions = _sessions.value
        val stats = getSummaryStats(sessions)

        return buildString {
            appendLine("KashCal Sync History")
            // Header: "X syncs   ↑Y pushed   ↓Z pulled", plus the issue count when any
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

                val changes = buildString {
                    if (session.status == SyncStatus.FAILED) {
                        append(session.errorMessage ?: session.errorType?.name ?: "Failed")
                    } else if (!session.hasAnyChanges) {
                        append("↑ 0   ↓ 0")
                    } else {
                        append("↑ ${session.totalPushed}   ")
                        val pullParts = mutableListOf<String>()
                        if (session.eventsWritten > 0) pullParts.add("+${session.eventsWritten}")
                        if (session.eventsUpdated > 0) pullParts.add("~${session.eventsUpdated}")
                        if (session.eventsDeleted > 0) pullParts.add("-${session.eventsDeleted}")
                        append("↓ ${if (pullParts.isEmpty()) "0" else pullParts.joinToString(" ")}")
                    }
                }

                appendLine("$icon  ${session.calendarName}  •  ${session.triggerSource.icon} ${session.syncType.name.lowercase().replaceFirstChar { it.uppercase() }}  •  ${dateFormat.format(Date(session.timestamp))}")
                appendLine("   $changes")

                if (session.hasParseFailures) {
                    appendLine("   ⚠ ${session.skippedParseError} failed to parse")
                }
                if (session.hasAlreadySynced) {
                    appendLine("   ${session.skippedAlreadySynced} already synced")
                }
                if (session.hasWarnings) {
                    session.warnings?.forEach { warning ->
                        appendLine("   • $warning")
                    }
                }
                appendLine()
            }
        }
    }

    /** Deletes all session history, in memory and on disk. */
    fun clear() {
        _sessions.value = emptyList()
        file.delete()
        Log.d(TAG, "Cleared all sessions")
    }
}

/** Totals for the sync history header. */
data class SyncSummaryStats(
    val totalSyncs: Int,
    val totalPushed: Int,
    val totalPulled: Int,
    val issueCount: Int
)
