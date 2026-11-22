package org.onekash.kashcal.calendarbackup

/** The failure the backup notification reports. */
sealed interface BackupAlert {
    /** Names the kind of problem; stored, so it doesn't follow class names R8 may rename. */
    val kind: String

    /** The folder can't be reached: access lost, or it was moved, deleted or disconnected. */
    data class FolderUnreachable(val folderName: String?) : BackupAlert {
        override val kind get() = "FolderUnreachable"
    }

    /** The folder's provider doesn't let KashCal save files there. */
    data class FolderUnsupported(val folderName: String?) : BackupAlert {
        override val kind get() = "FolderUnsupported"
    }

    /** Some calendars couldn't be saved; [fileNames] are their latest files. */
    data class CalendarsFailed(val fileNames: List<String>) : BackupAlert {
        override val kind get() = "CalendarsFailed"
    }
}

/** Decides whether a finished run posts, keeps or removes the backup notification. */
object CalendarBackupAlertPolicy {

    sealed interface Action {
        data class Notify(val alert: BackupAlert) : Action
        data object Cancel : Action
        data object None : Action
    }

    /** Failed runs in a row before a problem that can heal by itself is reported. */
    const val RUNS_BEFORE_ALERT = 2

    /**
     * Returns the action after [outcome]. A clean run removes the notification. A problem is
     * posted once ([notifiedAlert] holds the kind already shown) and a different one replaces it.
     * Only periodic runs post: a run started from the sheet shows its problem there. Lost access
     * and a folder that can't hold files never heal by themselves, so they are reported on the
     * first failed run; anything else after [RUNS_BEFORE_ALERT] failed runs in a row
     * ([failedRuns], this run included), so one run with a cloud folder offline doesn't alert.
     */
    fun decide(
        outcome: BackupRunOutcome.Done,
        failedRuns: Int,
        notifiedAlert: String?,
        folderName: String?,
    ): Action {
        val problem = outcome.folderProblem
        if (problem == null && outcome.failedFiles.isEmpty()) return Action.Cancel
        if (outcome.kind == RunKind.ONE_TIME) return Action.None
        val neverHeals = problem == FolderFailure.NO_ACCESS || problem == FolderFailure.UNSUPPORTED
        if (!neverHeals && failedRuns < RUNS_BEFORE_ALERT) return Action.None
        val alert = when (problem) {
            null -> BackupAlert.CalendarsFailed(outcome.failedFiles)
            FolderFailure.UNSUPPORTED -> BackupAlert.FolderUnsupported(folderName)
            else -> BackupAlert.FolderUnreachable(folderName)
        }
        return if (alert.kind == notifiedAlert) Action.None else Action.Notify(alert)
    }
}

/** Whether the backup failure notification can show, and what blocks it if not. */
enum class BackupNotificationState {
    ON,

    /** KashCal's notifications are off or not allowed. */
    APP_OFF,

    /** Only the Automatic calendar backup channel is blocked, or it doesn't exist. */
    CHANNEL_OFF,
}

/** Posts and removes the automatic backup failure notification. */
interface CalendarBackupAlerts {

    /** Returns whether a notification posted now would show, and why not. */
    fun notificationState(): BackupNotificationState

    /** Returns whether a notification posted now would show. */
    fun canPost(): Boolean = notificationState() == BackupNotificationState.ON

    /** Shows [alert], replacing the backup notification already shown, if any. */
    fun post(alert: BackupAlert)

    /** Removes the backup notification, if one is shown. */
    fun cancel()
}
