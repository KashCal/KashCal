package org.onekash.kashcal.data.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventSearchSql
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.search.SearchQuery
import java.util.UUID

/**
 * Runs the matching contract against the Room search ([EventSearchSql] through
 * [org.onekash.kashcal.data.db.dao.EventsDao.searchMatches]). Every app calendar kind (iCloud,
 * CalDAV, ICS, local, birthdays) is searched by this one query, so one calendar covers them.
 * Guests are stored with `mailto:`, as the pull stores email-shaped addresses.
 */
class RoomSearchMatchingContractTest : SearchMatchingContractTest() {

    private lateinit var database: KashCalDatabase
    private var calendarId = 0L

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
            calendarId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "local://calendar", displayName = "Test", color = 0)
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    override fun seed(title: String, location: String?, notes: String?, guests: List<Guest>) {
        seedWithAddresses(title, location, notes, guests.map { it.name to "mailto:${it.email}" })
    }

    private fun seedWithAddresses(
        title: String,
        location: String? = null,
        notes: String? = null,
        guests: List<Pair<String?, String>> = emptyList(),
    ): Unit = runBlocking {
        val id = database.eventsDao().insert(
            Event(
                uid = UUID.randomUUID().toString(),
                calendarId = calendarId,
                title = title,
                location = location,
                description = notes,
                startTs = T0,
                endTs = T0 + 3_600_000,
                dtstamp = T0,
            )
        )
        database.attendeesDao().insertAll(guests.map { (name, address) -> Attendee(eventId = id, address = address, displayName = name) })
    }

    override fun search(text: String): List<String> = runBlocking {
        database.eventsDao()
            .searchMatches(EventSearchSql.nextAndLast(SearchQuery.of(text), T0, TODAY))
            .map { it.event.title }
            .sorted()
    }

    @Test
    fun `a mailto prefix is not searched`() {
        seedWithAddresses("Checkup", guests = listOf(null to "mailto:amara@example.test"))

        assertEquals(emptyList<String>(), search("mailto"))
        assertEquals(emptyList<String>(), search("mail"))
        assertEquals(listOf("Checkup"), search("amara@"))
        assertEquals(listOf("Checkup"), search("AMARA@"))
    }

    @Test
    fun `an address that is not an email is never searched`() {
        seedWithAddresses("By id", guests = listOf(null to "urn:uuid:4d3c-amara"))
        seedWithAddresses("By path", guests = listOf(null to "/123456789/principal/"))

        assertEquals(emptyList<String>(), search("amara"))
        assertEquals(emptyList<String>(), search("principal"))
        assertEquals(emptyList<String>(), search("uuid"))
    }

    @Test
    fun `a bare email address is searched`() {
        seedWithAddresses("Bare", guests = listOf(null to "amara@example.test"))

        assertEquals(listOf("Bare"), search("amara@example"))
    }

    private companion object {
        const val T0 = 1_709_650_800_000L // 2024-03-05T15:00:00Z
        const val TODAY = 20240305
    }
}
