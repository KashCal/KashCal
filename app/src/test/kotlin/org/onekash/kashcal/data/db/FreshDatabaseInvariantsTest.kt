package org.onekash.kashcal.data.db

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Category
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what a database built with the create callback holds besides Room's tables: the two
 * triggers that keep one master per UID in a calendar, the pending-operations trigger and the
 * starter tags. Room doesn't validate triggers or rows, so a create callback that stops running
 * would leave them out and nothing else would fail. It builds with [TestDatabases.production],
 * the configuration `DatabaseModule` opens the app's database with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FreshDatabaseInvariantsTest {

    private lateinit var database: KashCalDatabase

    @Before
    fun setup() {
        database = TestDatabases.production(ApplicationProvider.getApplicationContext())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun names(sql: String): Set<String> = TestDatabases.query(database, sql) { it.getText(0) }.toSet()

    @Test
    fun `a fresh database has the master-uid and pending-operations triggers`() {
        val triggers = names("SELECT name FROM sqlite_master WHERE type = 'trigger'")
        assertTrue(
            "triggers: $triggers",
            triggers.containsAll(
                listOf(
                    "trigger_master_event_unique_insert",
                    "trigger_master_event_unique_update",
                    PendingOperationTriggers.EVENT_DELETE_TRIGGER,
                )
            )
        )
    }

    @Test
    fun `a fresh database has every starter tag`() {
        val tags = names("SELECT name FROM categories")
        assertEquals(Category.DEFAULT_SEEDS.map { it.first }.toSet(), tags)
    }
}
