package org.onekash.kashcal.calendarbackup

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** What the user set up for automatic backup. */
data class CalendarBackupSettings(
    val enabled: Boolean = false,
    /** The chosen folder's tree URI; KashCal keeps a persisted grant for it while set. */
    val folderUri: String? = null,
    val folderName: String? = null,
    val intervalHours: Int = CalendarBackupInterval.DEFAULT_HOURS,
    /** Files kept per calendar, the latest included. */
    val keepCount: Int = DEFAULT_KEEP,
) {
    /** Whether backups run: switched on with a folder chosen. */
    val isActive: Boolean get() = enabled && folderUri != null

    companion object {
        const val MIN_KEEP = 1
        const val MAX_KEEP = 30
        const val DEFAULT_KEEP = 3
    }
}

/** One calendar's outcome in the last backup that reached it. */
@Serializable
data class CalendarBackupResult(
    val kind: Kind,
    /** When the run that gave this outcome happened. */
    val at: Long,
    val fileName: String?,
) {
    enum class Kind { UPDATED, EMPTY, FAILED }
}

/** What the last backups did. */
data class CalendarBackupStatus(
    val lastCheckedAt: Long? = null,
    /** By calendar id. */
    val results: Map<Long, CalendarBackupResult> = emptyMap(),
    /** Why the last run couldn't use the folder at all, or null when it could. */
    val folderProblem: FolderFailure? = null,
    /** Runs in a row with a folder problem or a failed calendar. */
    val failedRuns: Int = 0,
    /** The [BackupAlert.kind] of the failure notification posted for the current problem. */
    val notifiedAlert: String? = null,
)

/**
 * Keeps automatic backup's settings and status in the preferences file [PREFS_NAME], which
 * backup and device transfer exclude: the folder grant can't move to another device, so a
 * restored install starts with backup off. Settings backup doesn't carry these values either.
 *
 * Reads come from the [settings], [status] and [dismissedHint] flows; their writers save the
 * file and publish the new value at once. Values out of range read as their defaults.
 */
@Singleton
class CalendarBackupStore internal constructor(private val prefs: SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val lock = Any()
    private val _settings = MutableStateFlow(readSettings())
    private val _status = MutableStateFlow(readStatus())
    private val _dismissedHint = MutableStateFlow(readDismissedHint())

    val settings: StateFlow<CalendarBackupSettings> = _settings.asStateFlow()
    val status: StateFlow<CalendarBackupStatus> = _status.asStateFlow()

    /** The notifications-off hint the user answered with Not now, or null. */
    val dismissedHint: StateFlow<BackupNotificationState?> = _dismissedHint.asStateFlow()

    fun setDismissedHint(state: BackupNotificationState?) {
        synchronized(lock) {
            prefs.edit().putString(KEY_DISMISSED_HINT, state?.name).apply()
            _dismissedHint.value = state
        }
    }

    /** Replaces the settings with [transform]'s result, interval and copies brought into range. */
    fun updateSettings(transform: (CalendarBackupSettings) -> CalendarBackupSettings) {
        synchronized(lock) {
            val next = transform(_settings.value).let {
                it.copy(
                    intervalHours = CalendarBackupInterval.storedOrDefault(it.intervalHours),
                    keepCount = it.keepCount.coerceIn(CalendarBackupSettings.MIN_KEEP, CalendarBackupSettings.MAX_KEEP),
                )
            }
            prefs.edit()
                .putBoolean(KEY_ENABLED, next.enabled)
                .putString(KEY_FOLDER_URI, next.folderUri)
                .putString(KEY_FOLDER_NAME, next.folderName)
                .putInt(KEY_INTERVAL_HOURS, next.intervalHours)
                .putInt(KEY_KEEP_COUNT, next.keepCount)
                .apply()
            _settings.value = next
        }
    }

    /** Replaces the status with [transform]'s result. */
    fun updateStatus(transform: (CalendarBackupStatus) -> CalendarBackupStatus) {
        synchronized(lock) {
            val next = transform(_status.value)
            prefs.edit()
                .putLong(KEY_LAST_CHECKED, next.lastCheckedAt ?: NONE)
                .putString(KEY_RESULTS, json.encodeToString(next.results.mapKeys { it.key.toString() }))
                .putString(KEY_FOLDER_PROBLEM, next.folderProblem?.name)
                .putInt(KEY_FAILED_RUNS, next.failedRuns)
                .putString(KEY_NOTIFIED, next.notifiedAlert)
                .apply()
            _status.value = next
        }
    }

    private fun readSettings() = CalendarBackupSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        folderUri = prefs.getString(KEY_FOLDER_URI, null),
        folderName = prefs.getString(KEY_FOLDER_NAME, null),
        intervalHours = CalendarBackupInterval.storedOrDefault(
            prefs.getInt(KEY_INTERVAL_HOURS, CalendarBackupInterval.DEFAULT_HOURS)
        ),
        keepCount = prefs.getInt(KEY_KEEP_COUNT, CalendarBackupSettings.DEFAULT_KEEP)
            .takeIf { it in CalendarBackupSettings.MIN_KEEP..CalendarBackupSettings.MAX_KEEP }
            ?: CalendarBackupSettings.DEFAULT_KEEP,
    )

    private fun readStatus() = CalendarBackupStatus(
        lastCheckedAt = prefs.getLong(KEY_LAST_CHECKED, NONE).takeIf { it != NONE },
        results = readResults(),
        folderProblem = prefs.getString(KEY_FOLDER_PROBLEM, null)
            ?.let { name -> FolderFailure.entries.firstOrNull { it.name == name } },
        failedRuns = prefs.getInt(KEY_FAILED_RUNS, 0),
        notifiedAlert = prefs.getString(KEY_NOTIFIED, null),
    )

    private fun readDismissedHint() = prefs.getString(KEY_DISMISSED_HINT, null)
        ?.let { name -> BackupNotificationState.entries.firstOrNull { it.name == name } }

    private fun readResults(): Map<Long, CalendarBackupResult> {
        val text = prefs.getString(KEY_RESULTS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, CalendarBackupResult>>(text)
                .mapNotNull { (id, result) -> id.toLongOrNull()?.let { it to result } }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val PREFS_NAME = "calendar_backup"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_KEEP_COUNT = "keep_count"
        private const val KEY_LAST_CHECKED = "last_checked"
        private const val KEY_RESULTS = "results"
        private const val KEY_FOLDER_PROBLEM = "folder_problem"
        private const val KEY_FAILED_RUNS = "failed_runs"
        private const val KEY_NOTIFIED = "notified_alert"
        private const val KEY_DISMISSED_HINT = "dismissed_notifications_hint"
        private const val NONE = -1L

        private val json = Json { ignoreUnknownKeys = true }
    }
}
