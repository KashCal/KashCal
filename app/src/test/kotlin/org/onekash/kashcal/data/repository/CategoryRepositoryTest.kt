package org.onekash.kashcal.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Category
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CategoryRepository] — the domain-facing wrapper over the tag
 * metadata table. ViewModels, the event writer, and the sync pull path use this
 * seam so they never touch [org.onekash.kashcal.data.db.dao.CategoryDao]
 * directly. Backed by a real in-memory Room DB for fidelity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CategoryRepositoryTest {

    private lateinit var database: KashCalDatabase
    private lateinit var repository: CategoryRepository

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CategoryRepository(database.categoryDao())
    }

    @After
    fun teardown() = database.close()

    @Test
    fun `colorFor returns a stored custom color`() = runTest {
        database.categoryDao().insertIgnore(
            Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L)
        )

        assertEquals(0xFF4457C9.toInt(), repository.colorFor("Work"))
    }

    @Test
    fun `colorFor is null when the row has no custom color`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Gym", color = null, lastUsedAt = 100L))

        assertNull("a row without a color falls through to the hash color", repository.colorFor("Gym"))
    }

    @Test
    fun `colorFor is null when no row exists`() = runTest {
        assertNull(repository.colorFor("Nonexistent"))
    }

    @Test
    fun `colorFor is case-insensitive`() = runTest {
        database.categoryDao().insertIgnore(
            Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L)
        )

        assertEquals(0xFF4457C9.toInt(), repository.colorFor("work"))
    }

    @Test
    fun `setColor creates a row when the tag is absent`() = runTest {
        repository.setColor("Study", 0xFF2E9F63.toInt(), now = 500L)

        assertEquals(0xFF2E9F63.toInt(), repository.colorFor("Study"))
    }

    @Test
    fun `setColor recolors an existing tag without disturbing recency`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = null, lastUsedAt = 100L))

        repository.setColor("Work", 0xFFE04A8E.toInt(), now = 900L)

        val row = database.categoryDao().getByName("Work")
        assertEquals(0xFFE04A8E.toInt(), row!!.color)
        assertEquals("recoloring is not a use — recency unchanged", 100L, row.lastUsedAt)
    }

    @Test
    fun `observeColors emits the table's name-to-color map`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))
        database.categoryDao().insertIgnore(Category(name = "Gym", color = null, lastUsedAt = 200L))

        val colors = repository.observeColors().first()

        assertEquals(0xFF4457C9.toInt(), colors["Work"])
        assertTrue("a colorless tag is present with a null value", colors.containsKey("Gym"))
        assertNull(colors["Gym"])
    }

    @Test
    fun `observeSuggestions ranks by recency then name`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Personal", color = null, lastUsedAt = 300L))
        database.categoryDao().insertIgnore(Category(name = "Beta", color = null, lastUsedAt = 200L))
        database.categoryDao().insertIgnore(Category(name = "Alpha", color = null, lastUsedAt = 200L))

        assertEquals(listOf("Personal", "Alpha", "Beta"), repository.observeSuggestions().first())
    }

    @Test
    fun `delete removes the metadata row`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Temp", color = null, lastUsedAt = 100L))

        repository.delete("Temp")

        assertNull(database.categoryDao().getByName("Temp"))
    }
}
