package org.onekash.kashcal.ui.components

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Tests [RecentChangeFormatter], the wording of a Recent changes row: the day headers, the
 * event's own date and time (a series by its time of day, never its first date), and what
 * changed ("Moved from ...", "Time changed", location, title, repeat, cancelled). Runs in
 * en-US, America/Chicago, at a fixed now (Mon Oct 5 2026, 3:00 PM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class RecentChangeFormatterTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val savedLocale = Locale.getDefault()
    private lateinit var resources: Resources

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun allDay(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val now get() = at(2026, 10, 5, 15)

    private fun formatter(timePattern: String = "h:mm a") =
        RecentChangeFormatter(resources, timePattern, zone, Locale.US)

    private fun item(
        type: ChangeType = ChangeType.MODIFIED,
        start: Long = at(2026, 10, 6, 14),
        end: Long = start + 3_600_000,
        allDay: Boolean = false,
        recurring: Boolean = false,
        instanceTs: Long = 0,
        fields: Set<ChangedField> = emptySet(),
        previousStart: Long? = null,
        previousAllDay: Boolean? = previousStart?.let { allDay },
        detectedAt: Long = now,
    ) = RecentChangeItem(
        id = 1, calendarId = 1, eventUid = "u", instanceTs = instanceTs, eventId = 1, changeType = type,
        title = "Standup", startTs = start, endTs = end, isAllDay = allDay, isRecurring = recurring,
        detectedAt = detectedAt, changedFields = fields, previousStartTs = previousStart,
        previousIsAllDay = previousAllDay, calendarName = "Work", calendarColor = 0
    )

    @Before
    fun setup() {
        Locale.setDefault(Locale.US)
        resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    // ---- day headers ----

    @Test
    fun `headers read Today, Yesterday, then the date`() {
        val f = formatter()
        assertEquals("Today", f.dayHeader(at(2026, 10, 5, 0, 5), now))
        assertEquals("Yesterday", f.dayHeader(at(2026, 10, 4, 23, 59), now))
        assertEquals("Tue, Sep 29", f.dayHeader(at(2026, 9, 29, 9), now))
    }

    @Test
    fun `a header in another year carries the year`() {
        assertEquals("Wed, Dec 31, 2025", formatter().dayHeader(at(2025, 12, 31, 9), now))
    }

    // ---- the event's own date and time ----

    @Test
    fun `a one-off shows its date and time`() {
        assertEquals("Tue, Oct 6, 2:00 PM", formatter().whenText(item(), now))
    }

    @Test
    fun `the user's 24-hour setting is honored`() {
        assertEquals("Tue, Oct 6, 14:00", formatter("HH:mm").whenText(item(), now))
    }

    @Test
    fun `an all-day event shows its UTC date only`() {
        assertEquals("Tue, Oct 6", formatter().whenText(item(start = allDay(2026, 10, 6), allDay = true), now))
    }

    @Test
    fun `a date in another year carries the year`() {
        assertEquals("Tue, Jan 5, 2027, 9:00 AM", formatter().whenText(item(start = at(2027, 1, 5, 9)), now))
    }

    @Test
    fun `an all-day date in a past or a future year carries the year`() {
        assertEquals("Wed, Dec 31, 2025", formatter().whenText(item(start = allDay(2025, 12, 31), allDay = true), now))
        assertEquals("Fri, Jan 1, 2027", formatter().whenText(item(start = allDay(2027, 1, 1), allDay = true), now))
    }

    @Test
    fun `around new year the header and dates follow the phone's calendar year`() {
        val newYearsEve = at(2026, 12, 31, 23, 30)
        val justAfter = at(2027, 1, 1, 0, 30)
        val f = formatter()
        assertEquals("Yesterday", f.dayHeader(newYearsEve, justAfter))
        assertEquals("Wed, Dec 30, 2026", f.dayHeader(at(2026, 12, 30, 12), justAfter))
        assertEquals("Fri, Jan 1, 2027", f.whenText(item(start = allDay(2027, 1, 1), allDay = true), newYearsEve))
        assertEquals("Thu, Dec 31", f.whenText(item(start = allDay(2026, 12, 31), allDay = true), newYearsEve))
    }

    @Test
    fun `a series shows its time of day, not its first date`() {
        val series = item(start = at(2026, 7, 20, 14), recurring = true, fields = setOf(ChangedField.TITLE))
        assertEquals("2:00 PM", formatter().whenText(series, now))
    }

    @Test
    fun `a series moved to another weekday shows the weekday`() {
        val series = item(
            start = at(2026, 7, 21, 14), recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = at(2026, 7, 20, 14)
        )
        assertEquals("Tue, 2:00 PM", formatter().whenText(series, now))
    }

    @Test
    fun `an all-day series shows the weekday of its start`() {
        val series = item(start = allDay(2026, 7, 20), allDay = true, recurring = true, fields = setOf(ChangedField.TITLE))
        assertEquals("Mon", formatter().whenText(series, now))
    }

    @Test
    fun `a changed occurrence shows its full date even though it repeats`() {
        val occurrence = item(start = at(2026, 10, 13, 15), recurring = true, instanceTs = at(2026, 10, 13, 14))
        assertEquals("Tue, Oct 13, 3:00 PM", formatter().whenText(occurrence, now))
    }

    @Test
    fun `an end-only change shows the start and the new end`() {
        val changed = item(end = at(2026, 10, 6, 15, 30), fields = setOf(ChangedField.TIME))
        assertEquals("Tue, Oct 6, 2:00 PM – 3:30 PM", formatter().whenText(changed, now))
    }

    // ---- what changed ----

    @Test
    fun `a same-day move names the old time only`() {
        val moved = item(start = at(2026, 10, 6, 15), fields = setOf(ChangedField.TIME), previousStart = at(2026, 10, 6, 14))
        assertEquals("Moved from 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `a move to another day names the old date and time`() {
        val moved = item(start = at(2026, 10, 6, 14), fields = setOf(ChangedField.TIME), previousStart = at(2026, 9, 29, 14))
        assertEquals("Moved from Tue, Sep 29, 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `an all-day move names the old date only`() {
        val moved = item(
            start = allDay(2026, 10, 6), allDay = true, fields = setOf(ChangedField.TIME),
            previousStart = allDay(2026, 9, 29)
        )
        assertEquals("Moved from Tue, Sep 29", formatter().detail(moved))
    }

    @Test
    fun `a switch from timed to all-day formats the old value as timed`() {
        val moved = item(
            start = allDay(2026, 10, 6), allDay = true, fields = setOf(ChangedField.TIME),
            previousStart = at(2026, 9, 29, 14), previousAllDay = false
        )
        assertEquals("Moved from Tue, Sep 29, 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `a series moved within its day names the old time`() {
        val moved = item(
            start = at(2026, 7, 20, 15), recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = at(2026, 7, 20, 14)
        )
        assertEquals("Moved from 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `a series moved to another weekday names the old weekday, never a date`() {
        val moved = item(
            start = at(2026, 7, 21, 14), recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = at(2026, 7, 20, 14)
        )
        assertEquals("Moved from Mon, 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `an all-day series moved names the old weekday`() {
        val moved = item(
            start = allDay(2026, 7, 21), allDay = true, recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = allDay(2026, 7, 20)
        )
        assertEquals("Moved from Mon", formatter().detail(moved))
    }

    @Test
    fun `a series moved by a whole week shows its dates, since the slot reads the same`() {
        val moved = item(
            start = at(2026, 7, 27, 14), recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = at(2026, 7, 20, 14)
        )
        assertEquals("Mon, Jul 27, 2:00 PM", formatter().whenText(moved, now))
        assertEquals("Moved from Mon, Jul 20, 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `a series deleted after a whole-week move shows its slot, not its first date`() {
        // A row merged from a week move and a later delete keeps the move's previous start.
        val deleted = item(
            type = ChangeType.DELETED, start = at(2026, 7, 27, 14), recurring = true,
            previousStart = at(2026, 7, 20, 14)
        )
        assertEquals("2:00 PM", formatter().whenText(deleted, now))
    }

    @Test
    fun `an all-day series moved by a whole week shows its dates`() {
        val moved = item(
            start = allDay(2026, 7, 27), allDay = true, recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = allDay(2026, 7, 20)
        )
        assertEquals("Mon, Jul 27", formatter().whenText(moved, now))
        assertEquals("Moved from Mon, Jul 20", formatter().detail(moved))
    }

    @Test
    fun `a timed series turned all-day on the same weekday keeps the slot wording`() {
        val switched = item(
            start = allDay(2026, 7, 20), allDay = true, recurring = true, fields = setOf(ChangedField.TIME),
            previousStart = at(2026, 7, 20, 14), previousAllDay = false
        )
        assertEquals("Mon", formatter().whenText(switched, now))
        assertEquals("Moved from Mon, 2:00 PM", formatter().detail(switched))
    }

    @Test
    fun `a moved occurrence names its original slot`() {
        val slot = at(2026, 10, 13, 14)
        val moved = item(
            start = at(2026, 10, 14, 14), recurring = true, instanceTs = slot,
            fields = setOf(ChangedField.TIME), previousStart = slot
        )
        assertEquals("Moved from Tue, Oct 13, 2:00 PM", formatter().detail(moved))
    }

    @Test
    fun `a previous start equal to the current one is no move`() {
        val back = item(fields = setOf(ChangedField.TIME), previousStart = at(2026, 10, 6, 14))
        assertEquals("Time changed", formatter().detail(back))
    }

    @Test
    fun `an end-only change reads Time changed`() {
        assertEquals("Time changed", formatter().detail(item(fields = setOf(ChangedField.TIME))))
    }

    @Test
    fun `location, title and repeat changes are named together`() {
        val changed = item(fields = setOf(ChangedField.LOCATION, ChangedField.TITLE, ChangedField.RECURRENCE))
        assertEquals("Location changed · Title changed · Repeat changed", formatter().detail(changed))
    }

    @Test
    fun `a move and a location change are named together`() {
        val changed = item(
            start = at(2026, 10, 6, 15), fields = setOf(ChangedField.TIME, ChangedField.LOCATION),
            previousStart = at(2026, 10, 6, 14)
        )
        assertEquals("Moved from 2:00 PM · Location changed", formatter().detail(changed))
    }

    @Test
    fun `other changes alone and new events have no detail line`() {
        assertNull(formatter().detail(item(fields = setOf(ChangedField.OTHER))))
        assertNull(formatter().detail(item(type = ChangeType.NEW)))
        assertNull(formatter().detail(item(type = ChangeType.DELETED)))
    }

    @Test
    fun `a cancelled occurrence reads Cancelled`() {
        val cancelled = item(type = ChangeType.DELETED, recurring = true, instanceTs = at(2026, 10, 13, 14), start = at(2026, 10, 13, 14))
        assertEquals("Cancelled", formatter().detail(cancelled))
        assertEquals("Tue, Oct 13, 2:00 PM", formatter().whenText(cancelled, now))
    }
}
