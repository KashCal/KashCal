package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [AndroidCalendarProviderRepository.ATTENDEES_PROJECTION] to its five columns in order.
 *
 * `mapToDeviceAttendee` reads cursor columns by position, so a reordered or inserted column
 * would silently misread name, email, relationship or status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AttendeesProjectionTest {

    @Test
    fun `projection is the canonical five columns in order`() {
        assertArrayEquals(
            arrayOf(
                Attendees._ID,
                Attendees.ATTENDEE_NAME,
                Attendees.ATTENDEE_EMAIL,
                Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.ATTENDEE_STATUS,
            ),
            AndroidCalendarProviderRepository.ATTENDEES_PROJECTION
        )
    }

    @Test
    fun `projection has exactly five columns`() {
        assertEquals(5, AndroidCalendarProviderRepository.ATTENDEES_PROJECTION.size)
    }
}
