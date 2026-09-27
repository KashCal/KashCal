package org.onekash.kashcal.data.calendar_provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository.DeviceTitleRow

/**
 * Tests [FakeCalendarProviderRepository.suggestTitlesByPrefix], pinning the contract
 * [AndroidCalendarProviderRepository] must match:
 *  - a DTSTART window bounded on both sides by sinceMs and untilMs
 *  - series (a non-null, non-empty rrule) bypass the window
 *  - the same (title, dtstart) counts once however many calendars show it, as when one invite
 *    reaches two accounts
 */
class FakeTitleSuggestionTest {

    private val now = 1_735_689_600_000L
    private val dayMs = 86_400_000L
    private val window90d = 90 * dayMs
    private val sinceMs = now - window90d
    private val untilMs = now + 7 * dayMs
    private val cal1 = 10L
    private val cal2 = 20L
    private val visible = setOf(cal1, cal2)

    @Test
    fun `non-recurring within window included`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.deviceTitleRows = listOf(
            DeviceTitleRow("Lunch", dtstart = now - 5 * dayMs, calendarId = cal1),
            DeviceTitleRow("Lunch", dtstart = now - 3 * dayMs, calendarId = cal1)
        )

        val results = fake.suggestTitlesByPrefix(
            "Lun", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals(2, results[0].freq)
    }

    @Test
    fun `non-recurring older than sinceMs excluded`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.deviceTitleRows = listOf(
            DeviceTitleRow("Old", dtstart = now - 200 * dayMs, calendarId = cal1),
            DeviceTitleRow("Old", dtstart = now - 180 * dayMs, calendarId = cal1)
        )

        val results = fake.suggestTitlesByPrefix(
            "Old", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `non-recurring beyond untilMs excluded`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.deviceTitleRows = listOf(
            DeviceTitleRow("Far Future", dtstart = now + 30 * dayMs, calendarId = cal1),
            DeviceTitleRow("Far Future", dtstart = now + 35 * dayMs, calendarId = cal1)
        )

        val results = fake.suggestTitlesByPrefix(
            "Far", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `recurring with old DTSTART is included`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.deviceTitleRows = listOf(
            DeviceTitleRow(
                "Weekly Standup",
                dtstart = now - 2 * 365 * dayMs,
                calendarId = cal1,
                rrule = "FREQ=WEEKLY"
            )
        )

        val results = fake.suggestTitlesByPrefix(
            "Wee", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 1, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals("Weekly Standup", results[0].title)
    }

    @Test
    fun `recurring with empty rrule string does NOT bypass window`() = runTest {
        val fake = FakeCalendarProviderRepository()
        fake.deviceTitleRows = listOf(
            DeviceTitleRow("Empty RRule", dtstart = now - 200 * dayMs, calendarId = cal1, rrule = ""),
            DeviceTitleRow("Empty RRule", dtstart = now - 180 * dayMs, calendarId = cal1, rrule = "")
        )

        val results = fake.suggestTitlesByPrefix(
            "Emp", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `cross-calendar dedup - same title and dtstart on two calendars counts once`() = runTest {
        // The same invite on a personal (cal1) and a work (cal2) account is one use, not two.
        val fake = FakeCalendarProviderRepository()
        val ts = now - 5 * dayMs
        fake.deviceTitleRows = listOf(
            DeviceTitleRow("All Hands", dtstart = ts, calendarId = cal1),
            DeviceTitleRow("All Hands", dtstart = ts, calendarId = cal2)
        )

        val results = fake.suggestTitlesByPrefix(
            "All", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 2, limit = 5
        )

        // freq=1 after dedup, below minFreq=2, so nothing is suggested.
        assertTrue(
            "Cross-calendar duplicates must dedup so minFreq=2 excludes a truly one-off event",
            results.isEmpty()
        )
    }

    @Test
    fun `cross-calendar dedup - different dtstart still counts separately`() = runTest {
        // Two dates of the same title on different calendars are two uses.
        val fake = FakeCalendarProviderRepository()
        fake.deviceTitleRows = listOf(
            DeviceTitleRow("All Hands", dtstart = now - 7 * dayMs, calendarId = cal1),
            DeviceTitleRow("All Hands", dtstart = now - 3 * dayMs, calendarId = cal2)
        )

        val results = fake.suggestTitlesByPrefix(
            "All", sinceMs = sinceMs, untilMs = untilMs,
            visibleCalendarIds = visible, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals(2, results[0].freq)
    }
}
