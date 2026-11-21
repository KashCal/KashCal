package org.onekash.kashcal.data.db.migration

import android.util.Log
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.testutil.TestDatabases
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

    private val dbFile = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB)
        .also { file -> file.parentFile?.mkdirs(); listOf("", "-journal", "-wal", "-shm").forEach { java.io.File(file.path + it).delete() } }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        dbFile,
        AndroidSQLiteDriver(),
        KashCalDatabase::class,
    )

    /**
     * Runs [migrations] to [version] through Room's validator, then checks no table outside the
     * export is left ([ExportedSchemaTables]), which the helper doesn't check.
     */
    private suspend fun migrateAndValidate(version: Int, vararg migrations: Migration): SQLiteConnection =
        helper.runMigrationsAndValidate(version, migrations.toList()).also { connection ->
            try {
                ExportedSchemaTables.assertExactly(connection, version)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }

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
    fun `MIGRATION_16_17 produces a schema whose identityHash matches Room's v17 export`(): Unit = runBlocking {
        // MigrationTestHelper builds the v16 database from the CREATE statements in 16.json
        helper.createDatabase(16).close()

        // Reopen at v17 and run the migration through Room's validator; [migrateAndValidate] also
        // fails on a table the v17 schema doesn't have.
        migrateAndValidate(17, Migrations.MIGRATION_16_17).close()
    }

    /**
     * Checks `MIGRATION_18_19` on a fresh v18 database against `19.json`: the two new nullable
     * columns `accounts.schedule_outbox_url` and `calendars.auto_schedule_supported` must match in
     * name, affinity and nullability.
     */
    @Test
    fun `MIGRATION_18_19 produces a schema whose identityHash matches Room's v19 export`(): Unit = runBlocking {
        helper.createDatabase(18).close()

        migrateAndValidate(19, Migrations.MIGRATION_18_19).close()
    }

    /**
     * Checks `MIGRATION_19_20` on a fresh v19 database against `20.json`: the two new nullable
     * `attendees` columns `itip_request_sequence` and `itip_request_status` must match in name,
     * affinity and nullability.
     */
    @Test
    fun `MIGRATION_19_20 produces a schema whose identityHash matches Room's v20 export`(): Unit = runBlocking {
        helper.createDatabase(19).close()

        migrateAndValidate(20, Migrations.MIGRATION_19_20).close()
    }

    /**
     * Checks `MIGRATION_20_21` on a fresh v20 database against `21.json`: the hand-written
     * `CREATE TABLE pending_cancels` must match in column names, affinities, nullability and
     * foreign keys.
     */
    @Test
    fun `MIGRATION_20_21 produces a schema whose identityHash matches Room's v21 export`(): Unit = runBlocking {
        helper.createDatabase(20).close()

        migrateAndValidate(21, Migrations.MIGRATION_20_21).close()
    }

    /**
     * Checks `MIGRATION_21_22` on a fresh v21 database against `22.json`: the hand-written
     * `CREATE TABLE categories` and its `last_used_at` index must match in column names,
     * affinities, nullability, primary key and indexes. Room's check reads `PRAGMA table_info`,
     * which has no collation, so it doesn't see the `COLLATE NOCASE` on the primary key.
     */
    @Test
    fun `MIGRATION_21_22 produces a schema whose identityHash matches Room's v22 export`(): Unit = runBlocking {
        helper.createDatabase(21).close()

        migrateAndValidate(22, Migrations.MIGRATION_21_22).close()
    }

    /**
     * Checks `MIGRATION_22_23` on a fresh v22 database against `23.json`: the hand-written
     * `CREATE TABLE address_books`, its `account_id` index and the unique `(account_id, url)` index
     * must match in column names, affinities, nullability, foreign keys and indexes.
     */
    @Test
    fun `MIGRATION_22_23 produces a schema whose identityHash matches Room's v23 export`(): Unit = runBlocking {
        helper.createDatabase(22).close()

        migrateAndValidate(23, Migrations.MIGRATION_22_23).close()
    }

    /**
     * Checks `MIGRATION_23_24` on a fresh v23 database against `24.json`: the added
     * `accounts.contact_sync_enabled` column (`INTEGER NOT NULL DEFAULT 0`) must match in name,
     * affinity, nullability and default.
     */
    @Test
    fun `MIGRATION_23_24 produces a schema whose identityHash matches Room's v24 export`(): Unit = runBlocking {
        helper.createDatabase(23).close()

        migrateAndValidate(24, Migrations.MIGRATION_23_24).close()
    }

    /**
     * Checks `MIGRATION_24_25` on a fresh v24 database against `25.json`: the `recent_changes`
     * table with its foreign keys and indexes, and `calendars.initial_pull_done`.
     */
    @Test
    fun `MIGRATION_24_25 produces a schema whose identityHash matches Room's v25 export`(): Unit = runBlocking {
        helper.createDatabase(24).close()

        migrateAndValidate(25, Migrations.MIGRATION_24_25).close()
    }

    /**
     * Checks `MIGRATION_25_26` on a fresh v25 database against `26.json`: the two import columns
     * on `events`.
     */
    @Test
    fun `MIGRATION_25_26 produces a schema whose identityHash matches Room's v26 export`(): Unit = runBlocking {
        helper.createDatabase(25).close()

        migrateAndValidate(26, Migrations.MIGRATION_25_26).close()
    }

    /**
     * Checks `MIGRATION_26_27` on a fresh v26 database against `27.json`: the schema is unchanged
     * (the migration adds a trigger, which Room's validation ignores) and the identity hash
     * matches.
     */
    @Test
    fun `MIGRATION_26_27 produces a schema whose identityHash matches Room's v27 export`(): Unit = runBlocking {
        helper.createDatabase(26).close()

        migrateAndValidate(27, Migrations.MIGRATION_26_27).close()
    }

    /**
     * Checks `MIGRATION_27_28` on a fresh v27 database against `28.json`: the search index is gone
     * ([ExportedSchemaTables] and the `sqlite_master` count below) and the identity hash matches.
     */
    @Test
    fun `MIGRATION_27_28 produces a schema whose identityHash matches Room's v28 export`(): Unit = runBlocking {
        helper.createDatabase(27).close()

        migrateAndValidate(28, Migrations.MIGRATION_27_28).use { connection ->
            connection.prepare(
                "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%events_fts%' OR sql LIKE '%events_fts%'"
            ).use {
                it.step()
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    /** Checks `MIGRATION_28_29` on a fresh v28 database against `29.json`. */
    @Test
    fun `MIGRATION_28_29 produces a schema whose identityHash matches Room's v29 export`(): Unit = runBlocking {
        helper.createDatabase(28).close()

        migrateAndValidate(29, Migrations.MIGRATION_28_29).close()
    }

    /**
     * Upgrades a seeded v26 file the way the app does: Room opens it with every migration. The
     * orphans go, the live operations and the self-contained DELETE stay, and the trigger works.
     */
    @Test
    fun `opening a seeded v26 database with Room upgrades it and keeps live operations`(): Unit = runBlocking {
        helper.createDatabase(26).apply {
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

        val room = TestDatabases.file(androidx.test.core.app.ApplicationProvider.getApplicationContext(), TEST_DB).addMigrations(*Migrations.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals(listOf(10L, 12L), room.pendingOperationsDao().getAll().map { it.id }.sorted())
                room.eventsDao().deleteById(1)
                assertEquals(listOf(12L), room.pendingOperationsDao().getAll().map { it.id })
            }
            assertTrue(TestDatabases.triggerExists(room, PendingOperationTriggers.EVENT_DELETE_TRIGGER))
        } finally {
            room.close()
        }
    }

    /**
     * Runs the whole chain from a v1 database and validates the result against `29.json`. This
     * catches drift in an early migration that only shows once later versions build on it.
     */
    @Test
    fun `full migration chain v1 to v29 produces schema whose identityHash matches Room's export`(): Unit = runBlocking {
        helper.createDatabase(1).close()

        migrateAndValidate(
            29,
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
            Migrations.MIGRATION_27_28,
            Migrations.MIGRATION_28_29
        ).close()
    }

    @Test
    fun `a migration that leaves a table the export lacks fails validation`(): Unit = runBlocking {
        helper.createDatabase(27).close()
        val leavesTable = object : Migration(27, 28) {
            override suspend fun migrate(connection: SQLiteConnection) {
                Migrations.MIGRATION_27_28.migrate(connection)
                connection.execSQL("CREATE TABLE left_behind (x INTEGER)")
            }
        }
        val error = runCatching { migrateAndValidate(28, leavesTable).close() }.exceptionOrNull()
        assertTrue("expected the left-behind table to fail, got $error", error?.message.orEmpty().contains("left_behind"))
    }

    private companion object {
        const val TEST_DB = "migration-hash-validation-test"
    }
}
