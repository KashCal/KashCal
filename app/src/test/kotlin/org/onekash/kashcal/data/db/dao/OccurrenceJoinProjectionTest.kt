package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import java.time.Instant

/**
 * Tests that the [OccurrencesDao] join queries, which name every event column they read with an
 * `e_` alias, carry the columns a later schema added: a column left out of one query's list reads
 * back null for every occurrence that query returns.
 */
class OccurrenceJoinProjectionTest : BaseDaoTest() {

    private lateinit var occurrencesDao: OccurrencesDao
    private lateinit var eventsDao: EventsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var accountsDao: AccountsDao

    private val now = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()

    @Before
    override fun setup() {
        super.setup()
        occurrencesDao = database.occurrencesDao()
        eventsDao = database.eventsDao()
        calendarsDao = database.calendarsDao()
        accountsDao = database.accountsDao()
    }

    @Test
    fun `JOIN projections preserve endTimezone and organizer scheduling fields`() = runTest {
        val accountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "user@radicale.local")
        )
        val calId = calendarsDao.insert(
            Calendar(accountId = accountId, caldavUrl = "https://test/cal/",
                displayName = "Test", color = 0xFF0000FF.toInt())
        )
        // A flight-style event (issue #39): start and end in different zones, with an
        // organizer that has SENT-BY (RFC 5545 §3.2.18) and SCHEDULE-STATUS (RFC 6638 §7.3).
        val eventId = eventsDao.insert(
            Event(
                uid = "tz-organizer@test.com", calendarId = calId, title = "Flight",
                startTs = now, endTs = now + 3600_000, dtstamp = now,
                timezone = "America/New_York",
                endTimezone = "Europe/London",
                organizerEmail = "boss@example.test",
                organizerSentBy = "mailto:assistant@example.test",
                organizerScheduleStatus = "1.2;Delivered",
                syncStatus = SyncStatus.SYNCED
            )
        )
        occurrencesDao.insertAll(listOf(
            Occurrence(
                eventId = eventId, calendarId = calId,
                startTs = now, endTs = now + 3600_000,
                startDay = 20260401, endDay = 20260401
            )
        ))

        // All three join projections must alias these columns (e_end_timezone,
        // e_organizer_sent_by, e_organizer_schedule_status); a missing alias silently leaves
        // the field null.
        val rangeEvent = occurrencesDao.getOccurrencesWithEventsInRange(
            now - 1000, now + 7200_000
        ).first().single().event
        val dayEvent = occurrencesDao.getOccurrencesWithEventsForDay(20260401)
            .first().single().event
        val insightsEvent = occurrencesDao.getOccurrencesWithEventsForInsights(
            now - 1000, now + 7200_000
        ).single().event

        for (event in listOf(rangeEvent, dayEvent, insightsEvent)) {
            assertEquals("Europe/London", event.endTimezone)
            assertEquals("mailto:assistant@example.test", event.organizerSentBy)
            assertEquals("1.2;Delivered", event.organizerScheduleStatus)
        }
    }
}
