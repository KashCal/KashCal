package org.onekash.kashcal.ui.appicon

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [ForegroundActivityTracker]: the count of started KashCal activities, and the callback when
 * the last one stops, which is skipped for a configuration relaunch.
 */
class ForegroundActivityTrackerTest {

    private var lastStops = 0
    private var screenOn = true
    private val tracker = ForegroundActivityTracker { screenOn }.apply { onLastStop = { lastStops++ } }

    private fun activity(changingConfigurations: Boolean = false) = mockk<Activity> {
        every { isChangingConfigurations } returns changingConfigurations
    }

    @Test
    fun `a start and stop pair counts up and back down and fires once`() {
        val main = activity()
        tracker.onActivityStarted(main)
        assertEquals(1, tracker.startedCount)

        tracker.onActivityStopped(main)

        assertEquals(0, tracker.startedCount)
        assertEquals(1, lastStops)
    }

    @Test
    fun `handing off to Settings fires only when the last screen stops`() {
        val main = activity()
        val settings = activity()

        tracker.onActivityStarted(main)
        tracker.onActivityStarted(settings)
        tracker.onActivityStopped(main)
        assertEquals("Settings is still up", 0, lastStops)

        tracker.onActivityStopped(settings)
        assertEquals(1, lastStops)
    }

    @Test
    fun `a configuration relaunch doesn't fire`() {
        val old = activity(changingConfigurations = true)
        tracker.onActivityStarted(old)

        tracker.onActivityStopped(old)

        assertEquals(0, lastStops)
    }

    @Test
    fun `the count never goes negative`() {
        tracker.onActivityStopped(activity())
        tracker.onActivityStopped(activity())

        assertEquals(0, tracker.startedCount)
    }

    @Test
    fun `it reports registered only after register`() {
        assertEquals(false, tracker.registered)
        tracker.register(mockk(relaxed = true))
        assertEquals(true, tracker.registered)
    }

    @Test
    fun `a last stop with the screen off holds KashCal as in use until it is seen again`() {
        val main = activity()
        tracker.onActivityStarted(main)
        screenOn = false
        tracker.onActivityStopped(main)
        assertEquals(true, tracker.stoppedByScreenOff)

        screenOn = true
        tracker.onActivityStarted(main)
        tracker.onActivityStopped(main)
        assertEquals(false, tracker.stoppedByScreenOff)
    }
}
