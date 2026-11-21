package org.onekash.kashcal.sync.parser.icaldav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.EventExport
import org.onekash.kashcal.domain.mapper.toExportEvent
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins what [IcsPatcher], [IcsExporter] and device-event export write for events that hold no
 * imported file's copy: synced rows (patched from the server's copy), locally created rows and
 * device events. Imported files change how some events are serialized; these outputs must not
 * move.
 *
 * Expected files live in `app/src/test/resources/ics/golden/patcher/`. Lines that carry the
 * current time (DTSTAMP, CREATED, LAST-MODIFIED) and an alarm's random UID are masked, and each
 * VTIMEZONE block is reduced to its TZID so a tzdata update doesn't change the files. Set
 * KASHCAL_GOLDEN_RECORD=1 to rewrite them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsPatcherOutputGoldenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val serverSeries = ics(
        "BEGIN:VTIMEZONE", "TZID:America/New_York",
        "BEGIN:STANDARD", "DTSTART:19701101T020000", "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU",
        "TZOFFSETFROM:-0400", "TZOFFSETTO:-0500", "TZNAME:EST", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:19700308T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
        "TZOFFSETFROM:-0500", "TZOFFSETTO:-0400", "TZNAME:EDT", "END:DAYLIGHT",
        "END:VTIMEZONE",
        "BEGIN:VEVENT", "UID:golden-series@example.test", "DTSTAMP:20260901T120000Z",
        "DTSTART;TZID=America/New_York:20261102T090000", "DTEND;TZID=America/New_York:20261102T100000",
        "RRULE:FREQ=WEEKLY;COUNT=6", "SUMMARY:Weekly sync", "SEQUENCE:2",
        "ORGANIZER;CN=Org:mailto:org@example.test",
        "ATTENDEE;CN=Guest;PARTSTAT=ACCEPTED:mailto:guest@example.test",
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Room, 1\":geo:1.0,2.0",
        "COMMENT:series\\nnote",
        "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT15M", "END:VALARM",
        "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Ends", "TRIGGER;RELATED=END:PT0M", "END:VALARM",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:golden-series@example.test", "DTSTAMP:20260901T120000Z",
        "RECURRENCE-ID;TZID=America/New_York:20261109T090000",
        "DTSTART;TZID=America/New_York:20261109T110000", "DTEND;TZID=America/New_York:20261109T120000",
        "SUMMARY:Weekly sync (moved)", "SEQUENCE:2",
        "ORGANIZER;CN=Org:mailto:org@example.test",
        "X-MICROSOFT-LOCATIONS:[{\"DisplayName\":\"Room 2\\, Floor 3\"}]",
        "END:VEVENT",
    )

    @Test
    fun `synced single event, export and push`() {
        val body = ics(
            "BEGIN:VEVENT", "UID:golden-single@example.test", "DTSTAMP:20260901T120000Z",
            "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:Visitor tour",
            "LOCATION:Apple Park", "ORGANIZER:mailto:org@example.test",
            "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:guest@example.test",
            "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY:mail\\,msg-1234\\;part\\=2",
            "COMMENT:line one\\nline two\\, with comma",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT30M", "END:VALARM",
            "END:VEVENT",
        )
        val row = synced(body).single().copy(title = "Visitor tour (edited)")
        check("synced-single-export", IcsPatcher.serialize(row, body))
        check("synced-single-push", IcsPatcher.serialize(row, body, listOf(attendee("guest@example.test"))))
    }

    @Test
    fun `synced series with changed occurrences, one the stored copy lacks`() {
        val rows = synced(serverSeries)
        val series = rows.single { it.originalEventId == null }.copy(id = 1)
        val stored = rows.single { it.originalEventId != null }.copy(id = 2, originalEventId = 1)
        val added = series.projectOntoOccurrence(stored.originalInstanceTime!! + WEEK).copy(
            id = 3, originalEventId = 1, originalInstanceTime = stored.originalInstanceTime + WEEK,
            title = "Weekly sync (local change)",
        )
        check("synced-series-export", IcsPatcher.serializeWithExceptions(series, serverSeries, listOf(stored, added)))
        check(
            "synced-series-push",
            IcsPatcher.serializeWithExceptions(
                series, serverSeries, listOf(attendee("guest@example.test")),
                listOf(stored to null, added to listOf(attendee("other@example.test"))),
                ownIcs = emptyMap(),
            ),
        )
    }

    // Export writes each row's attendee rows. For a synced series they are the guests the pull
    // stored from the server's copy, so only the ATTENDEE lines may differ from the copy's own.
    @Test
    fun `synced series exported with its attendee rows`() {
        val mapped = syncedWithAttendees(serverSeries)
        val series = mapped.single { it.first.originalInstanceTime == null }
        val stored = mapped.single { it.first.originalInstanceTime != null }
        val export = EventExport(
            series.first.copy(id = 1), listOf(stored.first.copy(id = 2, originalEventId = 1)),
            mapOf(1L to series.second, 2L to stored.second),
            icsByEventId = mapOf(1L to serverSeries),
        )
        File(context.cacheDir, "shared").deleteRecursively()
        // The file is written before the FileProvider lookup, which has no provider here.
        IcsExporter().exportEvent(context, export)
        val written = File(context.cacheDir, "shared").listFiles()!!.single().readText()
        check("synced-series-export-rows", written)

        val withoutRows = IcsPatcher.serializeWithExceptions(export.event, serverSeries, export.exceptions)
        assertEquals(
            "only ATTENDEE lines differ from the stored copy's export",
            normalize(withoutRows).lines().filterNot { it.startsWith("ATTENDEE") },
            normalize(written).lines().filterNot { it.startsWith("ATTENDEE") },
        )
    }

    @Test
    fun `stored copy listing the changed occurrence first`() {
        val blocks = serverSeries.split("BEGIN:VEVENT")
        // header+timezone, series, occurrence -> header+timezone, occurrence, series
        val reordered = blocks[0] + "BEGIN:VEVENT" + blocks[2].substringBefore("END:VCALENDAR") +
            "BEGIN:VEVENT" + blocks[1] + "END:VCALENDAR\r\n"
        val rows = synced(reordered)
        val series = rows.single { it.originalEventId == null }.copy(id = 1)
        val occurrence = rows.single { it.originalEventId != null }.copy(id = 2, originalEventId = 1)
        check("occurrence-first-export", IcsPatcher.serializeWithExceptions(series, reordered, listOf(occurrence)))
        check("occurrence-first-series-alone", IcsPatcher.serialize(series, reordered))
    }

    @Test
    fun `organizer push with attendee rows`() {
        val series = synced(serverSeries).single { it.originalEventId == null }
            .copy(organizerEmail = "org@example.test")
        check(
            "organizer-push",
            IcsPatcher.serialize(series, serverSeries, listOf(attendee("guest@example.test"), attendee("new@example.test"))),
        )
        check("organizer-push-cleared", IcsPatcher.serialize(series, serverSeries, emptyList()))
    }

    @Test
    fun `synced changed occurrence exported on its own`() {
        val occurrence = synced(serverSeries).single { it.originalEventId != null }
        // Exported alone it reads its resource's stored copy, held by its series row.
        check("synced-occurrence-alone", IcsPatcher.serialize(occurrence, serverSeries))
    }

    @Test
    fun `local event without a stored copy`() {
        check("local-single", IcsPatcher.serialize(local("Dentist", "local-single@kashcal"), null))
    }

    @Test
    fun `device single event and device series`() {
        val single = device(id = 10, title = "Device lunch", rrule = null).toExportEvent(listOf(10)).fixed()
        check("device-single", org.onekash.kashcal.util.IcsExporter().serializeDeviceEvent(single, emptyList()))
        val series = device(id = 20, title = "Device standup", rrule = "FREQ=DAILY;COUNT=5")
            .toExportEvent(listOf(5, 60)).fixed()
        val exception = device(
            id = 21, title = "Device standup (late)", rrule = null,
            originalId = 20, originalInstanceTime = T0 + DAY, start = T0 + DAY + HOUR,
        ).toExportEvent().fixed()
        check("device-series", org.onekash.kashcal.util.IcsExporter().serializeDeviceEvent(series, listOf(exception)))
    }

    @Test
    fun `whole calendar export of local rows across two zones`() {
        val first = local("Morning run", "local-run@kashcal").copy(
            id = 1, timezone = "America/New_York", rrule = "FREQ=DAILY;COUNT=3",
            extraProperties = mapOf("X-KASHCAL-NOTE" to "plain"),
        )
        val firstException = first.projectOntoOccurrence(first.startTs + DAY).copy(
            id = 2, originalEventId = 1, originalInstanceTime = first.startTs + DAY, title = "Long run",
        )
        val second = local("Call Tokyo", "local-call@kashcal").copy(id = 3, timezone = "Asia/Tokyo")
        File(context.cacheDir, "shared").deleteRecursively()
        // The file is written before the FileProvider lookup, which has no provider under
        // Robolectric, so the result is ignored and the file read directly.
        IcsExporter().exportCalendar(
            context,
            listOf(EventExport(first, listOf(firstException), emptyMap()), EventExport(second, emptyList(), emptyMap())),
            "Golden calendar",
        )
        val written = File(context.cacheDir, "shared").listFiles()!!.single().readText()
        check("whole-calendar-local", written)
    }

    // ---- fixtures ----

    /** Rows as the pull maps them; the resource's stored ICS is the body itself. */
    private fun synced(body: String): List<Event> {
        val events = ICalParser().parseAllEvents(body).getOrNull()!!
        val masterStart = events.firstOrNull { it.recurrenceId == null }?.dtStart
        return events.map {
            ICalEventMapper.toEntity(
                icalEvent = it, rawIcal = body, calendarId = 1,
                caldavUrl = "https://dav.example.test/cal/${it.uid}.ics", etag = "\"e1\"",
                masterDtStart = if (it.recurrenceId != null) masterStart else null,
            ).event.fixed().let { row ->
                // The pull links a changed occurrence to its series row after inserting both.
                if (it.recurrenceId != null) row.copy(originalEventId = 1) else row.copy(id = 1)
            }
        }
    }

    /** [synced], with the attendee rows the pull stores for each row. */
    private fun syncedWithAttendees(body: String): List<Pair<Event, List<Attendee>>> {
        val events = ICalParser().parseAllEvents(body).getOrNull()!!
        val masterStart = events.firstOrNull { it.recurrenceId == null }?.dtStart
        return events.map {
            val mapped = ICalEventMapper.toEntity(
                icalEvent = it, rawIcal = body, calendarId = 1,
                caldavUrl = "https://dav.example.test/cal/${it.uid}.ics", etag = "\"e1\"",
                masterDtStart = if (it.recurrenceId != null) masterStart else null,
            )
            mapped.event.fixed() to mapped.attendees
        }
    }

    private fun local(title: String, uid: String) = Event(
        uid = uid, calendarId = 1, title = title, startTs = T0, endTs = T0 + HOUR,
        timezone = "UTC", reminders = listOf("-PT10M"), alarmCount = 1, dtstamp = T0,
        createdAt = T0, updatedAt = T0,
    )

    private fun device(
        id: Long, title: String, rrule: String?, originalId: Long? = null,
        originalInstanceTime: Long? = null, start: Long = T0,
    ) = DeviceEvent(
        id = id, calendarId = 5, title = title, description = "From the phone", location = null,
        startTs = start, endTs = if (rrule == null) start + HOUR else null,
        duration = if (rrule == null) null else "PT1H", isAllDay = false, rrule = rrule,
        rdate = null, exdate = null, exrule = null, timezone = "Europe/Berlin",
        originalId = originalId, originalInstanceTime = originalInstanceTime, status = 1,
        availability = 0, accessLevel = 0, calendarColor = null, eventColor = null,
    )

    private fun attendee(address: String) = Attendee(
        eventId = 0, address = "mailto:$address", partstat = "NEEDS-ACTION", role = "REQ-PARTICIPANT",
    )

    private fun Event.fixed() = copy(dtstamp = T0, createdAt = T0, updatedAt = T0)

    private fun ics(vararg lines: String) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Golden//EN") + lines + "END:VCALENDAR"
    ).joinToString("\r\n", postfix = "\r\n")

    // ---- comparison ----

    private fun check(name: String, output: String) {
        val actual = normalize(output)
        val file = File(GOLDEN_DIR, "$name.ics")
        if (System.getenv("KASHCAL_GOLDEN_RECORD") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("No golden file ${file.path}; record with KASHCAL_GOLDEN_RECORD=1")
        assertEquals("$name output changed", file.readText(), actual)
    }

    /** Unfolds, masks the current-time lines and reduces each VTIMEZONE to its TZID. */
    private fun normalize(ics: String): String {
        val lines = ics.replace(Regex("\r?\n[ \t]"), "").replace("\r\n", "\n").lines()
        val out = mutableListOf<String>()
        var inZone = false
        var inAlarm = false
        for (line in lines) {
            when {
                line == "BEGIN:VTIMEZONE" -> inZone = true
                inZone && line.startsWith("TZID:") -> out += "VTIMEZONE ${line.removePrefix("TZID:")}"
                line == "END:VTIMEZONE" -> inZone = false
                inZone -> Unit
                TIME_LINE.containsMatchIn(line) -> out += line.substringBefore(':') + ":<masked>"
                // A new alarm gets a random UID on every serialization.
                inAlarm && ALARM_UID_LINE.containsMatchIn(line) -> out += line.substringBefore(':') + ":<masked>"
                else -> {
                    if (line == "BEGIN:VALARM") inAlarm = true
                    if (line == "END:VALARM") inAlarm = false
                    out += line
                }
            }
        }
        return out.joinToString("\n")
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
        const val T0 = 1_793_000_000_000L // 2026-10-26T07:33:20Z
        val GOLDEN_DIR = File(org.onekash.kashcal.testutil.resolveProjectRoot(), "app/src/test/resources/ics/golden/patcher")
        val TIME_LINE = Regex("^(DTSTAMP|CREATED|LAST-MODIFIED)[;:]")
        val ALARM_UID_LINE = Regex("^(UID|X-WR-ALARMUID)[;:]")
    }
}
