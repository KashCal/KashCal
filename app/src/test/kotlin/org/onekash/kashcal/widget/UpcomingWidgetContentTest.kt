package org.onekash.kashcal.widget

import androidx.glance.action.ActionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class UpcomingWidgetContentTest {

    private fun makeEvent(
        eventId: Long = 1L,
        title: String = "Event",
        startTs: Long = 1000L,
        endTs: Long = 2000L,
        isAllDay: Boolean = false,
        calendarColor: Int = 0xFF0000FF.toInt(),
        isPast: Boolean = false,
        isDeviceEvent: Boolean = false,
        startDay: Int = 0
    ) = WidgetDataRepository.WidgetEvent(
        eventId = eventId,
        occurrenceStartTs = startTs,
        title = title,
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        calendarColor = calendarColor,
        isPast = isPast,
        isDeviceEvent = isDeviceEvent,
        startDay = startDay
    )

    // ==================== buildFlatUpcomingItems ====================

    @Test
    fun `buildFlatUpcomingItems returns empty list for empty input`() {
        val items = buildFlatUpcomingItems(emptyMap())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `buildFlatUpcomingItems returns empty list when single day is all past`() {
        val events = listOf(
            makeEvent(eventId = 1, isPast = true),
            makeEvent(eventId = 2, isPast = true)
        )
        val items = buildFlatUpcomingItems(mapOf(20260428 to events))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `buildFlatUpcomingItems emits Header and only non-past events when day has mix`() {
        val past = makeEvent(eventId = 1, title = "Past", isPast = true)
        val future = makeEvent(eventId = 2, title = "Future", isPast = false)
        val items = buildFlatUpcomingItems(mapOf(20260428 to listOf(past, future)))

        assertEquals(2, items.size)
        assertTrue(items[0] is UpcomingWidgetItem.Header)
        val header = items[0] as UpcomingWidgetItem.Header
        assertEquals(20260428, header.dayCode)
        assertEquals(1, header.eventCount)
        assertTrue(items[1] is UpcomingWidgetItem.Event)
        assertEquals("Future", (items[1] as UpcomingWidgetItem.Event).event.title)
    }

    @Test
    fun `buildFlatUpcomingItems preserves ascending day-code ordering`() {
        // Input map with out-of-order keys; output must be ascending by dayCode
        val input = mapOf(
            20260430 to listOf(makeEvent(eventId = 3)),
            20260428 to listOf(makeEvent(eventId = 1)),
            20260429 to listOf(makeEvent(eventId = 2))
        )
        val items = buildFlatUpcomingItems(input)

        val headers = items.filterIsInstance<UpcomingWidgetItem.Header>()
        assertEquals(3, headers.size)
        assertEquals(20260428, headers[0].dayCode)
        assertEquals(20260429, headers[1].dayCode)
        assertEquals(20260430, headers[2].dayCode)
    }

    @Test
    fun `buildFlatUpcomingItems skips entirely-past days in the middle`() {
        val input = linkedMapOf(
            20260428 to listOf(makeEvent(eventId = 1, isPast = false)),
            20260429 to listOf(makeEvent(eventId = 2, isPast = true)),
            20260430 to listOf(makeEvent(eventId = 3, isPast = false))
        )
        val items = buildFlatUpcomingItems(input)

        val dayCodes = items.filterIsInstance<UpcomingWidgetItem.Header>().map { it.dayCode }
        assertEquals(listOf(20260428, 20260430), dayCodes)
        // 20260429 entirely filtered out — no Header, no Event for it
        assertTrue(items.none { it is UpcomingWidgetItem.Header && it.dayCode == 20260429 })
        assertTrue(items.none { it is UpcomingWidgetItem.Event && it.dayCode == 20260429 })
    }

    @Test
    fun `buildFlatUpcomingItems emits unique itemIds across multi-day expansion`() {
        // Same eventId appears on 3 consecutive days (simulating a 3-day event
        // expanded by DisplayEventRepository.generateDayCodesInRange).
        val event = makeEvent(eventId = 42, startDay = 20260428)
        val input = linkedMapOf(
            20260428 to listOf(event),
            20260429 to listOf(event),
            20260430 to listOf(event)
        )
        val items = buildFlatUpcomingItems(input)

        val eventItems = items.filterIsInstance<UpcomingWidgetItem.Event>()
        assertEquals(3, eventItems.size)
        val ids = eventItems.map { it.itemId }
        assertEquals("Cross-day itemIds must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `buildFlatUpcomingItems header eventCount reflects post-filter count`() {
        val events = listOf(
            makeEvent(eventId = 1, isPast = true),
            makeEvent(eventId = 2, isPast = false),
            makeEvent(eventId = 3, isPast = false),
            makeEvent(eventId = 4, isPast = true)
        )
        val items = buildFlatUpcomingItems(mapOf(20260428 to events))

        val header = items[0] as UpcomingWidgetItem.Header
        assertEquals(2, header.eventCount)
        // Header + 2 non-past Event items
        assertEquals(3, items.size)
    }

    @Test
    fun `buildFlatUpcomingItems all itemIds unique across multi-day input`() {
        val input = linkedMapOf(
            20260428 to (1..3).map { makeEvent(eventId = it.toLong()) },
            20260429 to (4..6).map { makeEvent(eventId = it.toLong()) },
            20260430 to (1..2).map { makeEvent(eventId = it.toLong()) } // eventId collision across days
        )
        val items = buildFlatUpcomingItems(input)

        val ids = items.map { it.itemId }
        assertEquals("All itemIds must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `buildFlatUpcomingItems preserves event ordering within a day`() {
        // Caller is responsible for intra-day sort (WidgetDataRepository does this).
        // The builder must preserve the incoming order — NOT re-sort.
        val events = listOf(
            makeEvent(eventId = 1, title = "First"),
            makeEvent(eventId = 2, title = "Second"),
            makeEvent(eventId = 3, title = "Third")
        )
        val items = buildFlatUpcomingItems(mapOf(20260428 to events))

        val titles = items.filterIsInstance<UpcomingWidgetItem.Event>().map { it.event.title }
        assertEquals(listOf("First", "Second", "Third"), titles)
    }

    // ==================== item cap + Footer ====================

    /** Builds a linked map of [dayCount] consecutive days starting at [startDayCode],
     *  each with [eventsPerDay] non-past events. */
    private fun denseDays(
        startDayCode: Int = 20260428,
        dayCount: Int,
        eventsPerDay: Int
    ): Map<Int, List<WidgetDataRepository.WidgetEvent>> {
        val result = linkedMapOf<Int, List<WidgetDataRepository.WidgetEvent>>()
        var day = DayPagerUtils.dayCodeToLocalDate(startDayCode)
        repeat(dayCount) {
            val code = day.year * 10000 + day.monthValue * 100 + day.dayOfMonth
            result[code] = (1..eventsPerDay).map { e -> makeEvent(eventId = e.toLong()) }
            day = day.plusDays(1)
        }
        return result
    }

    @Test
    fun `buildFlatUpcomingItems emits no Footer for empty input`() {
        val items = buildFlatUpcomingItems(emptyMap())
        assertTrue(items.none { it is UpcomingWidgetItem.Footer })
    }

    @Test
    fun `buildFlatUpcomingItems emits no Footer when under cap`() {
        // 10 days x 5 events = 50 events + 10 headers = 60 items, well under 100.
        val items = buildFlatUpcomingItems(denseDays(dayCount = 10, eventsPerDay = 5))
        assertTrue(items.none { it is UpcomingWidgetItem.Footer })
    }

    @Test
    fun `buildFlatUpcomingItems emits no Footer at exactly cap`() {
        // Construct exactly 100 items: 10 days with (1 header + 9 events) each = 10*10 = 100.
        val items = buildFlatUpcomingItems(denseDays(dayCount = 10, eventsPerDay = 9))
        assertEquals(100, items.size)
        assertTrue(items.none { it is UpcomingWidgetItem.Footer })
    }

    @Test
    fun `buildFlatUpcomingItems truncates next day when adding it would exceed 100`() {
        // 10 days * 10 items = 100 (fits), then day 11 adds 10 more which would exceed -> drop day 11.
        // Remaining: 1 day dropped.
        val items = buildFlatUpcomingItems(denseDays(dayCount = 11, eventsPerDay = 9))

        // First 10 days (100 items) included
        val headers = items.filterIsInstance<UpcomingWidgetItem.Header>()
        assertEquals(10, headers.size)
        // Footer emitted with daysDropped = 1
        val footers = items.filterIsInstance<UpcomingWidgetItem.Footer>()
        assertEquals(1, footers.size)
        assertEquals(1, footers[0].daysDropped)
    }

    @Test
    fun `buildFlatUpcomingItems counts all skipped days in daysDropped`() {
        // 15 days * 10 items = 150 (10 days fit = 100 items, drop 5 days).
        val items = buildFlatUpcomingItems(denseDays(dayCount = 15, eventsPerDay = 9))

        val headers = items.filterIsInstance<UpcomingWidgetItem.Header>()
        assertEquals(10, headers.size)
        val footers = items.filterIsInstance<UpcomingWidgetItem.Footer>()
        assertEquals(1, footers.size)
        assertEquals(5, footers[0].daysDropped)
    }

    @Test
    fun `buildFlatUpcomingItems includes first day even if alone it exceeds cap, no Footer`() {
        // 1 day with 150 events — day 1 must still be included; no Footer because
        // daysDropped would be 0 (no subsequent days to drop).
        val items = buildFlatUpcomingItems(denseDays(dayCount = 1, eventsPerDay = 150))

        val headers = items.filterIsInstance<UpcomingWidgetItem.Header>()
        assertEquals(1, headers.size)
        val events = items.filterIsInstance<UpcomingWidgetItem.Event>()
        assertEquals(150, events.size)
        assertTrue(items.none { it is UpcomingWidgetItem.Footer })
    }

    @Test
    fun `buildFlatUpcomingItems Footer itemId does not collide with others`() {
        // Construct input that emits Headers, Events, and a Footer.
        val items = buildFlatUpcomingItems(denseDays(dayCount = 11, eventsPerDay = 9))

        val ids = items.map { it.itemId }
        assertEquals("All itemIds must be unique", ids.size, ids.toSet().size)
        val footer = items.filterIsInstance<UpcomingWidgetItem.Footer>().single()
        assertEquals("Footer itemId should be Long.MAX_VALUE", Long.MAX_VALUE, footer.itemId)
    }

    @Test
    fun `footerActionParameters returns ACTION_GO_TO_TODAY`() {
        val params = footerActionParameters()
        val actionKey = ActionParameters.Key<String>(EXTRA_ACTION)
        assertEquals(ACTION_GO_TO_TODAY, params[actionKey])
    }

    // ==================== upcomingWindow ====================

    private val laZone: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun atMiddayMs(date: LocalDate, zone: ZoneId = laZone): Long =
        date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `upcomingWindow returns todayDayCode as start`() {
        val (start, _) = upcomingWindow(atMiddayMs(LocalDate.of(2026, 4, 28)), laZone)
        assertEquals(20260428, start)
    }

    @Test
    fun `upcomingWindow stays within month for Apr 10 to Apr 19`() {
        val (start, end) = upcomingWindow(atMiddayMs(LocalDate.of(2026, 4, 10)), laZone)
        assertEquals(20260410, start)
        assertEquals(20260419, end)
    }

    @Test
    fun `upcomingWindow crosses month boundary correctly Apr 25 to May 4`() {
        // Naive integer addition would produce 20260425 + 9 = 20260434 — NOT a valid dayCode.
        // Verifies LocalDate arithmetic is used.
        val (start, end) = upcomingWindow(atMiddayMs(LocalDate.of(2026, 4, 25)), laZone)
        assertEquals(20260425, start)
        assertEquals(20260504, end)
    }

    @Test
    fun `upcomingWindow crosses year boundary Dec 23 2026 to Jan 1 2027`() {
        val (start, end) = upcomingWindow(atMiddayMs(LocalDate.of(2026, 12, 23)), laZone)
        assertEquals(20261223, start)
        assertEquals(20270101, end)
    }

    @Test
    fun `upcomingWindow crosses short-month boundary Feb 25 to Mar 6 in non-leap year`() {
        // 2026 is NOT a leap year (Feb has 28 days). Feb 25 + 9 = Mar 6.
        // Confirms LocalDate handles the short-month roll correctly.
        val (start, end) = upcomingWindow(atMiddayMs(LocalDate.of(2026, 2, 25)), laZone)
        assertEquals(20260225, start)
        assertEquals(20260306, end)
    }

    @Test
    fun `upcomingWindow handles leap year Feb 20 2028 to Feb 29 2028`() {
        // 2028 IS a leap year. Feb 20 + 9 = Feb 29. Verifies LocalDate handles Feb 29.
        val (start, end) = upcomingWindow(atMiddayMs(LocalDate.of(2028, 2, 20)), laZone)
        assertEquals(20280220, start)
        assertEquals(20280229, end)
    }

    @Test
    fun `upcomingWindow uses provided zone to pick the local day`() {
        // 2026-04-28 00:30 UTC is still 2026-04-27 in America/Los_Angeles (UTC-7).
        val utcMs = LocalDate.of(2026, 4, 28).atTime(0, 30).atZone(ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val (laStart, laEnd) = upcomingWindow(utcMs, ZoneId.of("America/Los_Angeles"))
        val (utcStart, utcEnd) = upcomingWindow(utcMs, ZoneId.of("UTC"))
        assertEquals(20260427, laStart)
        assertEquals(20260506, laEnd)
        assertEquals(20260428, utcStart)
        assertEquals(20260507, utcEnd)
    }
}
