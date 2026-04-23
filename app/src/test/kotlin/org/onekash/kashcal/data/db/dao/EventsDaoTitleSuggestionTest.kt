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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [EventsDao.suggestTitlesByPrefix].
 *
 * Covers prefix matching, case/whitespace normalization, recency filter,
 * min-frequency filter, exception exclusion, pending-delete exclusion, and
 * deterministic ordering.
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
        insertEvents("Coffee with Alex", count = 3, createdAtBase = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertEquals(1, results.size)
        assertEquals("Coffee with Alex", results[0].title)
        assertEquals(3, results[0].freq)
        assertTrue(results[0].lastUsed >= now - dayMs)
    }

    @Test
    fun `returns empty when no prior events`() = runTest {
        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `returns empty when prefix does not match any title`() = runTest {
        insertEvents("Dentist", count = 5, createdAtBase = now - dayMs)
        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `matches case-insensitively`() = runTest {
        insertEvents("Coffee", count = 3, createdAtBase = now - dayMs)
        val lower = eventsDao.suggestTitlesByPrefix("cof", sinceMs = now - window90d, minFreq = 2, limit = 5)
        val upper = eventsDao.suggestTitlesByPrefix("COF", sinceMs = now - window90d, minFreq = 2, limit = 5)
        assertEquals(1, lower.size)
        assertEquals(1, upper.size)
    }

    // ==================== Deduplication ====================

    @Test
    fun `merges case variants into single suggestion`() = runTest {
        insertEvent("coffee", createdAt = now - 3 * dayMs)
        insertEvent("Coffee", createdAt = now - 2 * dayMs)
        insertEvent("COFFEE", createdAt = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertEquals(1, results.size)
        assertEquals(3, results[0].freq)
    }

    @Test
    fun `trims whitespace before matching`() = runTest {
        insertEvent("  Coffee  ", createdAt = now - dayMs)
        insertEvent("Coffee", createdAt = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertEquals(1, results.size)
        assertEquals("Coffee", results[0].title)
        assertEquals(2, results[0].freq)
    }

    // ==================== Quality filters ====================

    @Test
    fun `excludes titles below min frequency`() = runTest {
        insertEvents("Rare Event", count = 1, createdAtBase = now - dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Rar", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes titles older than sinceMs cutoff`() = runTest {
        // 5 events, all older than 90 days
        insertEvents("Old Event", count = 5, createdAtBase = now - 200 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Old", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes exception events from frequency count`() = runTest {
        // Master counted
        insertEvent("Weekly Standup", createdAt = now - 3 * dayMs)
        // 20 exception overrides — must be excluded
        repeat(20) {
            insertEvent("Weekly Standup", createdAt = now - 2 * dayMs, originalEventId = 1L)
        }

        val results = eventsDao.suggestTitlesByPrefix("Wee", sinceMs = now - window90d, minFreq = 1, limit = 5)

        assertEquals(1, results.size)
        assertEquals(1, results[0].freq) // Master only, exceptions excluded
    }

    @Test
    fun `excludes pending-delete events`() = runTest {
        insertEvent("Old Task", createdAt = now - dayMs, syncStatus = SyncStatus.PENDING_DELETE)
        insertEvent("Old Task", createdAt = now - dayMs, syncStatus = SyncStatus.PENDING_DELETE)

        val results = eventsDao.suggestTitlesByPrefix("Old", sinceMs = now - window90d, minFreq = 1, limit = 5)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `excludes blank titles`() = runTest {
        // Blank titles are legal per schema (used for some orphaned sync records).
        insertEvent("", createdAt = now - dayMs)
        insertEvent("   ", createdAt = now - dayMs)

        val resultsEmpty = eventsDao.suggestTitlesByPrefix("", sinceMs = now - window90d, minFreq = 1, limit = 5)
        // An empty prefix matches everything, but blank titles are filtered out.
        assertTrue(resultsEmpty.all { it.title.isNotBlank() })
    }

    // ==================== Ordering ====================

    @Test
    fun `ranks higher frequency first`() = runTest {
        insertEvents("Coffee with Alex", count = 10, createdAtBase = now - 10 * dayMs)
        insertEvents("Coffee 1-1", count = 3, createdAtBase = now - 5 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertEquals(2, results.size)
        assertEquals("Coffee with Alex", results[0].title)
        assertEquals("Coffee 1-1", results[1].title)
    }

    @Test
    fun `breaks frequency tie with most recent last-used`() = runTest {
        insertEvents("Coffee A", count = 3, createdAtBase = now - 30 * dayMs)
        insertEvents("Coffee B", count = 3, createdAtBase = now - 5 * dayMs)

        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertEquals(2, results.size)
        assertEquals("Coffee B", results[0].title) // more recent
        assertEquals("Coffee A", results[1].title)
    }

    @Test
    fun `enforces limit`() = runTest {
        for (i in 1..10) {
            insertEvents("Coffee $i", count = 2, createdAtBase = now - i * dayMs)
        }

        val results = eventsDao.suggestTitlesByPrefix("Cof", sinceMs = now - window90d, minFreq = 2, limit = 5)

        assertEquals(5, results.size)
    }

    // ==================== Helpers ====================

    private suspend fun insertEvents(title: String, count: Int, createdAtBase: Long) {
        repeat(count) { i ->
            insertEvent(title, createdAt = createdAtBase + i * 1000L)
        }
    }

    private suspend fun insertEvent(
        title: String,
        createdAt: Long,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        originalEventId: Long? = null
    ) {
        val event = Event(
            uid = "uid-${System.nanoTime()}-${title.hashCode()}",
            calendarId = calendarId,
            title = title,
            startTs = createdAt,
            endTs = createdAt + 3_600_000,
            dtstamp = createdAt,
            createdAt = createdAt,
            syncStatus = syncStatus,
            originalEventId = originalEventId
        )
        eventsDao.insert(event)
    }
}
