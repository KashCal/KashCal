package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekWidgetContentTest {

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

    @Test
    fun `empty day produces header and empty item`() {
        val weekEvents = mapOf(20260418 to emptyList<WidgetDataRepository.WidgetEvent>())
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        assertEquals(2, items.size)
        assertTrue(items[0] is WeekWidgetItem.Header)
        assertTrue(items[1] is WeekWidgetItem.Empty)
        assertEquals(20260418, (items[0] as WeekWidgetItem.Header).dayCode)
        assertEquals(20260418, (items[1] as WeekWidgetItem.Empty).dayCode)
    }

    @Test
    fun `day with events produces header and event items`() {
        val events = (1..3).map { makeEvent(eventId = it.toLong(), title = "Event $it") }
        val weekEvents = mapOf(20260418 to events)
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        assertEquals(4, items.size)
        assertTrue(items[0] is WeekWidgetItem.Header)
        assertTrue(items[1] is WeekWidgetItem.Event)
        assertTrue(items[2] is WeekWidgetItem.Event)
        assertTrue(items[3] is WeekWidgetItem.Event)
        assertEquals("Event 1", (items[1] as WeekWidgetItem.Event).event.title)
        assertEquals("Event 2", (items[2] as WeekWidgetItem.Event).event.title)
        assertEquals("Event 3", (items[3] as WeekWidgetItem.Event).event.title)
    }

    @Test
    fun `day with exactly maxEventsPerDay produces no overflow`() {
        val events = (1..15).map { makeEvent(eventId = it.toLong()) }
        val weekEvents = mapOf(20260418 to events)
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 15)

        assertEquals(16, items.size)
        assertTrue(items.last() is WeekWidgetItem.Event)
        assertTrue(items.none { it is WeekWidgetItem.Overflow })
    }

    @Test
    fun `day exceeding maxEventsPerDay produces overflow`() {
        val events = (1..16).map { makeEvent(eventId = it.toLong()) }
        val weekEvents = mapOf(20260418 to events)
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 15)

        assertEquals(17, items.size)
        assertTrue(items.last() is WeekWidgetItem.Overflow)
        val overflow = items.last() as WeekWidgetItem.Overflow
        assertEquals(1, overflow.count)
        assertEquals(20260418, overflow.dayCode)
    }

    @Test
    fun `seven day map produces correct interleaved sequence`() {
        val weekEvents = linkedMapOf(
            20260418 to listOf(makeEvent(eventId = 1)),
            20260419 to emptyList(),
            20260420 to listOf(makeEvent(eventId = 2), makeEvent(eventId = 3)),
            20260421 to emptyList(),
            20260422 to emptyList(),
            20260423 to listOf(makeEvent(eventId = 4)),
            20260424 to emptyList()
        )
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        assertEquals(15, items.size)

        val headers = items.filterIsInstance<WeekWidgetItem.Header>()
        assertEquals(7, headers.size)
        assertEquals(20260418, headers[0].dayCode)
        assertEquals(20260424, headers[6].dayCode)
    }

    @Test
    fun `header stores event count`() {
        val events = (1..3).map { makeEvent(eventId = it.toLong()) }
        val weekEvents = mapOf(20260418 to events)
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        val header = items[0] as WeekWidgetItem.Header
        assertEquals(3, header.eventCount)
        assertEquals(20260418, header.dayCode)
    }

    @Test
    fun `overflow count reflects excess events`() {
        val events = (1..20).map { makeEvent(eventId = it.toLong()) }
        val weekEvents = mapOf(20260418 to events)
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        assertEquals(7, items.size)
        val overflow = items.last() as WeekWidgetItem.Overflow
        assertEquals(15, overflow.count)
    }

    @Test
    fun `event items preserve all widget event data`() {
        val event = makeEvent(
            eventId = 42,
            title = "Important Meeting",
            startTs = 9999L,
            endTs = 19999L,
            isAllDay = true,
            calendarColor = 0xFFFF0000.toInt(),
            isPast = true,
            isDeviceEvent = true
        )
        val weekEvents = mapOf(20260418 to listOf(event))
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        val eventItem = items[1] as WeekWidgetItem.Event
        assertEquals(42L, eventItem.event.eventId)
        assertEquals("Important Meeting", eventItem.event.title)
        assertTrue(eventItem.event.isAllDay)
        assertTrue(eventItem.event.isPast)
        assertTrue(eventItem.event.isDeviceEvent)
    }

    @Test
    fun `all item ids are unique across items`() {
        val weekEvents = linkedMapOf(
            20260418 to listOf(makeEvent(eventId = 1), makeEvent(eventId = 2)),
            20260419 to emptyList(),
            20260420 to (1..10).map { makeEvent(eventId = (100 + it).toLong()) }
        )
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        val ids = items.map { it.itemId }
        assertEquals("All itemIds must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `same event on different days has unique item ids`() {
        val crossMidnightEvent = makeEvent(eventId = 42)
        val weekEvents = linkedMapOf(
            20260418 to listOf(crossMidnightEvent),
            20260419 to listOf(crossMidnightEvent)
        )
        val items = buildFlatWeekItems(weekEvents, maxEventsPerDay = 5)

        val eventItems = items.filterIsInstance<WeekWidgetItem.Event>()
        assertEquals(2, eventItems.size)
        assertTrue(
            "Cross-midnight event must have different itemIds on different days",
            eventItems[0].itemId != eventItems[1].itemId
        )
    }

    @Test
    fun `formatWidgetEventTime returns all day text for all day events`() {
        val event = makeEvent(isAllDay = true, startDay = 20260418)
        assertEquals("All day", formatWidgetEventTime(event, 20260418, "h:mma", "All day"))
    }

    @Test
    fun `formatWidgetEventTime formats timed event on start day`() {
        val zone = java.time.ZoneId.systemDefault()
        val startTs = java.time.LocalDateTime.of(2026, 4, 18, 10, 30)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val event = makeEvent(startTs = startTs, endTs = startTs + 3600_000, startDay = 20260418)
        assertEquals("10:30am", formatWidgetEventTime(event, 20260418, "h:mma", "All day"))
    }
}
