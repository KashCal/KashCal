package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * Tests for [EventReader.getRecentCategories] — the usage-ranked tag-suggestion
 * source. The rank/dedup/cap logic lives in the reader (the JSON-array column
 * can't be split in SQL), so these assertions target the reader, not the DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventReaderCategoriesSuggestionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventReader: EventReader
    private var calendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        eventReader = EventReader(database)

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "local")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "local://default",
                displayName = "Local",
                color = 0xFF4CAF50.toInt(),
                isVisible = true,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // Anchor test timestamps near "now" so they fall inside the reader's
    // one-year suggestion window; the small offsets passed by tests preserve
    // relative ordering (recency) while staying recent.
    private val recentBase = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000

    private suspend fun insertEvent(
        title: String,
        categories: List<String>?,
        offset: Long,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        rrule: String? = null,
        localModifiedAt: Long? = null,
    ) {
        val startTs = recentBase + offset
        database.eventsDao().insert(
            Event(
                uid = "uid-$title-$offset",
                calendarId = calendarId,
                title = title,
                startTs = startTs,
                endTs = startTs + 3_600_000,
                timezone = "UTC",
                categories = categories,
                syncStatus = syncStatus,
                rrule = rrule,
                localModifiedAt = localModifiedAt,
                dtstamp = startTs,
            )
        )
    }

    @Test
    fun `empty database yields no suggestions`() = runTest {
        assertEquals(emptyList<String>(), eventReader.getRecentCategories().first())
    }

    @Test
    fun `distinct categories are returned`() = runTest {
        insertEvent("A", listOf("Work"), 1_000)
        insertEvent("B", listOf("Personal"), 2_000)
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.containsAll(listOf("Work", "Personal")))
    }

    @Test
    fun `ranked by frequency descending`() = runTest {
        insertEvent("A", listOf("Work"), 1_000)
        insertEvent("B", listOf("Work"), 2_000)
        insertEvent("C", listOf("Personal"), 3_000)
        val result = eventReader.getRecentCategories().first()
        // Work appears twice, Personal once -> Work ranks first.
        assertEquals("Work", result.first())
    }

    @Test
    fun `case-insensitive dedup counts as one, first-seen casing preserved`() = runTest {
        insertEvent("A", listOf("Work"), 1_000)
        insertEvent("B", listOf("work"), 2_000)
        val result = eventReader.getRecentCategories().first()
        assertEquals(1, result.count { it.equals("work", ignoreCase = true) })
        assertEquals("Work", result.first { it.equals("work", ignoreCase = true) })
    }

    @Test
    fun `recency breaks frequency ties`() = runTest {
        insertEvent("A", listOf("Older"), 1_000)
        insertEvent("B", listOf("Newer"), 5_000)
        val result = eventReader.getRecentCategories().first()
        // Same frequency (1 each) -> more recent first.
        assertEquals("Newer", result.first())
    }

    @Test
    fun `capped to twenty`() = runTest {
        repeat(30) { i -> insertEvent("E$i", listOf("tag$i"), (i + 1) * 1_000L) }
        val result = eventReader.getRecentCategories().first()
        assertEquals(20, result.size)
    }

    @Test
    fun `pending-delete events are excluded`() = runTest {
        insertEvent("A", listOf("Ghost"), 1_000, syncStatus = SyncStatus.PENDING_DELETE)
        insertEvent("B", listOf("Real"), 2_000)
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.contains("Real"))
        assertTrue(!result.contains("Ghost"))
    }

    @Test
    fun `old one-off events fall outside the window`() = runTest {
        // ~2 years ago, non-recurring -> excluded.
        val twoYearsAgo = -(2L * 365 * 24 * 60 * 60 * 1000) - (7L * 24 * 60 * 60 * 1000)
        insertEvent("Old", listOf("Stale"), twoYearsAgo)
        insertEvent("New", listOf("Fresh"), 1_000)
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.contains("Fresh"))
        assertTrue(!result.contains("Stale"))
    }

    @Test
    fun `old recurring masters stay in the window`() = runTest {
        // ~2 years ago but recurring -> included regardless of age.
        val twoYearsAgo = -(2L * 365 * 24 * 60 * 60 * 1000) - (7L * 24 * 60 * 60 * 1000)
        insertEvent("Standup", listOf("Recurring"), twoYearsAgo, rrule = "FREQ=WEEKLY")
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.contains("Recurring"))
    }

    @Test
    fun `old event retro-tagged recently stays in the window`() = runTest {
        // start_ts ~2 years ago, but local_modified_at is now (user just tagged
        // it) -> the window keys on last-used time, so it should still surface.
        val twoYearsAgo = -(2L * 365 * 24 * 60 * 60 * 1000) - (7L * 24 * 60 * 60 * 1000)
        insertEvent(
            "Old",
            listOf("JustTagged"),
            twoYearsAgo,
            localModifiedAt = System.currentTimeMillis(),
        )
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.contains("JustTagged"))
    }
}
