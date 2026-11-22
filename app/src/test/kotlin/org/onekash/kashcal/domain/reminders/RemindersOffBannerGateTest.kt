package org.onekash.kashcal.domain.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [shouldShowRemindersOffBanner]: the banner is for a user the app never asked. */
class RemindersOffBannerGateTest {

    private fun gate(
        sdkInt: Int = 33,
        permissionGranted: Boolean = false,
        askDone: Boolean = false,
        deniedCount: Int = 0,
        rationaleDue: Boolean = false,
        syncRunning: Boolean = false,
        alreadyShown: Boolean = false,
        eventsWithReminders: Int = 2,
    ) = shouldShowRemindersOffBanner(
        sdkInt, permissionGranted, askDone, deniedCount, rationaleDue, syncRunning, alreadyShown,
        eventsWithReminders,
    )

    @Test
    fun `a never-asked user with reminders this week sees it`() = assertTrue(gate())

    @Test
    fun `Android 12 has no notification permission to ask for`() = assertFalse(gate(sdkInt = 32))

    @Test
    fun `granted permission hides it`() = assertFalse(gate(permissionGranted = true))

    @Test
    fun `an answered ask hides it`() = assertFalse(gate(askDone = true))

    @Test
    fun `a recorded refusal hides it`() = assertFalse(gate(deniedCount = 1))

    @Test
    fun `a rationale due means Android asked before`() = assertFalse(gate(rationaleDue = true))

    @Test
    fun `no reminders this week hides it`() = assertFalse(gate(eventsWithReminders = 0))

    @Test
    fun `a running sync delays the first show`() = assertFalse(gate(syncRunning = true))

    @Test
    fun `a running sync keeps a banner already on screen`() =
        assertTrue(gate(syncRunning = true, alreadyShown = true))
}
