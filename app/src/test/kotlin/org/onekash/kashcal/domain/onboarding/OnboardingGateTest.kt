package org.onekash.kashcal.domain.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [shouldShowOnboarding]: the sheet is for a user with nothing set up yet. */
class OnboardingGateTest {

    private val reads = mutableListOf<String>()

    private suspend fun gate(
        completed: Boolean = false,
        signedIn: Boolean = false,
        feedOrBirthdays: Boolean = false,
        deviceCalendars: Boolean = false,
        events: Int = 0,
    ): Boolean = shouldShowOnboarding(
        isCompleted = { reads += "completed"; completed },
        hasSignedInSyncAccount = { reads += "signedIn"; signedIn },
        hasFeedOrBirthdaysAccount = { reads += "feeds"; feedOrBirthdays },
        deviceCalendarsEnabled = { reads += "device"; deviceCalendars },
        eventCount = { reads += "events"; events },
    )

    @Test
    fun `a user with nothing set up sees the sheet`() = runTest {
        assertTrue(gate())
    }

    @Test
    fun `a completed sheet never shows again`() = runTest {
        assertFalse(gate(completed = true))
    }

    @Test
    fun `an iCloud or CalDAV sign-in hides the sheet`() = runTest {
        assertFalse(gate(signedIn = true))
    }

    @Test
    fun `a calendar feed or birthdays account hides the sheet`() = runTest {
        assertFalse(gate(feedOrBirthdays = true))
    }

    @Test
    fun `device calendars on hide the sheet`() = runTest {
        assertFalse(gate(deviceCalendars = true))
    }

    @Test
    fun `any event, such as an offline or imported one, hides the sheet`() = runTest {
        assertFalse(gate(events = 1))
    }

    @Test
    fun `a completed sheet reads no other fact`() = runTest {
        gate(completed = true)
        assertEquals(listOf("completed"), reads)
    }

    @Test
    fun `facts are read in order and the first that hides the sheet stops the reads`() = runTest {
        gate(deviceCalendars = true)
        assertEquals(listOf("completed", "signedIn", "feeds", "device"), reads)
    }
}
