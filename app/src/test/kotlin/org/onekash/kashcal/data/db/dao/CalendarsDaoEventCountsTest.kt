package org.onekash.kashcal.data.db.dao

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader

/**
 * The event count of every Room calendar, as the Export calendars sheet shows it: one-offs and
 * series count once each, changed occurrences and rows waiting to be deleted don't, and every
 * kind of calendar is listed, empty ones as 0.
 */
class CalendarsDaoEventCountsTest : BaseDaoTest() {

    private val start = 1_793_000_000_000L

    private suspend fun calendar(provider: AccountProvider, url: String): Long {
        val accountId = database.accountsDao().insert(Account(provider = provider, email = "$url@example.test"))
        return database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = url, displayName = url, color = 0)
        )
    }

    private suspend fun event(calendarId: Long, uid: String, rrule: String? = null, status: SyncStatus = SyncStatus.SYNCED) =
        database.eventsDao().insert(
            Event(
                uid = uid, calendarId = calendarId, title = uid, startTs = start, endTs = start + 3_600_000,
                dtstamp = start, rrule = rrule, syncStatus = status,
            )
        )

    @Test
    fun `counts every calendar's events as the user sees them`() = runBlocking {
        val local = calendar(AccountProvider.LOCAL, "local://default")
        val feed = calendar(AccountProvider.ICS, "ics://feed")
        val contacts = calendar(AccountProvider.CONTACTS, "local://contact_birthdays")
        val caldav = calendar(AccountProvider.CALDAV, "https://dav.example.test/cal/")
        val empty = calendar(AccountProvider.ICLOUD, "https://caldav.icloud.com/123456789/calendars/home/")

        val series = event(local, "series", rrule = "FREQ=WEEKLY;COUNT=3")
        database.eventsDao().insert(
            Event(
                uid = "series", calendarId = local, title = "moved", startTs = start + 7 * 86_400_000L,
                endTs = start + 7 * 86_400_000L + 3_600_000, dtstamp = start, originalEventId = series,
                originalInstanceTime = start + 7 * 86_400_000L,
            )
        )
        event(local, "one-off")
        event(local, "going", status = SyncStatus.PENDING_DELETE)
        event(feed, "feed-1")
        event(contacts, "birthday")
        event(caldav, "caldav-1")
        event(caldav, "caldav-2")

        val counts = EventReader(database).getCalendarEventCounts().first()

        assertEquals(mapOf(local to 2, feed to 1, contacts to 1, caldav to 2, empty to 0), counts)
    }

    @Test
    fun `emits again when an event is written`() = runBlocking {
        val local = calendar(AccountProvider.LOCAL, "local://default")

        EventReader(database).getCalendarEventCounts().test {
            assertEquals(mapOf(local to 0), awaitItem())
            event(local, "new")
            assertEquals(mapOf(local to 1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
