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
import org.onekash.kashcal.data.db.entity.Category
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [EventReader.getRecentCategories] — the tag-suggestion source. The
 * tag metadata table is the source of truth, so suggestions rank purely by
 * recency (most-recently used first) with a stable name-ASC tiebreak, and
 * deletes/renames are reflected immediately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventReaderCategoriesSuggestionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventReader: EventReader

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        eventReader = EventReader(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedTag(name: String, lastUsedAt: Long) {
        database.categoryDao().insertIgnore(Category(name = name, color = null, lastUsedAt = lastUsedAt))
    }

    @Test
    fun `empty table yields no suggestions`() = runTest {
        assertEquals(emptyList<String>(), eventReader.getRecentCategories().first())
    }

    @Test
    fun `ranked by most-recently-used first`() = runTest {
        seedTag("Older", 1_000)
        seedTag("Newer", 5_000)
        assertEquals(listOf("Newer", "Older"), eventReader.getRecentCategories().first())
    }

    @Test
    fun `tags sharing a last-used time return in stable name-ASC order`() = runTest {
        seedTag("Beta", 2_000)
        seedTag("Alpha", 2_000)
        val first = eventReader.getRecentCategories().first()
        assertEquals(listOf("Alpha", "Beta"), first)
        // Repeated queries keep the same deterministic order.
        assertEquals(first, eventReader.getRecentCategories().first())
    }

    @Test
    fun `capped to twenty`() = runTest {
        repeat(30) { i -> seedTag("tag$i", (i + 1) * 1_000L) }
        assertEquals(20, eventReader.getRecentCategories().first().size)
    }

    @Test
    fun `deleted tag drops out immediately`() = runTest {
        seedTag("Keep", 2_000)
        seedTag("Drop", 3_000)
        database.categoryDao().deleteByName("Drop")
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.contains("Keep"))
        assertTrue(!result.contains("Drop"))
    }

    @Test
    fun `renamed tag surfaces under the new name`() = runTest {
        seedTag("Work", 2_000)
        database.categoryDao().renameTag("Work", "Job")
        val result = eventReader.getRecentCategories().first()
        assertTrue(result.contains("Job"))
        assertTrue(!result.contains("Work"))
    }
}
