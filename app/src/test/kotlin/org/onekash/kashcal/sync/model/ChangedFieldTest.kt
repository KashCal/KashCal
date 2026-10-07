package org.onekash.kashcal.sync.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event

/**
 * Tests [ChangedField.between] and [ChangedField.cancelledInstances], which decide what the
 * Recent changes sheet says changed. Fields a user doesn't see as a change (alarms, busy/free,
 * sequence, server stamps) must give no category, so an iOS first touch (default alarm + TRANSP)
 * isn't shown as "updated".
 */
class ChangedFieldTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = 1_790_000_000_000L // a fixed instant, no clock reads

    private fun event(
        startTs: Long = start,
        endTs: Long = start + hour,
        isAllDay: Boolean = false,
        timezone: String? = "America/Chicago",
        title: String = "Standup",
        location: String? = "Room 1",
        rrule: String? = null,
        exdate: String? = null,
    ) = Event(
        uid = "u", calendarId = 1, title = title, location = location,
        startTs = startTs, endTs = endTs, isAllDay = isAllDay, timezone = timezone,
        rrule = rrule, exdate = exdate, dtstamp = 0
    )

    @Test
    fun `identical events have no changed fields`() {
        assertEquals(emptySet<ChangedField>(), ChangedField.between(event(), event()))
    }

    @Test
    fun `a moved start is TIME`() {
        val moved = event(startTs = start + hour, endTs = start + 2 * hour)
        assertEquals(setOf(ChangedField.TIME), ChangedField.between(event(), moved))
    }

    @Test
    fun `an end-only change is TIME`() {
        assertEquals(setOf(ChangedField.TIME), ChangedField.between(event(), event(endTs = start + 2 * hour)))
    }

    @Test
    fun `an all-day switch is TIME`() {
        assertEquals(setOf(ChangedField.TIME), ChangedField.between(event(), event(isAllDay = true)))
    }

    @Test
    fun `a timezone change at the same instants is not a change`() {
        assertEquals(emptySet<ChangedField>(), ChangedField.between(event(), event(timezone = "Europe/Berlin")))
    }

    @Test
    fun `location, title and rule changes have their own categories`() {
        assertEquals(setOf(ChangedField.LOCATION), ChangedField.between(event(), event(location = "Room 2")))
        assertEquals(setOf(ChangedField.LOCATION), ChangedField.between(event(), event().copy(geoLat = 1.0)))
        assertEquals(setOf(ChangedField.TITLE), ChangedField.between(event(), event(title = "Retro")))
        assertEquals(
            setOf(ChangedField.RECURRENCE),
            ChangedField.between(event(rrule = "FREQ=WEEKLY"), event(rrule = "FREQ=DAILY"))
        )
    }

    @Test
    fun `other visible fields are OTHER`() {
        val base = event()
        for (changed in listOf(
            base.copy(description = "notes"),
            base.copy(status = "TENTATIVE"),
            base.copy(url = "https://example.test"),
            base.copy(color = 5),
            base.copy(categories = listOf("Work")),
            base.copy(classification = "PRIVATE"),
            base.copy(priority = 1),
            base.copy(organizerEmail = "boss@example.test"),
            base.copy(organizerName = "Boss"),
        )) {
            assertEquals("$changed", setOf(ChangedField.OTHER), ChangedField.between(base, changed))
        }
    }

    @Test
    fun `alarms, busy-free, sequence and server stamps are not a change`() {
        val base = event()
        for (changed in listOf(
            base.copy(reminders = listOf("-PT15M")),
            base.copy(alarmCount = 1),
            base.copy(transp = "TRANSPARENT"),
            base.copy(sequence = 3),
            base.copy(extraProperties = mapOf("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR" to "AUTOMATIC")),
            base.copy(serverModifiedAt = 99),
            base.copy(organizerScheduleStatus = "1.2"),
            base.copy(organizerSentBy = "mailto:assistant@example.test"),
            base.copy(etag = "e2", dtstamp = 5, rawIcal = "x", caldavUrl = "https://x/1.ics"),
        )) {
            assertEquals("$changed", emptySet<ChangedField>(), ChangedField.between(base, changed))
        }
    }

    @Test
    fun `empty and unset categories compare equal`() {
        assertEquals(
            emptySet<ChangedField>(),
            ChangedField.between(event().copy(categories = emptyList()), event().copy(categories = null))
        )
    }

    @Test
    fun `a new EXDATE is a cancelled instance, not a field change`() {
        val series = event(rrule = "FREQ=WEEKLY")
        val cancelled = series.copy(exdate = "${start + 7 * day}")

        assertEquals(emptySet<ChangedField>(), ChangedField.between(series, cancelled))
        assertEquals(listOf(start + 7 * day), ChangedField.cancelledInstances(series, cancelled))
    }

    @Test
    fun `a removed EXDATE is a RECURRENCE change`() {
        val cancelled = event(rrule = "FREQ=WEEKLY", exdate = "${start + 7 * day}")
        val restored = cancelled.copy(exdate = null)

        assertEquals(setOf(ChangedField.RECURRENCE), ChangedField.between(cancelled, restored))
        assertEquals(emptyList<Long>(), ChangedField.cancelledInstances(cancelled, restored))
    }

    @Test
    fun `a series moved an hour with its EXDATE rewritten cancels nothing`() {
        val before = event(rrule = "FREQ=WEEKLY", exdate = "${start + 7 * day}")
        val after = before.copy(startTs = start + hour, endTs = start + 2 * hour, exdate = "${start + 7 * day + hour}")

        assertEquals(emptyList<Long>(), ChangedField.cancelledInstances(before, after))
        assertEquals(setOf(ChangedField.TIME), ChangedField.between(before, after))
    }

    @Test
    fun `a series moved to the next weekday with its EXDATE rewritten cancels nothing`() {
        val before = event(rrule = "FREQ=WEEKLY", exdate = "${start + 7 * day},${start + 14 * day}")
        val after = before.copy(
            startTs = start + day, endTs = start + day + hour,
            exdate = "${start + 8 * day},${start + 15 * day}"
        )

        assertEquals(emptyList<Long>(), ChangedField.cancelledInstances(before, after))
        assertEquals(setOf(ChangedField.TIME), ChangedField.between(before, after))
    }

    @Test
    fun `a moved series that also gains an EXDATE lists only the new one`() {
        val before = event(rrule = "FREQ=WEEKLY", exdate = "${start + 7 * day}")
        val after = before.copy(
            startTs = start + hour, endTs = start + 2 * hour,
            exdate = "${start + 7 * day + hour},${start + 21 * day + hour}"
        )

        assertEquals(listOf(start + 21 * day + hour), ChangedField.cancelledInstances(before, after))
    }

    @Test
    fun `a second cancellation on an already-cancelled day of an unmoved series is listed`() {
        val nine = start + 7 * day
        val three = nine + 6 * hour
        val before = event(rrule = "FREQ=DAILY;BYHOUR=9,15", exdate = "$nine")
        val after = before.copy(exdate = "$nine,$three")

        assertEquals(listOf(three), ChangedField.cancelledInstances(before, after))
    }

    @Test
    fun `restoring one of two cancellations on a day of an unmoved series is a RECURRENCE change`() {
        val nine = start + 7 * day
        val three = nine + 6 * hour
        val before = event(rrule = "FREQ=DAILY;BYHOUR=9,15", exdate = "$nine,$three")

        assertEquals(setOf(ChangedField.RECURRENCE), ChangedField.between(before, before.copy(exdate = "$nine")))
    }

    @Test
    fun `a timed series switched to all-day with its EXDATE rewritten cancels and restores nothing`() {
        val ny = java.time.ZoneId.of("America/New_York")
        val first = java.time.LocalDateTime.of(2026, 1, 5, 21, 0).atZone(ny).toInstant().toEpochMilli()
        val excluded = java.time.LocalDateTime.of(2026, 7, 6, 21, 0).atZone(ny).toInstant().toEpochMilli()
        val before = event(startTs = first, endTs = first + hour, timezone = "America/New_York",
            rrule = "FREQ=WEEKLY", exdate = "$excluded")
        val utcMidnight = { d: java.time.LocalDate -> d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        val after = before.copy(
            startTs = utcMidnight(java.time.LocalDate.of(2026, 1, 5)),
            endTs = utcMidnight(java.time.LocalDate.of(2026, 1, 6)),
            isAllDay = true,
            exdate = "${utcMidnight(java.time.LocalDate.of(2026, 7, 6))}"
        )

        assertEquals(emptyList<Long>(), ChangedField.cancelledInstances(before, after))
        assertEquals(setOf(ChangedField.TIME), ChangedField.between(before, after))
    }

    @Test
    fun `a series re-zoned at the same first instant with its EXDATE rewritten cancels nothing`() {
        val ny = java.time.ZoneId.of("America/New_York")
        val phoenix = java.time.ZoneId.of("America/Phoenix")
        val first = java.time.LocalDateTime.of(2026, 1, 5, 9, 0).atZone(ny).toInstant().toEpochMilli()
        val oldExcluded = java.time.LocalDateTime.of(2026, 7, 6, 9, 0).atZone(ny).toInstant().toEpochMilli()
        val newExcluded = java.time.LocalDateTime.of(2026, 7, 6, 7, 0).atZone(phoenix).toInstant().toEpochMilli()
        val before = event(startTs = first, endTs = first + hour, timezone = "America/New_York",
            rrule = "FREQ=WEEKLY", exdate = "$oldExcluded")
        val after = before.copy(timezone = "America/Phoenix", exdate = "$newExcluded")

        assertEquals(emptyList<Long>(), ChangedField.cancelledInstances(before, after))
        assertEquals(emptySet<ChangedField>(), ChangedField.between(before, after))
    }
}
