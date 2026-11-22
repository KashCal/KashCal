package org.onekash.kashcal.calendarbackup

import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow

/**
 * Schedules automatic backup runs. [CalendarBackupReconciler] is the one caller of
 * [ensurePeriodic] and [cancelPeriodic], so no second arming path with its own interval appears.
 */
interface CalendarBackupScheduler {

    /**
     * Arms the periodic run every [intervalHours] if it isn't armed, moves its period if it
     * differs, and otherwise leaves it alone. Idempotent and cheap: it runs on every app start.
     */
    suspend fun ensurePeriodic(intervalHours: Int)

    /** Stops the periodic run, suspending until the cancellation is committed. */
    suspend fun cancelPeriodic()

    /**
     * Asks for a run now ([decideRunNow]): it never cancels a run that is going, and taps while
     * one runs queue at most one more.
     */
    suspend fun runNow()

    /** True while a periodic or one-time run is going. */
    val running: Flow<Boolean>
}

/** What [CalendarBackupScheduler.runNow] does, given the states of the one-time runs. */
enum class RunNowDecision {
    /** A run is queued and hasn't started; it reads the latest settings when it does. */
    NOTHING,

    /** No run is queued or going: queue one. */
    ENQUEUE,

    /** A run is going and none is queued: queue one to start after it. */
    APPEND,
}

/** Decides [CalendarBackupScheduler.runNow] from the one-time work's [states]. */
fun decideRunNow(states: List<WorkInfo.State>): RunNowDecision = when {
    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> RunNowDecision.NOTHING
    states.any { it == WorkInfo.State.RUNNING } -> RunNowDecision.APPEND
    else -> RunNowDecision.ENQUEUE
}
