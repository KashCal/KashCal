package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.TITLE_SUGGESTION_WINDOW_FUTURE_DAYS
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [EventsDao.suggestTitlesByPrefix].
 *
 * The window is on `start_ts` (the event's time), not `created_at` (the row's insert time):
 * a sync sets created_at to now, so old imported events would otherwise flood suggestions
 * (seen in v23.6.3).
 *
 * A series (non-empty rrule) skips the window: the master's DTSTART is the first occurrence,
 * which can be years old while the series still runs.
 *
 * Results rank by frequency, then by MAX(COALESCE(local_modified_at, start_ts)), so at equal
 * frequency user-edited events rank above untouched sync imports, whose null
 * local_modified_at falls back to start_ts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventsDaoTitleSuggestionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventsDao: EventsDao
    private var calendarId: Long = 0

    private val now = 1_735_689_600_000L // 2025-01-01 UTC, fixed for determinism
    private val dayMs = 86_400_000L
    private val window90d = 90 * dayMs
    private val sinceMs = now - window90d
    private val untilMs = now + TITLE_SUGGESTION_WINDOW_FUTURE_DAYS * dayMs

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        eventsDao = database.eventsDao()

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "t@test")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://t/cal/",
                displayName = "Test",
                color = 0xFF0000FF.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Happy path ====================

    @Test
    fun `matches prefix and returns frequency and last-used`() = runTest {
        insertEvents("Coffee with Alex", count = 3, startTsBase = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals("Coffee with Alex", results[0].title)
        assertEquals(3, results[0].freq)
        assertTrue(results[0].lastUsed >= now - dayMs)
    }

    @Test
    fun `returns empty when no prior events`() = runTest {
        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `returns empty when prefix does not match any title`() = runTest {
        insertEvents("Dentist", count = 5, startTsBase = now - dayMs)
        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `matches case-insensitively`() = runTest {
        insertEvents("Coffee", count = 3, startTsBase = now - dayMs)
        val lower = eventsDao.suggestTitlesByPrefix(
            "cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )
        val upper = eventsDao.suggestTitlesByPrefix(
            "COF", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )
        assertEquals(1, lower.size)
        assertEquals(1, upper.size)
    }

    // ==================== Deduplication ====================

    @Test
    fun `merges case variants into single suggestion`() = runTest {
        insertEvent("coffee", startTs = now - 3 * dayMs)
        insertEvent("Coffee", startTs = now - 2 * dayMs)
        insertEvent("COFFEE", startTs = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals(3, results[0].freq)
    }

    @Test
    fun `trims whitespace before matching`() = runTest {
        insertEvent("  Coffee  ", startTs = now - dayMs)
        insertEvent("Coffee", startTs = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals("Coffee", results[0].title)
        assertEquals(2, results[0].freq)
    }

    // ==================== Quality filters ====================

    @Test
    fun `excludes titles below min frequency`() = runTest {
        insertEvents("Rare Event", count = 1, startTsBase = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Rar", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes non-recurring events with start_ts older than sinceMs`() = runTest {
        insertEvents("Old Event", count = 5, startTsBase = now - 200 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Old", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes non-recurring events with start_ts beyond untilMs`() = runTest {
        // Events 30 days ahead, beyond the 7-day future window.
        insertEvents("Far Future", count = 5, startTsBase = now + 30 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Far", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `includes non-recurring events with start_ts within future window`() = runTest {
        // Events 3 days ahead, within the 7-day future window.
        insertEvents("Upcoming", count = 2, startTsBase = now + 3 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Upc", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals(2, results[0].freq)
    }

    @Test
    fun `import scenario - old start_ts with fresh created_at is excluded`() = runTest {
        // A sync imports events from a year ago: created_at is now (sync time) but start_ts
        // is a year old, so the start_ts window excludes them (v23.6.3).
        val oldStart = now - 365 * dayMs
        insertEvent("Imported Old", startTs = oldStart, createdAt = now)
        insertEvent("Imported Old", startTs = oldStart + 1_000, createdAt = now)

        val results = eventsDao.suggestTitlesByPrefix(
            "Imp", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertTrue("Old-dated imports must not pollute suggestions", results.isEmpty())
    }

    @Test
    fun `includes recurring events even when start_ts is older than sinceMs`() = runTest {
        // A weekly standup from 2 years ago: the master's DTSTART is old but the series
        // still runs, so it's included although start_ts < sinceMs.
        insertEvent("Weekly Standup", startTs = now - 2 * 365 * dayMs, rrule = "FREQ=WEEKLY")

        val results = eventsDao.suggestTitlesByPrefix(
            "Wee", sinceMs = sinceMs, untilMs = untilMs, minFreq = 1, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals("Weekly Standup", results[0].title)
    }

    @Test
    fun `excludes recurring events with empty rrule string`() = runTest {
        // Some providers emit an empty string instead of null. An old start_ts with an empty
        // rrule must not bypass the window.
        insertEvents("Empty RRule", count = 3, startTsBase = now - 200 * dayMs, rrule = "")

        val results = eventsDao.suggestTitlesByPrefix(
            "Emp", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes exception events from frequency count`() = runTest {
        insertEvent("Weekly Standup", startTs = now - 3 * dayMs)
        repeat(20) {
            insertEvent("Weekly Standup", startTs = now - 2 * dayMs, originalEventId = 1L)
        }

        val results = eventsDao.suggestTitlesByPrefix(
            "Wee", sinceMs = sinceMs, untilMs = untilMs, minFreq = 1, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals(1, results[0].freq)
    }

    @Test
    fun `excludes pending-delete events`() = runTest {
        insertEvent("Old Task", startTs = now - dayMs, syncStatus = SyncStatus.PENDING_DELETE)
        insertEvent("Old Task", startTs = now - dayMs, syncStatus = SyncStatus.PENDING_DELETE)

        val results = eventsDao.suggestTitlesByPrefix(
            "Old", sinceMs = sinceMs, untilMs = untilMs, minFreq = 1, limit = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes blank titles`() = runTest {
        insertEvent("", startTs = now - dayMs)
        insertEvent("   ", startTs = now - dayMs)

        val resultsEmpty = eventsDao.suggestTitlesByPrefix(
            "", sinceMs = sinceMs, untilMs = untilMs, minFreq = 1, limit = 5
        )
        assertTrue(resultsEmpty.all { it.title.isNotBlank() })
    }

    // ==================== Ordering ====================

    @Test
    fun `ranks higher frequency first`() = runTest {
        insertEvents("Coffee with Alex", count = 10, startTsBase = now - 10 * dayMs)
        insertEvents("Coffee 1-1", count = 3, startTsBase = now - 5 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(2, results.size)
        assertEquals("Coffee with Alex", results[0].title)
        assertEquals("Coffee 1-1", results[1].title)
    }

    @Test
    fun `breaks frequency tie with most recent last-used`() = runTest {
        insertEvents("Coffee A", count = 3, startTsBase = now - 30 * dayMs)
        insertEvents("Coffee B", count = 3, startTsBase = now - 5 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(2, results.size)
        assertEquals("Coffee B", results[0].title)
        assertEquals("Coffee A", results[1].title)
    }

    @Test
    fun `user-edited event ranks above sync-imported event with same freq`() = runTest {
        // Both titles: freq=2, same start_ts. "Edited" has localModifiedAt yesterday (a user
        // edit); "Imported" has it null (a sync insert). Edited's last_used (yesterday) beats
        // Imported's start_ts fallback (10 days ago).
        val oldStart = now - 10 * dayMs
        insertEvent("Edited", startTs = oldStart, localModifiedAt = now - dayMs)
        insertEvent("Edited", startTs = oldStart + 1_000, localModifiedAt = now - dayMs)
        insertEvent("Imported", startTs = oldStart, localModifiedAt = null)
        insertEvent("Imported", startTs = oldStart + 1_000, localModifiedAt = null)

        val results = eventsDao.suggestTitlesByPrefix(
            "E", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )
        val imp = eventsDao.suggestTitlesByPrefix(
            "I", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(1, results.size)
        assertEquals(1, imp.size)
        assertTrue(
            "Edited lastUsed (${results[0].lastUsed}) should be > Imported lastUsed (${imp[0].lastUsed})",
            results[0].lastUsed > imp[0].lastUsed
        )
    }

    @Test
    fun `enforces limit`() = runTest {
        for (i in 1..10) {
            insertEvents("Coffee $i", count = 2, startTsBase = now - i * dayMs)
        }

        val results = eventsDao.suggestTitlesByPrefix(
            "Cof", sinceMs = sinceMs, untilMs = untilMs, minFreq = 2, limit = 5
        )

        assertEquals(5, results.size)
    }

    // ==================== Helpers ====================

    private suspend fun insertEvents(
        title: String,
        count: Int,
        startTsBase: Long,
        rrule: String? = null
    ) {
        repeat(count) { i ->
            insertEvent(title, startTs = startTsBase + i * 1000L, rrule = rrule)
        }
    }

    private suspend fun insertEvent(
        title: String,
        startTs: Long,
        createdAt: Long = startTs,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        originalEventId: Long? = null,
        rrule: String? = null,
        localModifiedAt: Long? = null
    ) {
        val event = Event(
            uid = "uid-${System.nanoTime()}-${title.hashCode()}",
            calendarId = calendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + 3_600_000,
            dtstamp = createdAt,
            createdAt = createdAt,
            syncStatus = syncStatus,
            originalEventId = originalEventId,
            rrule = rrule,
            localModifiedAt = localModifiedAt
        )
        eventsDao.insert(event)
    }
}
