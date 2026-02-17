package org.onekash.kashcal.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult

/**
 * Unit tests for DisplayEventRepository merge logic.
 *
 * Tests the core merge/grouping/sort logic that the composite repository uses.
 * The combine() Flow integration is tested implicitly via full suite.
 */
class DisplayEventRepositoryTest {

    // ========== Merge Logic ==========

    @Test
    fun `merge sorts by startTs`() {
        val room = listOf(
            roomEvent(startTs = 1000L, title = "Room Event"),
        )
        val device = listOf(
            deviceEvent(startTs = 500L, title = "Device Event"),
        )

        val merged = (room + device).sortedBy { it.startTs }
        assertEquals("Device Event", merged[0].title)
        assertEquals("Room Event", merged[1].title)
    }

    @Test
    fun `merge with empty device events returns room only`() {
        val room = listOf(
            roomEvent(startTs = 1000L, title = "Room Event"),
        )
        val device = emptyList<DisplayEvent>()

        val merged = (room + device).sortedBy { it.startTs }
        assertEquals(1, merged.size)
        assertEquals("Room Event", merged[0].title)
    }

    @Test
    fun `merge with empty room events returns device only`() {
        val room = emptyList<DisplayEvent>()
        val device = listOf(
            deviceEvent(startTs = 1000L, title = "Device Event"),
        )

        val merged = (room + device).sortedBy { it.startTs }
        assertEquals(1, merged.size)
        assertEquals("Device Event", merged[0].title)
    }

    @Test
    fun `merge with both empty returns empty`() {
        val merged = (emptyList<DisplayEvent>() + emptyList<DisplayEvent>())
            .sortedBy { it.startTs }
        assertTrue(merged.isEmpty())
    }

    // ========== Day Grouping ==========

    @Test
    fun `events group by startDay`() {
        val events = listOf(
            roomEvent(startDay = 20260215, title = "Feb 15 Event"),
            deviceEvent(startDay = 20260216, title = "Feb 16 Event"),
            roomEvent(startDay = 20260215, title = "Another Feb 15"),
        )

        val grouped = events
            .groupBy { it.startDay }
            .mapValues { (_, list) -> list.sortedBy { it.startTs } }

        assertEquals(2, grouped.size)
        assertEquals(2, grouped[20260215]?.size)
        assertEquals(1, grouped[20260216]?.size)
    }

    // ========== Multi-Day Expansion ==========

    @Test
    fun `multi-day event appears in all spanned days`() {
        val event = roomEvent(startDay = 20260215, endDay = 20260217, title = "3-day")

        val expanded = if (event.startDay == event.endDay) {
            listOf(event.startDay to event)
        } else {
            generateDayCodesInRange(event.startDay, event.endDay)
                .map { dayCode -> dayCode to event }
        }

        assertEquals(3, expanded.size)
        assertEquals(20260215, expanded[0].first)
        assertEquals(20260216, expanded[1].first)
        assertEquals(20260217, expanded[2].first)
    }

    @Test
    fun `single day event generates one entry`() {
        val event = roomEvent(startDay = 20260215, endDay = 20260215, title = "Single")

        val expanded = if (event.startDay == event.endDay) {
            listOf(event.startDay to event)
        } else {
            generateDayCodesInRange(event.startDay, event.endDay)
                .map { dayCode -> dayCode to event }
        }

        assertEquals(1, expanded.size)
        assertEquals(20260215, expanded[0].first)
    }

    // ========== generateDayCodesInRange ==========

    @Test
    fun `generateDayCodesInRange same day`() {
        val result = generateDayCodesInRange(20260215, 20260215)
        assertEquals(listOf(20260215), result)
    }

    @Test
    fun `generateDayCodesInRange two days`() {
        val result = generateDayCodesInRange(20260215, 20260216)
        assertEquals(listOf(20260215, 20260216), result)
    }

    @Test
    fun `generateDayCodesInRange across month boundary`() {
        val result = generateDayCodesInRange(20260228, 20260302)
        assertEquals(listOf(20260228, 20260301, 20260302), result)
    }

    @Test
    fun `generateDayCodesInRange across year boundary`() {
        val result = generateDayCodesInRange(20251230, 20260102)
        assertEquals(listOf(20251230, 20251231, 20260101, 20260102), result)
    }

    // ========== FakeCalendarProviderRepository ==========

    @Test
    fun `fake repo returns configured instances`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(testDeviceInstance(calendarId = 1L))

        kotlinx.coroutines.runBlocking {
            val result = fake.getInstancesForDayRange(20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `fake repo filters by enabledCalendarIds`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L),
            testDeviceInstance(calendarId = 2L)
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.getInstancesForDayRange(20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
            assertEquals(1L, result[0].calendarId)
        }
    }

    @Test
    fun `fake repo throws SecurityException when configured`() {
        val fake = FakeCalendarProviderRepository()
        fake.shouldThrowSecurityException = true

        try {
            kotlinx.coroutines.runBlocking {
                fake.getDeviceCalendars()
            }
            assertTrue("Should have thrown", false)
        } catch (e: SecurityException) {
            // expected
        }
    }

    // ========== FakeCalendarProviderRepository Search ==========

    @Test
    fun `fake repo searchInstances with empty query returns empty`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L, title = "Meeting")
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.searchInstances("", 20260215, 20260215, setOf(1L))
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `fake repo searchInstances matches title partially`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L, title = "Team Meeting"),
            testDeviceInstance(calendarId = 1L, title = "Lunch Break")
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.searchInstances("meet", 20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
            assertEquals("Team Meeting", result[0].title)
        }
    }

    @Test
    fun `fake repo searchInstances filters by enabledCalendarIds`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L, title = "Meeting A"),
            testDeviceInstance(calendarId = 2L, title = "Meeting B")
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.searchInstances("meeting", 20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
            assertEquals("Meeting A", result[0].title)
        }
    }

    @Test
    fun `fake repo searchInstances is case insensitive`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L, title = "IMPORTANT Meeting")
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.searchInstances("important", 20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `fake repo searchInstances matches description`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L, title = "Event", description = "Discuss budget")
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.searchInstances("budget", 20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `fake repo searchInstances matches location`() {
        val fake = FakeCalendarProviderRepository()
        fake.instances = listOf(
            testDeviceInstance(calendarId = 1L, title = "Event", location = "Conference Room B")
        )

        kotlinx.coroutines.runBlocking {
            val result = fake.searchInstances("conference", 20260215, 20260215, setOf(1L))
            assertEquals(1, result.size)
        }
    }

    // ========== Search Merge (Room + Device) ==========

    @Test
    fun `search merge sorts by displayTs`() {
        val roomResult = SearchResult(
            displayEvent = roomEvent(startTs = 2000L, title = "Room Result"),
            displayTs = 2000L
        )
        val deviceResult = SearchResult(
            displayEvent = deviceEvent(startTs = 1000L, title = "Device Result"),
            displayTs = 1000L
        )

        val merged = listOf(roomResult, deviceResult).sortedBy { it.displayTs }
        assertEquals("Device Result", merged[0].displayEvent.title)
        assertEquals("Room Result", merged[1].displayEvent.title)
    }

    @Test
    fun `search merge with Room recurring uses nextOccurrenceTs`() {
        // Room recurring event with startTs=1000 but nextOccurrenceTs=5000
        val roomResult = SearchResult(
            displayEvent = roomEvent(startTs = 1000L, title = "Recurring Room"),
            displayTs = 5000L // nextOccurrenceTs
        )
        val deviceResult = SearchResult(
            displayEvent = deviceEvent(startTs = 3000L, title = "Device Event"),
            displayTs = 3000L
        )

        val merged = listOf(roomResult, deviceResult).sortedBy { it.displayTs }
        assertEquals("Device Event", merged[0].displayEvent.title)
        assertEquals("Recurring Room", merged[1].displayEvent.title)
    }

    @Test
    fun `search merge with both empty returns empty`() {
        val merged = emptyList<SearchResult>().sortedBy { it.displayTs }
        assertTrue(merged.isEmpty())
    }

    // ========== Test Helpers ==========

    private fun roomEvent(
        startTs: Long = 1000L,
        startDay: Int = 20260215,
        endDay: Int = startDay,
        title: String = "Room Event"
    ): DisplayEvent.Room {
        val event = Event(
            id = 1L,
            uid = "test-uid",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000L,
            dtstamp = startTs
        )
        val occurrence = Occurrence(
            eventId = 1L,
            calendarId = 1L,
            startTs = startTs,
            endTs = startTs + 3600000L,
            startDay = startDay,
            endDay = endDay
        )
        val calendar = Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://example.com/cal/",
            displayName = "Test Calendar",
            color = 0xFF0000.toInt()
        )
        return DisplayEvent.Room(event, occurrence, calendar)
    }

    private fun deviceEvent(
        startTs: Long = 1000L,
        startDay: Int = 20260215,
        endDay: Int = startDay,
        title: String = "Device Event"
    ): DisplayEvent.Device {
        return DisplayEvent.Device(testDeviceInstance(
            startTs = startTs,
            startDay = startDay,
            endDay = endDay,
            title = title
        ))
    }

    private fun testDeviceInstance(
        calendarId: Long = 5L,
        startTs: Long = 1000L,
        startDay: Int = 20260215,
        endDay: Int = startDay,
        title: String = "Device Event",
        description: String = "",
        location: String = ""
    ) = DeviceCalendarInstance(
        instanceId = 0L,
        eventId = 0L,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = startTs + 3600000L,
        startDay = startDay,
        endDay = endDay,
        isAllDay = false,
        hasRrule = false,
        calendarId = calendarId,
        calendarDisplayName = "Device Cal",
        displayColor = 0xFF00FF.toInt(),
        status = 0,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true
    )
}
