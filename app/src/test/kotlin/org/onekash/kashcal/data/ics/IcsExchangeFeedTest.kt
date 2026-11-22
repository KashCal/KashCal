package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.util.resolveWindowsZone
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Subscribes to feeds shaped like a published Microsoft 365 calendar (#441), through the real
 * [OkHttpIcsFetcher] and [IcsSubscriptionRepository] over Room, with a MockWebServer as the feed.
 *
 * The feeds use Windows zone names with Exchange's VTIMEZONE (observances starting 1601-01-01),
 * `X-MICROSOFT-CDO-*` properties, a weekly series with an EXDATE and a moved occurrence, and an
 * all-day event. The Windows zone resolver is installed as `KashCalApplication.onCreate` does.
 * A feed that lists a moved occurrence twice, as a real published one did, keeps every event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsExchangeFeedTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var db: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: IcsSubscriptionRepository

    private val chicago = ZoneId.of("America/Chicago")
    private val local = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)

    private val oneOffStart: ZonedDateTime =
        LocalDate.now(chicago).plusDays(3).atTime(LocalTime.of(9, 0)).atZone(chicago)
    private val seriesStart: ZonedDateTime = LocalDate.now(chicago).minusWeeks(4)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY))
        .atTime(LocalTime.of(14, 0)).atZone(chicago)
    private val allDay: LocalDate = LocalDate.now(chicago).plusDays(10)

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        ICalDateTime.customTimezoneResolver = ::resolveWindowsZone
        val context: Context = ApplicationProvider.getApplicationContext()
        // Production's triggers, so a feed the database refuses fails here as on a phone.
        fixture = ReminderAlarmFixture(context, withProductionCallback = true)
        db = fixture.database
        val icsAccountId = runBlocking {
            db.accountsDao().insert(
                Account(provider = AccountProvider.ICS, email = IcsSubscription.ACCOUNT_EMAIL)
            )
        }
        val accountRepository = mockk<AccountRepository>()
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.ICS, IcsSubscription.ACCOUNT_EMAIL)
        } coAnswers { db.accountsDao().getById(icsAccountId) }
        repository = IcsSubscriptionRepository(
            database = db,
            icsSubscriptionsDao = db.icsSubscriptionsDao(),
            accountRepository = accountRepository,
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            occurrenceGenerator = OccurrenceGenerator(
                db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()
            ),
            icsFetcher = OkHttpIcsFetcher(),
            reminderScheduler = fixture.scheduler,
            eventReader = fixture.eventReader,
            context = context,
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        ICalDateTime.customTimezoneResolver = null
        server.shutdown()
        fixture.close()
    }

    private fun vtimezone(tzid: String) = """
        BEGIN:VTIMEZONE
        TZID:$tzid
        BEGIN:STANDARD
        DTSTART:16010101T020000
        TZOFFSETFROM:-0500
        TZOFFSETTO:-0600
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:16010101T020000
        TZOFFSETFROM:-0600
        TZOFFSETTO:-0500
        RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3
        END:DAYLIGHT
        END:VTIMEZONE
    """.trimIndent()

    private fun cdo(busy: String = "BUSY", allDay: Boolean = false) = """
        CLASS:PUBLIC
        PRIORITY:5
        DTSTAMP:20261118T120000Z
        TRANSP:OPAQUE
        STATUS:CONFIRMED
        SEQUENCE:0
        X-MICROSOFT-CDO-APPT-SEQUENCE:0
        X-MICROSOFT-CDO-BUSYSTATUS:$busy
        X-MICROSOFT-CDO-INTENDEDSTATUS:$busy
        X-MICROSOFT-CDO-ALLDAYEVENT:${if (allDay) "TRUE" else "FALSE"}
        X-MICROSOFT-CDO-IMPORTANCE:1
        X-MICROSOFT-CDO-INSTTYPE:0
        X-MICROSOFT-DONOTFORWARDMEETING:FALSE
        X-MICROSOFT-DISALLOW-COUNTER:FALSE
    """.trimIndent()

    private fun feed(tzid: String): String {
        val series = seriesStart.plusWeeks(2)
        val moved = seriesStart.plusWeeks(3)
        val uidSeries = "040000008200E00074C5B7101A82E0080000000010B2C3D4E5F6D901000000000000000" +
            "010000000A1B2C3D4E5F60718293A4B5C6D7E8F90"
        val body = """
            BEGIN:VCALENDAR
            METHOD:PUBLISH
            PRODID:Microsoft Exchange Server 2010
            VERSION:2.0
            X-WR-CALNAME:Calendar
            ${vtimezone(tzid)}
            BEGIN:VEVENT
            DESCRIPTION:\n
            UID:040000008200E00074C5B7101A82E00800000000A0B1C2D3E4F5D9010000000000000000100000001
            SUMMARY:Work review
            DTSTART;TZID=$tzid:${local.format(oneOffStart)}
            DTEND;TZID=$tzid:${local.format(oneOffStart.plusHours(1))}
            LOCATION:Room 4
            ${cdo()}
            END:VEVENT
            BEGIN:VEVENT
            DESCRIPTION:\n
            RRULE:FREQ=WEEKLY;UNTIL=${utc.format(seriesStart.plusWeeks(20))};INTERVAL=1;BYDAY=TH;WKST=SU
            EXDATE;TZID=$tzid:${local.format(series)}
            UID:$uidSeries
            SUMMARY:Team sync
            DTSTART;TZID=$tzid:${local.format(seriesStart)}
            DTEND;TZID=$tzid:${local.format(seriesStart.plusMinutes(30))}
            LOCATION:
            ${cdo()}
            END:VEVENT
            BEGIN:VEVENT
            DESCRIPTION:\n
            UID:$uidSeries
            RECURRENCE-ID;TZID=$tzid:${local.format(moved)}
            SUMMARY:Team sync
            DTSTART;TZID=$tzid:${local.format(moved.plusHours(1))}
            DTEND;TZID=$tzid:${local.format(moved.plusHours(1).plusMinutes(30))}
            LOCATION:
            ${cdo()}
            END:VEVENT
            BEGIN:VEVENT
            DESCRIPTION:\n
            UID:040000008200E00074C5B7101A82E00800000000C0D1E2F3A4B5D9010000000000000000100000003
            SUMMARY:Out of office
            DTSTART;VALUE=DATE:${day.format(allDay)}
            DTEND;VALUE=DATE:${day.format(allDay.plusDays(1))}
            ${cdo(busy = "OOF", allDay = true)}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        // Each interpolated block starts flush left; strip the template's leading spaces line
        // by line.
        return body.lines().joinToString("\r\n") { it.trimStart() } + "\r\n"
    }

    private suspend fun storedEvents(sub: IcsSubscription): List<Event> =
        db.eventsDao().getByCalendarIdAndCaldavUrlPrefix(sub.calendarId, IcsSubscription.eventSourcePrefix(sub.id))

    private fun subscribeAndCheck(tzid: String) = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(feed(tzid)).setHeader("ETag", "\"v1\""))

        val added = repository.addSubscription(server.url("/calendar.ics").toString(), "M365", 0xFF3366CC.toInt())
        assertTrue("subscribing failed: $added", added is IcsSubscriptionRepository.SubscriptionResult.Success)
        val sub = (added as IcsSubscriptionRepository.SubscriptionResult.Success).subscription
        val row = db.icsSubscriptionsDao().getById(sub.id)!!
        assertNull("no error on the row", row.lastError)

        val events = storedEvents(sub)
        assertEquals(
            "three events and one moved occurrence stored: ${events.map { it.title to it.uid.take(12) }}",
            4, events.size
        )
        val occurrences = db.occurrencesDao().getForEvents(events.map { it.id })
        val masters = events.filter { it.originalEventId == null }
        masters.forEach { master ->
            assertTrue("${master.title} has occurrences", occurrences.any { it.eventId == master.id })
        }

        val review = events.single { it.title == "Work review" }
        assertEquals("one-off at 09:00 Chicago", oneOffStart.toInstant().toEpochMilli(), review.startTs)

        val oof = events.single { it.title == "Out of office" }
        assertTrue("all-day event", oof.isAllDay)

        val seriesOccurrences = occurrences.filter { it.eventId == masters.single { it.title == "Team sync" }.id }
        assertTrue(
            "the EXDATE occurrence is not shown",
            seriesOccurrences.none {
                !it.isCancelled && it.startTs == seriesStart.plusWeeks(2).toInstant().toEpochMilli()
            }
        )
    }

    @Test
    fun `published Microsoft 365 feed with Windows zone names stores its events`() =
        subscribeAndCheck("Central Standard Time")

    @Test
    fun `a changed occurrence listed twice does not wipe the rest of the feed`() = runBlocking {
        val tz = "Eastern Standard Time"
        val start = seriesStart.withZoneSameLocal(ZoneId.of("America/New_York"))
        val moved = start.plusWeeks(1)
        val exception = """
            BEGIN:VEVENT
            UID:series-1
            RECURRENCE-ID;TZID=$tz:${local.format(moved)}
            SUMMARY:Moved sync
            DTSTART;TZID=$tz:${local.format(moved.plusHours(1))}
            DTEND;TZID=$tz:${local.format(moved.plusHours(2))}
            DTSTAMP:20261118T120000Z
            END:VEVENT
        """.trimIndent()
        val body = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:Microsoft Exchange Server 2010
            METHOD:PUBLISH
            BEGIN:VEVENT
            UID:one-off-1
            SUMMARY:Work review
            DTSTART;TZID=$tz:${local.format(oneOffStart)}
            DTEND;TZID=$tz:${local.format(oneOffStart.plusHours(1))}
            DTSTAMP:20261118T120000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:series-1
            SUMMARY:Team sync
            RRULE:FREQ=WEEKLY;COUNT=10
            DTSTART;TZID=$tz:${local.format(start)}
            DTEND;TZID=$tz:${local.format(start.plusMinutes(30))}
            DTSTAMP:20261118T120000Z
            END:VEVENT
            $exception
            $exception
            END:VCALENDAR
        """.trimIndent().lines().joinToString("\r\n") { it.trimStart() } + "\r\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val added = repository.addSubscription(server.url("/calendar.ics").toString(), "M365", 0xFF3366CC.toInt())
        val sub = (added as IcsSubscriptionRepository.SubscriptionResult.Success).subscription

        assertEquals(
            "one-off, series and its changed occurrence stored",
            setOf("Work review", "Team sync", "Moved sync"),
            storedEvents(sub).map { it.title }.toSet()
        )
    }

    /** Serves [body] (lines joined with CRLF, leading spaces dropped) and subscribes to it. */
    private suspend fun subscribe(body: String): IcsSubscription {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"")
                .setBody(body.lines().joinToString("\r\n") { it.trimStart() } + "\r\n")
        )
        val added = repository.addSubscription(server.url("/calendar.ics").toString(), "M365", 0xFF3366CC.toInt())
        assertTrue("subscribing failed: $added", added is IcsSubscriptionRepository.SubscriptionResult.Success)
        return (added as IcsSubscriptionRepository.SubscriptionResult.Success).subscription
    }

    private fun timed(uid: String, title: String, start: ZonedDateTime, extra: String = "") = """
        BEGIN:VEVENT
        UID:$uid
        SUMMARY:$title
        DTSTART;TZID=Eastern Standard Time:${local.format(start)}
        DTEND;TZID=Eastern Standard Time:${local.format(start.plusHours(1))}
        DTSTAMP:20261118T120000Z
        $extra
        END:VEVENT
    """.trimIndent()

    private fun calendar(vararg vevents: String) =
        "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:Microsoft Exchange Server 2010\n" +
            vevents.joinToString("\n") + "\nEND:VCALENDAR"

    @Test
    fun `an event that can't be stored leaves the rest of the feed and is named on the feed`() = runBlocking {
        // Two series rows with one UID and one start: disambiguation names both alike, so the
        // master-uniqueness trigger refuses the second.
        val start = oneOffStart.withZoneSameLocal(ZoneId.of("America/New_York"))
        val sub = subscribe(
            calendar(
                timed("one-off-1", "Work review", start),
                timed("twin", "Planning", start.plusDays(1)),
                timed("twin", "Planning (copy)", start.plusDays(1)),
                timed("one-off-2", "Standup", start.plusDays(2)),
            )
        )

        assertEquals(
            setOf("Work review", "Planning", "Standup"),
            storedEvents(sub).map { it.title }.toSet()
        )
        assertEquals(
            IcsFeedErrors.notSaved(1, listOf("Planning (copy)")),
            db.icsSubscriptionsDao().getById(sub.id)!!.lastError
        )
    }

    @Test
    fun `after a per-event retry every armed reminder belongs to a stored event`() = runBlocking {
        val soon = ZonedDateTime.now(ZoneId.of("America/New_York")).plusMinutes(30).withSecond(0).withNano(0)
        val alarm = "BEGIN:VALARM\nACTION:DISPLAY\nDESCRIPTION:Soon\nTRIGGER:-PT15M\nEND:VALARM"
        val sub = subscribe(
            calendar(
                timed("one-off-1", "Work review", soon, alarm),
                timed("twin", "Planning", soon.plusMinutes(5), alarm),
                timed("twin", "Planning (copy)", soon.plusMinutes(5), alarm),
            )
        )

        val stored = storedEvents(sub).map { it.id }.toSet()
        val armed = fixture.armedReminders().keys
        assertTrue("a reminder is armed", armed.isNotEmpty())
        for (reminderId in armed) {
            val row = fixture.reminderRow(reminderId)
            assertTrue("armed reminder $reminderId has its row", row != null)
            assertTrue("armed reminder $reminderId belongs to a stored event", row!!.eventId in stored)
        }
        assertEquals("one alarm per reminder row", fixture.reminderRowCount(), fixture.reminderAlarms().size)
    }

    private val centralRules = listOf(
        "BEGIN:STANDARD", "DTSTART:16010101T020000", "TZOFFSETFROM:-0500", "TZOFFSETTO:-0600",
        "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=1SU;BYMONTH=11", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:16010101T020000", "TZOFFSETFROM:-0600", "TZOFFSETTO:-0500",
        "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=2SU;BYMONTH=3", "END:DAYLIGHT",
    )

    private fun fixedRules(offset: String) = listOf(
        "BEGIN:STANDARD", "DTSTART:16010101T000000", "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:16010101T000000", "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:DAYLIGHT",
    )

    private fun zoneBlock(tzid: String, rules: List<String>) =
        (listOf("BEGIN:VTIMEZONE", "TZID:$tzid") + rules + "END:VTIMEZONE").joinToString("\n")

    private val usDisplayName = "(UTC-06:00) Central Time (US & Canada)"
    private val beijing = "(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi"

    /** One event per zone form, in January and July, with the UTC offset each should get. */
    private val zoneCases = listOf(
        Triple("Central Standard Time", "Windows ID", -6 to -5),
        Triple("UTC", "UTC", 0 to 0),
        Triple("\"$beijing\"", "Beijing", 8 to 8),
        Triple("\"$usDisplayName\"", "US display name", -6 to -5),
    )

    private fun zoneEvents() = zoneCases.flatMap { (tzid, label, _) ->
        listOf("01", "07").map { month ->
            """
            BEGIN:VEVENT
            UID:zone-$label-$month
            SUMMARY:$label|$month
            DTSTART;TZID=$tzid:2027${month}15T100000
            DTEND;TZID=$tzid:2027${month}15T110000
            DTSTAMP:20261118T120000Z
            END:VEVENT
            """.trimIndent()
        }
    }.toTypedArray()

    private fun assertZoneInstants(events: List<Event>) {
        val offsets = zoneCases.associate { (_, label, offsets) -> label to offsets }
        assertEquals("every zone event stored", zoneCases.size * 2, events.size)
        for (event in events) {
            val (label, month) = event.title.split("|")
            val (winter, summer) = offsets.getValue(label)
            val expected = java.time.LocalDateTime.of(2027, month.toInt(), 15, 10, 0)
                .toInstant(java.time.ZoneOffset.ofHours(if (month == "07") summer else winter)).toEpochMilli()
            assertEquals("${event.title} (${event.timezone})", expected, event.startTs)
        }
    }


    @Test
    fun `Exchange zone forms with their VTIMEZONEs store at the feed's offsets`() = withDeviceTimeZone("Asia/Tokyo") {
        runBlocking {
            val sub = subscribe(
                calendar(
                    zoneBlock("Central Standard Time", centralRules),
                    zoneBlock("UTC", fixedRules("+0000")),
                    zoneBlock(beijing.replace(",", "\\,"), fixedRules("+0800")),
                    zoneBlock(usDisplayName, centralRules),
                    *zoneEvents(),
                )
            )
            assertZoneInstants(storedEvents(sub))
        }
    }

    @Test
    fun `Exchange zone forms without VTIMEZONEs store at their offsets`() = withDeviceTimeZone("Asia/Tokyo") {
        runBlocking {
            // iCloud serves the Beijing name without its VTIMEZONE; the others resolve by name.
            assertZoneInstants(storedEvents(subscribe(calendar(*zoneEvents()))))
        }
    }
}
