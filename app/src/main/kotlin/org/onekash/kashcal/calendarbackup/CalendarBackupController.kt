package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** One offline calendar's line in the Automatic calendar backup sheet. */
data class BackupCalendarRow(
    val calendarId: Long,
    val name: String,
    val color: Int,
    /** The latest file this calendar is saved to. */
    val fileName: String,
    /** The last backup's outcome for it, or null before its first backup. */
    val result: CalendarBackupResult?,
)

/** Everything the Automatic calendar backup sheet shows. */
data class CalendarBackupUiModel(
    val settings: CalendarBackupSettings,
    val status: CalendarBackupStatus,
    val running: Boolean,
    val calendars: List<BackupCalendarRow>,
    /**
     * Why a failure notification couldn't show, while backup is on and the user hasn't answered
     * this same reason with Not now; null otherwise.
     */
    val notificationsHint: BackupNotificationState? = null,
)

/** The answer to [CalendarBackupController.chooseFolder]. */
sealed interface FolderChoice {
    data object Chosen : FolderChoice

    /** The folder wasn't taken; the one chosen before, if any, stays. */
    data class Refused(val reason: FolderFailure) : FolderChoice
}

/**
 * Carries out the Automatic calendar backup sheet's actions: choosing the folder, turning backup
 * on and off, the interval, the number of copies, backing up now, and the notifications-off hint.
 * Each change that affects the schedule reconciles it ([CalendarBackupReconciler]).
 */
@Singleton
class CalendarBackupController @Inject constructor(
    private val store: CalendarBackupStore,
    private val folder: BackupFolder,
    private val scheduler: CalendarBackupScheduler,
    private val reconciler: CalendarBackupReconciler,
    private val alerts: CalendarBackupAlerts,
    private val source: OfflineCalendarSource,
) {

    private val notifications = MutableStateFlow(alerts.notificationState())

    // The hint's two inputs as one flow: typed combine takes at most five.
    private val hint = combine(notifications, store.dismissedHint) { state, dismissed ->
        state.takeIf { it != BackupNotificationState.ON && it != dismissed }
    }

    val state: Flow<CalendarBackupUiModel> = combine(
        store.settings,
        store.status,
        scheduler.running,
        source.observeCalendars(),
        hint,
    ) { settings, status, running, calendars, hint ->
        val bases = CalendarBackupNames.planBaseNames(
            calendars.map { BackupCalendarName(it.id, it.shownName, it.isBuiltIn) }
        )
        CalendarBackupUiModel(
            settings = settings,
            status = status,
            running = running,
            calendars = calendars.map {
                BackupCalendarRow(
                    calendarId = it.id,
                    name = it.shownName,
                    color = it.color,
                    fileName = CalendarBackupNames.latestFileName(bases.getValue(it.id)),
                    result = status.results[it.id],
                )
            },
            notificationsHint = hint.takeIf { settings.isActive },
        )
    }

    /**
     * Makes [tree] the backup folder and turns backup on. Access is taken first and the folder
     * must accept new files; when either fails, the folder chosen before stays and its access is
     * kept, including when the user picked that same folder again. A new folder starts with no
     * results, and its first backup runs at once.
     */
    suspend fun chooseFolder(tree: String): FolderChoice {
        val previous = store.settings.value.folderUri
        try {
            folder.takeAccess(tree)
        } catch (e: BackupFolderException) {
            return FolderChoice.Refused(e.failure)
        }
        val info = try {
            folder.describe(tree)
        } catch (e: BackupFolderException) {
            return refuse(tree, previous, e.failure)
        }
        if (!info.canCreate) return refuse(tree, previous, FolderFailure.UNSUPPORTED)
        if (previous != null && previous != tree) folder.releaseAccess(previous)

        store.updateSettings { it.copy(enabled = true, folderUri = tree, folderName = info.displayName) }
        store.updateStatus {
            if (tree == previous) {
                it.copy(folderProblem = null, failedRuns = 0, notifiedAlert = null)
            } else {
                CalendarBackupStatus()
            }
        }
        alerts.cancel()
        reconciler.reconcile()
        scheduler.runNow()
        return FolderChoice.Chosen
    }

    /** Gives up the grant just taken for [tree], unless it is the folder in use, and refuses. */
    private suspend fun refuse(tree: String, previous: String?, reason: FolderFailure): FolderChoice {
        if (tree != previous) folder.releaseAccess(tree)
        return FolderChoice.Refused(reason)
    }

    /**
     * Turns backup on or off. Off keeps the folder and its access, so turning it back on needs no
     * picker, and removes the failure notification; on runs a backup at once. On without a folder
     * does nothing: the sheet asks for one first.
     */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled && store.settings.value.folderUri == null) return
        store.updateSettings { it.copy(enabled = enabled) }
        if (!enabled) {
            store.updateStatus { it.copy(notifiedAlert = null) }
            alerts.cancel()
        }
        reconciler.reconcile()
        if (enabled) scheduler.runNow()
    }

    /** Sets the interval to [hours], which must pass [CalendarBackupInterval.check]. */
    suspend fun setInterval(hours: Int) {
        require(hours in CalendarBackupInterval.MIN_HOURS..CalendarBackupInterval.MAX_HOURS)
        store.updateSettings { it.copy(intervalHours = hours) }
        reconciler.reconcile()
    }

    /**
     * Sets how many files to keep per calendar and, while backup is on, runs a backup at once so
     * copies beyond the new count are removed now.
     */
    suspend fun setKeep(count: Int) {
        store.updateSettings { it.copy(keepCount = count) }
        if (store.settings.value.isActive) scheduler.runNow()
    }

    /**
     * Returns how many dated copies across all offline calendars a count of [keep] would remove,
     * counting only files the folder can delete; null when the folder can't be read, so the sheet
     * still asks before a later backup removes them.
     */
    suspend fun removableCopies(keep: Int): Int? {
        val tree = store.settings.value.folderUri ?: return 0
        return try {
            val deletable = folder.list(tree).filter { it.supportsDelete }.map { it.name }
            val calendars = source.calendars()
            val bases = CalendarBackupNames.planBaseNames(
                calendars.map { BackupCalendarName(it.id, it.shownName, it.isBuiltIn) }
            )
            bases.values.sumOf { base ->
                CalendarBackupNames.copiesBeyondCount(deletable, base, keep, justAdded = null).size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Runs a backup now, unless backup is off or has no folder. */
    suspend fun backUpNow() {
        if (store.settings.value.isActive) scheduler.runNow()
    }

    /**
     * Reads again whether notifications can show, for when the user may have changed them in
     * system settings. On clears a Not now, so the hint returns if they are turned off later.
     */
    fun refreshNotifications() {
        val current = alerts.notificationState()
        notifications.value = current
        if (current == BackupNotificationState.ON) store.setDismissedHint(null)
    }

    /** Hides the notifications hint for its current reason until notifications are on again. */
    fun dismissNotificationsHint() {
        val current = notifications.value
        if (current != BackupNotificationState.ON) store.setDismissedHint(current)
    }

    /** The current settings, read without collecting [state]. */
    val settings: StateFlow<CalendarBackupSettings> get() = store.settings
}
