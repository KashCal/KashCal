package org.onekash.kashcal.data.search

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
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

    override fun seed(title: String, location: String?, notes: String?, guests: List<Guest>) {
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
    }

    override fun search(text: String): List<String> = runBlocking {
        repo.searchInstances(SearchQuery.of(text).toProviderQuery(), DAY, DAY, setOf(CAL)).map { it.title }.sorted()
    }

    private companion object {
        const val CAL = 7L
        const val T0 = 1_709_650_800_000L // 2024-03-05T15:00:00Z
        const val DAY = 20240305
    }
}
