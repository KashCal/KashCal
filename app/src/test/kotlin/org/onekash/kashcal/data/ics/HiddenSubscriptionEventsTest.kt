package org.onekash.kashcal.data.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Event

class HiddenSubscriptionEventsTest {

    private fun event(uid: String, title: String, id: Long = 0) = Event(
        id = id,
        uid = uid,
        calendarId = 1L,
        title = title,
        startTs = 0L,
        endTs = 1L,
        dtstamp = 0L,
    )

    private fun rules(vararg raw: String, subscriptionId: Long = 7L) =
        HiddenSubscriptionEvents.parse(raw.toSet(), subscriptionId)

    @Test
    fun `uid rule hides that uid only`() {
        val kept = event("a", "Day A")
        val hidden = event("b", "Day B")
        val result = HiddenSubscriptionEvents.apply(listOf(kept, hidden), rules("7|U|b"))
        assertEquals(listOf(kept), result)
    }

    @Test
    fun `title rule ignores case and surrounding space and spans uids`() {
        val year1 = event("x-2026", "Holy Day")
        val year2 = event("x-2027", "  holy day ")
        val other = event("y", "Other")
        val result = HiddenSubscriptionEvents.apply(
            listOf(year1, year2, other),
            rules("7|T|holy day"),
        )
        assertEquals(listOf(other), result)
    }

    @Test
    fun `exceptions that share a hidden uid go with it`() {
        val master = event("s", "Series")
        val exception = event("s", "Renamed in one week")
        val result = HiddenSubscriptionEvents.apply(listOf(master, exception), rules("7|T|series"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rules of another subscription and malformed entries are ignored`() {
        val e = event("a", "Day A")
        val parsed = rules("8|U|a", "garbage", "7|Z|a", "x|U|a")
        assertTrue(parsed.isEmpty)
        assertEquals(listOf(e), HiddenSubscriptionEvents.apply(listOf(e), parsed))
    }

    @Test
    fun `a title may contain the separator`() {
        val e = event("a", "Day | Night")
        val parsed = rules("7|T|day | night")
        assertTrue(parsed.hides(e))
    }

    private fun instance(calendarId: Long, eventId: Long, title: String) = DeviceCalendarInstance(
        instanceId = 1L,
        eventId = eventId,
        title = title,
        description = "",
        location = "",
        startTs = 0L,
        endTs = 1L,
        startDay = 20260101,
        endDay = 20260101,
        isAllDay = true,
        hasRrule = false,
        rrule = null,
        reminders = emptyList(),
        calendarId = calendarId,
        calendarDisplayName = "Holidays",
        calendarColor = 0,
        eventColor = null,
        status = 0,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = false,
        originalId = null,
        originalInstanceTime = null,
        timezone = null,
        eventStartTs = 0L,
    )

    @Test
    fun `device event rule hides only that event in that calendar`() {
        val kept = instance(5, 100, "Day A")
        val hidden = instance(5, 101, "Day B")
        val otherCalendar = instance(6, 101, "Day B")
        val rules = HiddenSubscriptionEvents.parseDevice(setOf("d|5|E|101"))
        assertEquals(
            listOf(kept, otherCalendar),
            HiddenSubscriptionEvents.applyDevice(listOf(kept, hidden, otherCalendar), rules),
        )
    }

    @Test
    fun `device title rule spans events of one calendar and ignores case`() {
        val year1 = instance(5, 100, "Holy Day")
        val year2 = instance(5, 200, " HOLY DAY ")
        val otherCalendar = instance(6, 300, "Holy Day")
        val rules = HiddenSubscriptionEvents.parseDevice(setOf("d|5|T|holy day"))
        assertEquals(listOf(otherCalendar), HiddenSubscriptionEvents.applyDevice(listOf(year1, year2, otherCalendar), rules))
    }

    @Test
    fun `subscription and device rules do not read each other`() {
        val raw = setOf("7|U|a", "d|5|E|101", "d|x|E|1", "d|5|E|notanumber")
        assertTrue(HiddenSubscriptionEvents.parse(raw, 5L).isEmpty)
        assertTrue(HiddenSubscriptionEvents.parse(raw, 7L).hides(event("a", "t")))
        val device = HiddenSubscriptionEvents.parseDevice(raw)
        assertTrue(device.hides(5, 101, "t"))
        assertEquals(false, device.hides(5, 1, "t"))
    }

    @Test
    fun `a label after the separator does not change what a rule matches`() {
        val sep = HiddenSubscriptionEvents.LABEL_SEPARATOR
        val raw = setOf("7|U|a${sep}Holy Day", "7|T|night${sep}Night", "d|5|E|101${sep}Day B", "d|5|T|holy day${sep}Holy Day")
        val rules = HiddenSubscriptionEvents.parse(raw, 7L)
        assertTrue(rules.hides(event("a", "x")))
        assertTrue(rules.hides(event("b", "NIGHT")))
        val device = HiddenSubscriptionEvents.parseDevice(raw)
        assertTrue(device.hides(5, 101, "x"))
        assertTrue(device.hides(5, 300, " holy day "))
    }

    @Test
    fun `entries list rules by label, with kind, sorted, skipping malformed ones`() {
        val sep = HiddenSubscriptionEvents.LABEL_SEPARATOR
        val raw = setOf(
            "d|5|T|yom kippur${sep}Yom Kippur",
            "7|U|abc${sep}Zeta Event",
            "d|5|E|101${sep}alpha day",
            "7|T|plain",
            "garbage",
            "d|x|E|1",
        )
        val entries = HiddenSubscriptionEvents.entries(raw)
        assertEquals(listOf("alpha day", "plain", "Yom Kippur", "Zeta Event"), entries.map { it.label })
        assertEquals(listOf(false, true, true, false), entries.map { it.byTitle })
        assertEquals("d|5|E|101${sep}alpha day", entries.first().rule)
    }

    @Test
    fun `an unlabeled rule shows its target`() {
        val entry = HiddenSubscriptionEvents.entries(setOf("7|U|some-uid")).single()
        assertEquals("some-uid", entry.label)
        assertEquals(false, entry.byTitle)
    }

    @Test
    fun `ruleKey strips the label`() {
        val sep = HiddenSubscriptionEvents.LABEL_SEPARATOR
        assertEquals("7|U|a", HiddenSubscriptionEvents.ruleKey("7|U|a${sep}Title"))
        assertEquals("7|U|a", HiddenSubscriptionEvents.ruleKey("7|U|a"))
    }
}
