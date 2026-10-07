package org.onekash.kashcal.domain.reader

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
import org.onekash.kashcal.domain.changes.RecentChangesRecorder
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [EventReader.observeRecentChanges] over a real Room database: the 7-day window, dismissed
 * rows hidden, newest first, calendar name and color joined, and the row mapped to its domain
 * form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventReaderRecentChangesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var reader: EventReader
    private var calendarId = 0L
    private val now = 1_790_000_000_000L

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        reader = EventReader(database)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "self@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://x/a/", displayName = "Work", color = 7)
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insert(uid: String, detectedAt: Long, dismissedAt: Long? = null) =
        database.recentChangesDao().insert(
            RecentChange(
                calendarId = calendarId, eventUid = uid, instanceTs = 0, eventId = null,
                changeType = ChangeType.MODIFIED, title = uid, startTs = 1_000, endTs = 2_000, isAllDay = false,
                isRecurring = true, detectedAt = detectedAt,
                changedFields = setOf(ChangedField.TIME),
                previousStartTs = 500, previousIsAllDay = false, dismissedAt = dismissedAt
            )
        )

    @Test
    fun `shows rows of the last seven days, newest first, without dismissed ones`() = runTest {
        insert("expired", now - RecentChangesRecorder.RETENTION_MS - 1)
        insert("older", now - 2_000)
        insert("newer", now - 1_000)
        insert("dismissed", now - 500, dismissedAt = now)

        val items = reader.observeRecentChanges(now).first()

        assertEquals(listOf("newer", "older"), items.map { it.eventUid })
    }

    @Test
    fun `maps the row with its calendar`() = runTest {
        val id = insert("u", now)

        val item = reader.observeRecentChanges(now).first().single()

        assertEquals(id, item.id)
        assertEquals(ChangeType.MODIFIED, item.changeType)
        assertEquals(setOf(ChangedField.TIME), item.changedFields)
        assertEquals(500L, item.previousStartTs)
        assertEquals(false, item.previousIsAllDay)
        assertEquals("Work", item.calendarName)
        assertEquals(7, item.calendarColor)
        assertEquals(true, item.isRecurring)
        assertEquals(now, item.detectedAt)
    }
}
