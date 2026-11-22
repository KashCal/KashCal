package org.onekash.kashcal.data.db.migration

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.testutil.queryRows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests that every upgrade path still produces exactly the database it produced before: for each
 * old schema version a seeded database is migrated to the current version and its whole state
 * ([DatabaseDump]) is compared with a committed reference dump. The references were recorded
 * before any change to the migration code, so they hold the results users' upgrades have always
 * had; a migration change that alters a row, a type, an index or a trigger fails here. Every
 * upgrade runs through [MigrationTestHelper] on [AndroidSQLiteDriver], the driver the app opens
 * its database with.
 *
 * Record mode (`-Pkashcal.migrationDumps.record=true`) rewrites the references and must only be
 * used when a new schema version or migration is added on purpose. `-Pkashcal.migrationSeeds.dir`
 * writes the seed SQL of each public schema version for the emulator upgrade check, and
 * `-Pkashcal.dump.dir` dumps the databases that check pulls from the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationDifferentialTest {

    private val record = System.getProperty("kashcal.migrationDumps.record") == "true"

    @Test
    fun `every upgrade path matches its reference dump`() {
        val mismatches = mutableListOf<String>()
        for (version in START_VERSIONS) {
            val actual = upgrade(version, "diff-$version")
            val reference = referenceFile(version)
            if (record) {
                reference.parentFile.mkdirs()
                reference.writeText(actual)
                continue
            }
            assertTrue("missing reference ${reference.path}; record it first", reference.isFile)
            val expected = reference.readText()
            if (expected != actual) {
                mismatches += "v$version -> $TARGET:\n" + DatabaseDump.differences(expected, actual).joinToString("\n")
            }
        }
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n\n"))
    }

    @Test
    fun `two upgrades from the same seed give the same dump`() {
        for (version in START_VERSIONS) {
            assertEquals("v$version", upgrade(version, "repeat-a-$version"), upgrade(version, "repeat-b-$version"))
        }
    }

    @Test
    fun `every seeded trigger and row is accepted at every version`() {
        for (version in 1..CURRENT) {
            val seeded = withNewDatabase("accept-$version", version) { db -> MigrationSeeder.seed(version) { db.execSQL(it) } }
            val refused = seeded.refused.filter { it.kind != MigrationSeeder.Kind.SCENARIO }
            assertTrue("v$version refused:\n" + refused.joinToString("\n") { it.sql.take(200) }, refused.isEmpty())
        }
    }

    @Test
    fun `the seed reaches every data-dependent migration branch`() {
        for (branch in MigrationSeeder.branches) {
            val name = "branch-${branch.fromVersion}-${branch.description.hashCode()}"
            val count = withNewDatabase(name, branch.fromVersion) { db ->
                MigrationSeeder.seed(branch.fromVersion) { db.execSQL(it) }
                db.queryRows(branch.countSql).use { c -> c.moveToFirst(); c.getLong(0) }
            }
            assertTrue("v${branch.fromVersion}: no seeded row reaches '${branch.description}'", count > 0)
        }
    }

    @Test
    fun `seed SQL is written for the public schema versions when asked`() {
        val dir = System.getProperty("kashcal.migrationSeeds.dir") ?: return
        File(dir).mkdirs()
        for (version in PUBLIC_VERSIONS) {
            val kept = withNewDatabase("seed-export-$version", version) { db ->
                MigrationSeeder.seed(version) { db.execSQL(it) }.kept
            }
            // A device database already has the triggers its build created on first launch.
            val rows = kept.filter { it.kind != MigrationSeeder.Kind.TRIGGER }.map { it.sql }
            File(dir, "seed-v$version.sql").writeText(rows.joinToString(";\n", postfix = ";\n"))
        }
    }

    @Test
    fun `databases pulled from a device are dumped when asked`() {
        val dir = System.getProperty("kashcal.dump.dir")?.let(::File) ?: return
        val pulled = dir.listFiles { f -> f.extension == "db" }.orEmpty()
        for (db in pulled) {
            // <name>.window holds the device clock around the launch that migrated <name>.db.
            val window = File(dir, db.nameWithoutExtension + ".window").takeIf { it.isFile }
                ?.readText()?.trim()?.split(" ")?.let { (start, end) -> start.toLong()..end.toLong() }
            File(dir, db.nameWithoutExtension + ".txt").writeText(DatabaseDump.of(db, window))
        }
    }

    /** Seeds a database at [version], migrates it to [TARGET] and returns its dump. */
    private fun upgrade(version: Int, name: String): String = runBlocking {
        val (helper, file) = helperFor(name)
        val seeded = helper.createDatabase(version)
        MigrationSeeder.seed(version) { seeded.execSQL(it) }
        seeded.close()
        val start = System.currentTimeMillis()
        helper.runMigrationsAndValidate(TARGET, Migrations.ALL_MIGRATIONS.filter { it.endVersion <= TARGET }).close()
        val end = System.currentTimeMillis()
        DatabaseDump.of(file, timeWindow = start..end)
    }

    /** Creates database [name] at [version], hands its connection to [block] and closes it. */
    private fun <T> withNewDatabase(name: String, version: Int, block: (SQLiteConnection) -> T): T = runBlocking {
        val connection = helperFor(name).first.createDatabase(version)
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    /** Returns a [MigrationTestHelper] on [AndroidSQLiteDriver] for a fresh file [name]. */
    private fun helperFor(name: String): Pair<MigrationTestHelper, File> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = instrumentation.targetContext.getDatabasePath(name)
        file.parentFile?.mkdirs()
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
        return MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), KashCalDatabase::class) to file
    }

    private fun referenceFile(version: Int): File {
        val relative = "src/test/resources/migration-dumps/v$version.txt"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.parentFile.parentFile.isDirectory }
            ?: File(relative)
    }

    private companion object {
        val CURRENT: Int = TestDatabases.exportedSchemaVersion

        /** Holds the version the references were recorded at: the current one. */
        val TARGET: Int = CURRENT
        val START_VERSIONS = 1 until TARGET
        val PUBLIC_VERSIONS = listOf(22, 24, 25, 27, 28)
    }
}
