package org.onekash.kashcal.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Category
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks that a fresh install (Room's create callback, not a migration) seeds the same starter
 * tags `MIGRATION_21_22` gives an upgrading user, so a new user doesn't open the tag screen to an
 * empty table. It runs [KashCalDatabase.testCallback], the test copy of the production callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FreshInstallCategorySeedTest {

    private lateinit var database: KashCalDatabase

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(KashCalDatabase.testCallback())
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `fresh install seeds the curated default tags with their colors`() = runTest {
        val rows = database.categoryDao().observeAll().first()

        assertEquals(
            "the starter set matches the shared seed constant",
            Category.DEFAULT_SEEDS.map { it.first }.sorted(),
            rows.map { it.name }.sorted()
        )
        for ((name, color) in Category.DEFAULT_SEEDS) {
            val row = database.categoryDao().getByName(name)
            assertNotNull("$name is seeded on a fresh install", row)
            assertEquals("$name keeps its curated color", color, row!!.color)
        }
    }
}
