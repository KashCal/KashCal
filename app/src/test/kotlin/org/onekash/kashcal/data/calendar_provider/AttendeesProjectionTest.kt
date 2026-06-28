package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drift guard for [AndroidCalendarProviderRepository.ATTENDEES_PROJECTION].
 *
 * `mapToDeviceAttendee` reads cursor columns positionally, so the projection
 * order is load-bearing — a reordered or extended column would silently
 * misread name/email/relationship/status. This test pins the
 * `CalendarContract.Attendees` five-column projection in order.
 *
 * Robolectric is required: the `Attendees.*` column-name constants are
 * stubbed to null/0 under plain JVM, so the comparison would be vacuous.
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
