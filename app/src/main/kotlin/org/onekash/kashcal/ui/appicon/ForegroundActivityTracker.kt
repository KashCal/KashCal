package org.onekash.kashcal.ui.appicon

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts the app's started activities, and runs [onLastStop] when the last one stops.
 *
 * [DateIconRefresher] reads [startedCount] to keep a background date switch from closing a
 * screen the user is looking at; [onLastStop] is where a deferred switch catches up. A stop that
 * is part of a configuration relaunch doesn't fire, since the new instance starts right after.
 * The count is read off the main thread, so it is atomic. During a configuration relaunch the count
 * is 0 between the old instance's stop and the new one's start; a background trigger landing in
 * that one main-thread transaction can switch. That window is accepted.
 */
@Singleton
class ForegroundActivityTracker internal constructor(
    private val isScreenOn: () -> Boolean,
) : Application.ActivityLifecycleCallbacks {

    @Inject
    constructor(@ApplicationContext context: Context) : this({
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    })

    private val started = AtomicInteger(0)

    /** Runs on the main thread when the last started activity stops; set by KashCalApplication. */
    @Volatile
    var onLastStop: (() -> Unit)? = null

    /** False until [register] runs; [DateIconRefresher] treats an unregistered count as unknown. */
    @Volatile
    var registered: Boolean = false
        private set

    val startedCount: Int get() = started.get()

    /**
     * True when the last KashCal screen stopped because the screen went off, for example the phone
     * locked with KashCal open. Its task is still what the user returns to, so it counts as in use
     * until a KashCal screen starts again.
     */
    @Volatile
    var stoppedByScreenOff: Boolean = false
        private set

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        registered = true
    }

    override fun onActivityStarted(activity: Activity) {
        stoppedByScreenOff = false
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        val count = started.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (count == 0 && !activity.isChangingConfigurations) {
            stoppedByScreenOff = !isScreenOn()
            onLastStop?.invoke()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
