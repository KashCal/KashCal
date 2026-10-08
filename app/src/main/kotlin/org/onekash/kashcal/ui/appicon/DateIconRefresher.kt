package org.onekash.kashcal.ui.appicon

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Keeps the date launcher icon on today's day of the month, and repairs a launcher entry left with
 * no alias or several aliases enabled ([AppIconUtility.refreshDateIcon]).
 *
 * Callers: the midnight alarm, clock and timezone changes, boot, and shortly after the last KashCal
 * screen stops ([ForegroundActivityTracker.onLastStop]). The platform removes a task when the
 * alias it was launched from is disabled, so a switch waits while KashCal is in use and while
 * another app's screen (a share or view screen started from KashCal) sits in such a task
 * ([canSwitch]). A waiting switch catches up at the next trigger. A backgrounded KashCal task
 * loses its Recents card at the switch. The platform applies the removal when its delayed
 * package-change broadcast arrives (about a second later), so reopening KashCal inside that second
 * can still close it.
 */
class DateIconRefresher internal constructor(
    private val context: Context,
    private val isKashCalInUse: () -> Boolean,
    private val ownTasks: () -> List<OwnTask>,
) {

    /** One of the app's tasks: the component it was launched from and its top activity. */
    internal data class OwnTask(val base: ComponentName?, val top: ComponentName?)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        tracker: ForegroundActivityTracker,
    ) : this(
        context,
        { !tracker.registered || tracker.startedCount > 0 || tracker.stoppedByScreenOff },
        { appTasks(context) },
    )

    /** Moves the date icon to [today] when it may; logs and swallows any failure. */
    fun refresh(today: LocalDate = LocalDate.now()) {
        try {
            AppIconUtility(context).refreshDateIcon(today, ::canSwitch)
        } catch (e: Exception) {
            Log.e(TAG, "Date icon refresh failed", e)
        }
    }

    /**
     * True when KashCal isn't in use ([ForegroundActivityTracker]) and no task launched from a
     * launcher alias has another app's screen on top. Tasks launched another way (notification,
     * widget, shortcut) aren't removed by an alias switch, so they don't hold it. A null top (a
     * task with nothing running, for example after a reboot) doesn't hold the switch; any other
     * package, including an emptied one, does. A failed lookup holds it.
     */
    private fun canSwitch(): Boolean {
        if (isKashCalInUse()) return false
        val tasks = runCatching { ownTasks() }.getOrElse {
            Log.w(TAG, "Task lookup failed; date icon switch waits", it)
            return false
        }
        val aliases = LauncherAliases.ALL.map { context.packageName + it }.toSet()
        return tasks
            .filter { it.base?.className in aliases }
            .all { it.top == null || it.top.packageName == context.packageName }
    }

    private companion object {
        const val TAG = "DateIconRefresher"

        /** The app's own tasks; a task that went away after appTasks listed it is skipped. */
        fun appTasks(context: Context): List<OwnTask> =
            context.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { task ->
                // getTaskInfo throws if the task went away after appTasks listed it.
                runCatching { task.taskInfo }.getOrNull()?.let {
                    OwnTask(base = it.baseIntent?.component, top = it.topActivity)
                }
            }
    }
}
