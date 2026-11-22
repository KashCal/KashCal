package org.onekash.kashcal.calendarbackup

/** Records [CalendarBackupAlerts] posts and cancels; [state] is what notifications read as. */
class FakeCalendarBackupAlerts : CalendarBackupAlerts {
    var state = BackupNotificationState.ON
    val posted = mutableListOf<BackupAlert>()
    var cancels = 0

    /** Whether a post would show: true is [BackupNotificationState.ON], false app-level off. */
    var postable: Boolean
        get() = state == BackupNotificationState.ON
        set(value) {
            state = if (value) BackupNotificationState.ON else BackupNotificationState.APP_OFF
        }

    override fun notificationState(): BackupNotificationState = state

    override fun post(alert: BackupAlert) {
        posted += alert
    }

    override fun cancel() {
        cancels++
    }
}
