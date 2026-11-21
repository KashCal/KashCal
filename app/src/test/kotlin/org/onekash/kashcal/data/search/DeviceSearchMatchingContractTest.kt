package org.onekash.kashcal.data.search

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.EXTNAME_CATEGORIES
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.calendar_provider.encodeCategories
import org.onekash.kashcal.domain.search.SearchQuery
import java.util.TimeZone

/**
 * Runs the matching contract against the platform's Instances search, modelled by
 * [SqliteCalendarProvider] and read through the real [AndroidCalendarProviderRepository]. The
 * query is [SearchQuery.toProviderQuery], the text the app sends.
 */
class DeviceSearchMatchingContractTest : SearchMatchingContractTest() {

    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repo: AndroidCalendarProviderRepository
    private lateinit var savedZone: TimeZone

    @Before
    fun setup() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        provider = SqliteCalendarProvider.install()
        repo = SqliteCalendarProvider.repository(ApplicationProvider.getApplicationContext())
        provider.seedCalendar(CAL)
    }

    @After
    fun tearDown() {
        provider.db.close()
        TimeZone.setDefault(savedZone)
    }

    override fun seed(title: String, location: String?, notes: String?, guests: List<Guest>, tags: List<String>) {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to title, Events.DTSTART to T0, Events.DTEND to T0 + 3_600_000,
            Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0, Events.STATUS to Events.STATUS_CONFIRMED,
            Events.DESCRIPTION to notes, Events.EVENT_LOCATION to location,
        )
        guests.forEach {
            provider.insertRow(
                SqliteCalendarProvider.ATTENDEES,
                Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to it.email, Attendees.ATTENDEE_NAME to it.name,
            )
        }
        // Tags as a sync adapter or the app's own write stores them.
        encodeCategories(tags)?.let { value ->
            provider.insertRow(
                SqliteCalendarProvider.EXTENDED_PROPERTIES,
                ExtendedProperties.EVENT_ID to id, ExtendedProperties.NAME to EXTNAME_CATEGORIES, ExtendedProperties.VALUE to value,
            )
        }
    }

    override fun search(text: String): List<String> = runBlocking {
        val query = SearchQuery.of(text)
        repo.searchTaggedInstances(query.tags, query.toProviderQuery(), DAY, DAY, setOf(CAL)).map { it.title }.sorted()
    }

    @Test
    fun `a tag-only search with no tagged event reads no instances`() {
        seed("Checkup", tags = listOf("Doctor"))
        seed("Untagged")
        provider.queryFailures[SqliteCalendarProvider.INSTANCES] = SqliteCalendarProvider.QueryFailure.THROW

        assertEquals(emptyList<String>(), search("#dentist"))
    }

    private fun tag(eventId: Long, value: String) {
        provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES,
            ExtendedProperties.EVENT_ID to eventId, ExtendedProperties.NAME to EXTNAME_CATEGORIES, ExtendedProperties.VALUE to value,
        )
    }

    @Test
    fun `a changed occurrence is matched by its own tags, not its series'`() {
        val master = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Standup", Events.DTSTART to T0, Events.DURATION to "PT1H",
            Events.RRULE to "FREQ=DAILY;COUNT=3", Events.EVENT_TIMEZONE to "UTC", Events._SYNC_ID to "series-1",
        )
        val moved = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Standup moved", Events.DTSTART to T0 + DAY_MS + 3_600_000,
            Events.DTEND to T0 + DAY_MS + 7_200_000, Events.EVENT_TIMEZONE to "UTC",
            Events.ORIGINAL_ID to master, Events.ORIGINAL_SYNC_ID to "series-1", Events.ORIGINAL_INSTANCE_TIME to T0 + DAY_MS,
        )
        tag(moved, "Focus")

        val found = runBlocking { repo.searchTaggedInstances(listOf("focus"), "", DAY, DAY + 2, setOf(CAL)) }

        assertEquals(listOf("Standup moved"), found.map { it.title })
    }

    @Test
    fun `the returned occurrences carry their tags and the declined ones can be left out`() {
        seed("Checkup", tags = listOf("Doctor", "Family"))
        val declined = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Declined clinic", Events.DTSTART to T0, Events.DTEND to T0 + 3_600_000,
            Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
        )
        tag(declined, "Doctor")
        provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to declined, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_DECLINED,
        )

        val all = runBlocking { repo.searchTaggedInstances(listOf("doctor"), "", DAY, DAY, setOf(CAL)) }
        val kept = runBlocking { repo.searchTaggedInstances(listOf("doctor"), "", DAY, DAY, setOf(CAL), hideDeclined = true) }

        assertEquals(listOf("Doctor", "Family"), all.single { it.title == "Checkup" }.categories)
        assertEquals(listOf("Checkup", "Declined clinic"), all.map { it.title }.sorted())
        assertEquals(listOf("Checkup"), kept.map { it.title })
    }

    @Test
    fun `a revoked calendar permission returns no occurrences`() {
        val resolver: android.content.ContentResolver = io.mockk.mockk {
            io.mockk.every { query(any(), any(), any(), any(), any()) } throws SecurityException("revoked")
        }
        val revoked = AndroidCalendarProviderRepository(resolver)

        val found = runBlocking { revoked.searchTaggedInstances(listOf("doctor"), "", DAY, DAY, setOf(CAL)) }

        assertEquals(emptyList<String>(), found.map { it.title })
    }

    @Test
    fun `a tag search leaves out calendars that don't sync events and deleted events`() {
        seed("Kept", tags = listOf("Doctor"))
        val deleted = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Deleted", Events.DTSTART to T0, Events.DTEND to T0 + 3_600_000,
            Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0, Events.DELETED to 1,
        )
        provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES,
            ExtendedProperties.EVENT_ID to deleted, ExtendedProperties.NAME to EXTNAME_CATEGORIES, ExtendedProperties.VALUE to "Doctor",
        )
        provider.seedCalendar(OFF, syncEvents = false)
        val off = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to OFF, Events.TITLE to "Not synced", Events.DTSTART to T0, Events.DTEND to T0 + 3_600_000,
            Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
        )
        provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES,
            ExtendedProperties.EVENT_ID to off, ExtendedProperties.NAME to EXTNAME_CATEGORIES, ExtendedProperties.VALUE to "Doctor",
        )

        val found = runBlocking { repo.searchTaggedInstances(listOf("doctor"), "", DAY, DAY, setOf(CAL, OFF)) }

        assertEquals(listOf("Kept"), found.map { it.title })
    }

    private companion object {
        const val CAL = 7L
        const val OFF = 8L
        const val DAY_MS = 86_400_000L
        const val OWNER = "me@example.test"
        const val T0 = 1_709_650_800_000L // 2024-03-05T15:00:00Z
        const val DAY = 20240305
    }
}
