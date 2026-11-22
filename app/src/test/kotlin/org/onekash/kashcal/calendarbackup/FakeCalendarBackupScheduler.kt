package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Records [CalendarBackupScheduler] calls; [runningFlow] drives [running]. */
class FakeCalendarBackupScheduler : CalendarBackupScheduler {

    /** Calls in order: "ensure:<hours>", "cancel", "runNow". */
    val calls = mutableListOf<String>()
    val runningFlow = MutableStateFlow(false)

    /** The armed period, or null when the periodic run is cancelled or was never armed. */
    var armedHours: Int? = null
        private set

    override suspend fun ensurePeriodic(intervalHours: Int) {
        calls += "ensure:$intervalHours"
        armedHours = intervalHours
    }

    override suspend fun cancelPeriodic() {
        calls += "cancel"
        armedHours = null
    }

    override suspend fun runNow() {
        calls += "runNow"
    }

    override val running: Flow<Boolean> get() = runningFlow
}
