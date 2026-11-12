package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates [KashCalDatabase] migrations the way Room does after an upgrade.
 *
 * [MigrationTest] checks what each migration does through SQL and PRAGMA. This class runs
 * `MigrationTestHelper.runMigrationsAndValidate`, which compares every table of the migrated
 * database with the exported schema JSON: columns with affinity, nullability and defaults,
 * foreign keys and indexes. A mismatch the PRAGMA checks miss makes the app's first open after the
 * upgrade throw `IllegalStateException`.
 *
 * Schemas live at `$projectDir/schemas` (the `room.schemaLocation` argument in
 * `app/build.gradle.kts`). The tests run under Robolectric, so they are part of
 * `./gradlew testDebugUnitTest` with no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationHashValidationTest {

    private val schemasPath = "${System.getProperty("user.dir")}/schemas"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KashCalDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Before
    fun setup() {
        // Mute Log calls produced by the migration body.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /**
     * Checks `MIGRATION_16_17` on a fresh v16 database against `17.json`. A failure means the
     * migration's SQL differs from the export (defaults, a foreign key clause or an index, for
     * example), which would crash the app's first launch after the upgrade.
     */
    @Test
    fun `MIGRATION_16_17 produces a schema whose identityHash matches Room's v17 export`() {
        // MigrationTestHelper builds the v16 database from the CREATE statements in 16.json
        helper.createDatabase(TEST_DB, 16).close()

        // Reopen at v17 and run the migration through Room's validator. validateDroppedTables =
        // true also fails on a table the v17 schema doesn't have.
        helper.runMigrationsAndValidate(
            TEST_DB,
            17,
            true,
            Migrations.MIGRATION_16_17
        ).close()
    }

    /**
     * Checks `MIGRATION_18_19` on a fresh v18 database against `19.json`: the two new nullable
     * columns `accounts.schedule_outbox_url` and `calendars.auto_schedule_supported` must match in
     * name, affinity and nullability.
     */
    @Test
    fun `MIGRATION_18_19 produces a schema whose identityHash matches Room's v19 export`() {
        helper.createDatabase(TEST_DB, 18).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            19,
            true,
            Migrations.MIGRATION_18_19
        ).close()
    }

    /**
     * Checks `MIGRATION_19_20` on a fresh v19 database against `20.json`: the two new nullable
     * `attendees` columns `itip_request_sequence` and `itip_request_status` must match in name,
     * affinity and nullability.
     */
    @Test
    fun `MIGRATION_19_20 produces a schema whose identityHash matches Room's v20 export`() {
        helper.createDatabase(TEST_DB, 19).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            20,
            true,
            Migrations.MIGRATION_19_20
        ).close()
    }

    /**
     * Checks `MIGRATION_20_21` on a fresh v20 database against `21.json`: the hand-written
     * `CREATE TABLE pending_cancels` must match in column names, affinities, nullability and
     * foreign keys.
     */
    @Test
    fun `MIGRATION_20_21 produces a schema whose identityHash matches Room's v21 export`() {
        helper.createDatabase(TEST_DB, 20).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            21,
            true,
            Migrations.MIGRATION_20_21
        ).close()
    }

    /**
     * Checks `MIGRATION_21_22` on a fresh v21 database against `22.json`: the hand-written
     * `CREATE TABLE categories` and its `last_used_at` index must match in column names,
     * affinities, nullability, primary key and indexes. Room's check reads `PRAGMA table_info`,
     * which has no collation, so it doesn't see the `COLLATE NOCASE` on the primary key.
     */
    @Test
    fun `MIGRATION_21_22 produces a schema whose identityHash matches Room's v22 export`() {
        helper.createDatabase(TEST_DB, 21).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            22,
            true,
            Migrations.MIGRATION_21_22
        ).close()
    }

    /**
     * Checks `MIGRATION_22_23` on a fresh v22 database against `23.json`: the hand-written
     * `CREATE TABLE address_books`, its `account_id` index and the unique `(account_id, url)` index
     * must match in column names, affinities, nullability, foreign keys and indexes.
     */
    @Test
    fun `MIGRATION_22_23 produces a schema whose identityHash matches Room's v23 export`() {
        helper.createDatabase(TEST_DB, 22).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            23,
            true,
            Migrations.MIGRATION_22_23
        ).close()
    }

    /**
     * Checks `MIGRATION_23_24` on a fresh v23 database against `24.json`: the added
     * `accounts.contact_sync_enabled` column (`INTEGER NOT NULL DEFAULT 0`) must match in name,
     * affinity, nullability and default.
     */
    @Test
    fun `MIGRATION_23_24 produces a schema whose identityHash matches Room's v24 export`() {
        helper.createDatabase(TEST_DB, 23).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            24,
            true,
            Migrations.MIGRATION_23_24
        ).close()
    }

    /**
     * Checks `MIGRATION_24_25` on a fresh v24 database against `25.json`: the `recent_changes`
     * table with its foreign keys and indexes, and `calendars.initial_pull_done`.
     */
    @Test
    fun `MIGRATION_24_25 produces a schema whose identityHash matches Room's v25 export`() {
        helper.createDatabase(TEST_DB, 24).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            25,
            true,
            Migrations.MIGRATION_24_25
        ).close()
    }

    /**
     * Checks `MIGRATION_25_26` on a fresh v25 database against `26.json`: the two import columns
     * on `events`.
     */
    @Test
    fun `MIGRATION_25_26 produces a schema whose identityHash matches Room's v26 export`() {
        helper.createDatabase(TEST_DB, 25).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            26,
            true,
            Migrations.MIGRATION_25_26
        ).close()
    }

    /**
     * Checks `MIGRATION_26_27` on a fresh v26 database against `27.json`: the schema is unchanged
     * (the migration adds a trigger, which Room's validation ignores) and the identity hash
     * matches.
     */
    @Test
    fun `MIGRATION_26_27 produces a schema whose identityHash matches Room's v27 export`() {
        helper.createDatabase(TEST_DB, 26).close()

        helper.runMigrationsAndValidate(TEST_DB, 27, true, Migrations.MIGRATION_26_27).close()
    }

    /**
     * Checks `MIGRATION_27_28` on a fresh v27 database against `28.json`: the search index and its
     * triggers are gone, which validating dropped tables checks, and the identity hash matches.
     */
    @Test
    fun `MIGRATION_27_28 produces a schema whose identityHash matches Room's v28 export`() {
        helper.createDatabase(TEST_DB, 27).close()

        helper.runMigrationsAndValidate(TEST_DB, 28, true, Migrations.MIGRATION_27_28).use { db ->
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%events_fts%' OR sql LIKE '%events_fts%'").use {
                it.moveToFirst()
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    /**
     * Upgrades a seeded v26 file the way the app does: Room opens it with every migration. The
     * orphans go, the live operations and the self-contained DELETE stay, and the trigger works.
     */
    @Test
    fun `opening a seeded v26 database with Room upgrades it and keeps live operations`() {
        helper.createDatabase(TEST_DB, 26).apply {
            execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'ICLOUD', 'a@example.test', 0)")
            execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
            execSQL(
                "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at) " +
                    "VALUES (1, 'live', 1, 'Live', 100, 200, 'UTC', 0, 0, 0)"
            )
            execSQL("INSERT INTO pending_operations (id, event_id, operation, created_at, updated_at) VALUES (10, 1, 'UPDATE', 0, 0)")
            execSQL(
                "INSERT INTO pending_operations (id, event_id, operation, status, created_at, updated_at) " +
                    "VALUES (11, 99, 'UPDATE', 'ABANDONED', 0, 0)"
            )
            execSQL(
                "INSERT INTO pending_operations (id, event_id, operation, target_url, source_calendar_id, created_at, updated_at) " +
                    "VALUES (12, 98, 'DELETE', 'https://x/old.ics', 1, 0, 0)"
            )
            close()
        }

        val room = androidx.room.Room.databaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            KashCalDatabase::class.java,
            TEST_DB
        ).addMigrations(*Migrations.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            kotlinx.coroutines.runBlocking {
                assertEquals(listOf(10L, 12L), room.pendingOperationsDao().getAll().map { it.id }.sorted())
                room.eventsDao().deleteById(1)
                assertEquals(listOf(12L), room.pendingOperationsDao().getAll().map { it.id })
            }
            assertTrue(PendingOperationTriggers.exists(room.openHelper.readableDatabase))
        } finally {
            room.close()
        }
    }

    /**
     * Runs the whole chain from a v1 database and validates the result against `28.json`. This
     * catches drift in an early migration that only shows once later versions build on it.
     */
    @Test
    fun `full migration chain v1 to v28 produces schema whose identityHash matches Room's export`() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            28,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            // 3 -> 4 is an AutoMigration; the helper takes it from the database class when no
            // explicit migration is given.
            Migrations.MIGRATION_4_5,
            Migrations.MIGRATION_5_6,
            Migrations.MIGRATION_6_7,
            Migrations.MIGRATION_7_8,
            Migrations.MIGRATION_8_9,
            Migrations.MIGRATION_9_10,
            Migrations.MIGRATION_10_11,
            Migrations.MIGRATION_11_12,
            Migrations.MIGRATION_12_13,
            Migrations.MIGRATION_13_14,
            Migrations.MIGRATION_14_15,
            Migrations.MIGRATION_15_16,
            Migrations.MIGRATION_16_17,
            Migrations.MIGRATION_17_18,
            Migrations.MIGRATION_18_19,
            Migrations.MIGRATION_19_20,
            Migrations.MIGRATION_20_21,
            Migrations.MIGRATION_21_22,
            Migrations.MIGRATION_22_23,
            Migrations.MIGRATION_23_24,
            Migrations.MIGRATION_24_25,
            Migrations.MIGRATION_25_26,
            Migrations.MIGRATION_26_27,
            Migrations.MIGRATION_27_28
        ).close()
    }

    private companion object {
        const val TEST_DB = "migration-hash-validation-test"
    }
}
