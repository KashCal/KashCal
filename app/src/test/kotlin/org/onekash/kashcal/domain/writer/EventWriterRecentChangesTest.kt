package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.RecentChange
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the Recent changes dismiss and restore writes on [EventWriter] over a real Room
 * database: dismissing only the given rows (so Clear all can't take a row a sync added after the
 * sheet showed), and restoring them for Clear all's undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterRecentChangesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var writer: EventWriter
    private var calendarId = 0L

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        val generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        writer = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "self@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://x/a/", displayName = "Work", color = 7)
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insert(uid: String) = database.recentChangesDao().insert(
        RecentChange(
            calendarId = calendarId, eventUid = uid, instanceTs = 0, eventId = null, changeType = org.onekash.kashcal.sync.model.ChangeType.NEW,
            title = uid, startTs = 0, endTs = 0, isAllDay = false, isRecurring = false, detectedAt = 1_000
        )
    )

    private suspend fun visibleUids() =
        database.recentChangesDao().observeVisible(0).first().map { it.change.eventUid }.toSet()

    @Test
    fun `dismissing given rows leaves a row added after them`() = runTest {
        val shown = listOf(insert("a"), insert("b"))
        insert("arrived-later")

        writer.dismissRecentChanges(shown, at = 2_000)

        assertEquals(setOf("arrived-later"), visibleUids())
    }

    @Test
    fun `restore brings back exactly the dismissed rows`() = runTest {
        val shown = listOf(insert("a"), insert("b"))
        writer.dismissRecentChanges(shown, at = 2_000)

        writer.restoreRecentChanges(shown)

        assertEquals(setOf("a", "b"), visibleUids())
    }

    @Test
    fun `many rows dismiss and restore in one go`() = runTest {
        val shown = (1..1_200).map { insert("u$it") }

        writer.dismissRecentChanges(shown, at = 2_000)
        assertEquals(emptySet<String>(), visibleUids())

        writer.restoreRecentChanges(shown)
        assertEquals(1_200, visibleUids().size)
    }
}
