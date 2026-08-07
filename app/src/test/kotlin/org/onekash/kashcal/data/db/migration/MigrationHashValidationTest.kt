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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hash-validation tests for `KashCalDatabase` migrations.
 *
 * Where [MigrationTest] verifies what each migration does at the SQL/PRAGMA
 * level, this class verifies what Room actually checks at runtime: the
 * `identityHash` of the database after migration must match the hash Room
 * computes from its compile-time entity model. A mismatch makes Room throw
 * `IllegalStateException` the first time the app opens the DB — not at
 * migration time, and not in any of the targeted PRAGMA tests.
 *
 * `MigrationTestHelper.runMigrationsAndValidate` is what catches that.
 *
 * Conventions:
 * - Schemas live at `$projectDir/schemas` (per `app/build.gradle.kts:61`).
 * - We run inside Robolectric so the test is part of the unit-test gate
 *   (`./gradlew testDebugUnitTest`) — no emulator, no instrumented run.
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
     * Validates that the post-migration schema after running
     * `MIGRATION_16_17` against a fresh v16 database matches Room's expected
     * v17 identityHash exactly. A failure here means the migration's SQL
     * has diverged (column order, defaults, FK clause, index DDL, etc.)
     * from `app/schemas/.../17.json` — exactly the failure mode that would
     * crash the app at first launch.
     */
    @Test
    fun `MIGRATION_16_17 produces a schema whose identityHash matches Room's v17 export`() {
        // 1. Create a v16 database — `MigrationTestHelper` reads `16.json`
        //    and runs the corresponding CREATE TABLE statements.
        helper.createDatabase(TEST_DB, 16).close()

        // 2. Reopen at v17 and run the migration through Room's validator.
        //    `validateDroppedTables = true` makes Room verify that no
        //    expected table is missing post-migration.
        helper.runMigrationsAndValidate(
            TEST_DB,
            17,
            true,
            Migrations.MIGRATION_16_17
        ).close()
    }

    /**
     * Validates that the post-migration schema after running
     * `MIGRATION_18_19` against a fresh v18 database matches Room's expected
     * v19 identityHash exactly — i.e. the two new nullable columns
     * (`accounts.schedule_outbox_url`, `calendars.auto_schedule_supported`)
     * match `app/schemas/.../19.json` in name, affinity, and nullability.
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
     * Validates that running `MIGRATION_19_20` against a fresh v19 database
     * matches Room's expected v20 identityHash exactly — i.e. the two new
     * nullable `attendees` columns (`itip_request_sequence`,
     * `itip_request_status`) match `app/schemas/.../20.json` in name,
     * affinity, and nullability.
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
     * Validates that running `MIGRATION_20_21` against a fresh v20 database
     * matches Room's expected v21 identityHash exactly — i.e. the hand-written
     * `CREATE TABLE pending_cancels` matches `app/schemas/.../21.json` in
     * column names, affinities, nullability, and foreign keys.
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
     * Validates that running `MIGRATION_21_22` against a fresh v21 database
     * matches Room's expected v22 identityHash exactly — i.e. the hand-written
     * `CREATE TABLE categories` (plus its `last_used_at` index) matches
     * `app/schemas/.../22.json` in column names, affinities, nullability, the
     * `COLLATE NOCASE` primary key, and index DDL.
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
     * Validates that running `MIGRATION_22_23` against a fresh v22 database
     * matches Room's expected v23 identityHash exactly — i.e. the hand-written
     * `CREATE TABLE address_books` (plus its `account_id` index and the unique
     * `(account_id, url)` index) matches `app/schemas/.../23.json` in column
     * names, affinities, nullability, foreign keys, and index DDL.
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
     * Validates that running `MIGRATION_23_24` against a fresh v23 database
     * matches Room's expected v24 identityHash exactly — i.e. the additive
     * `accounts.contact_sync_enabled` column (`INTEGER NOT NULL DEFAULT 0`)
     * matches `app/schemas/.../24.json` in name, affinity, nullability, and
     * default.
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
     * Same validation but starting from a `v1` database — verifies the
     * full migration chain produces a hash-equivalent schema all the way
     * up to v24. Belt-and-braces against any earlier-migration drift that
     * only matters once subsequent versions stack on top.
     */
    @Test
    fun `full migration chain v1 to v24 produces schema whose identityHash matches Room's export`() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            24,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            // 3 -> 4 is an AutoMigration; the helper picks it up from the
            // database class metadata when no explicit migration is provided.
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
            Migrations.MIGRATION_23_24
        ).close()
    }

    private companion object {
        const val TEST_DB = "migration-hash-validation-test"
    }
}
