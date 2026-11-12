package org.onekash.kashcal.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.EventSearchSql
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.search.SearchQuery

/**
 * Proves on a device that the app's Room search folds case outside ASCII, as the phone's
 * SQLite does for the platform's own calendar search: "врач" finds "Врач" and "ärzte" finds
 * "Ärzte-Termin", while "cafe" does not find "Café".
 */
@RunWith(AndroidJUnit4::class)
class RoomSearchCaseFoldingTest {

    private lateinit var database: KashCalDatabase
    private var calendarId = 0L

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KashCalDatabase::class.java)
            .build()
        runBlocking {
            val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
            calendarId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "local://calendar", displayName = "Test", color = 0)
            )
            listOf("Врач", "Ärzte-Termin", "Café Nero").forEach { title ->
                database.eventsDao().insert(
                    Event(
                        uid = title,
                        calendarId = calendarId,
                        title = title,
                        startTs = T0,
                        endTs = T0 + 3_600_000,
                        dtstamp = T0,
                    )
                )
            }
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun search(text: String): List<String> = runBlocking {
        database.eventsDao()
            .searchMatches(EventSearchSql.nextAndLast(SearchQuery.of(text), T0, TODAY))
            .map { it.event.title }
    }

    @Test
    fun cyrillicCaseIsFolded() {
        assertEquals(listOf("Врач"), search("врач"))
    }

    @Test
    fun germanCaseIsFolded() {
        assertEquals(listOf("Ärzte-Termin"), search("ärzte"))
    }

    @Test
    fun accentsAreNotFolded() {
        assertEquals(emptyList<String>(), search("cafe"))
    }

    private companion object {
        const val T0 = 1_709_650_800_000L // 2024-03-05T15:00:00Z
        const val TODAY = 20240305
    }
}
