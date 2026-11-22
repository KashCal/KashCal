package org.onekash.kashcal.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps every Room database in the unit and instrumented tests on [TestDatabases] or
 * `DeviceTestDatabases`, so each one runs on the driver the app uses; a Room builder created
 * anywhere else could miss it. It also keeps SupportSQLite out of the tests (`openHelper`,
 * `withTransaction`, `RoomDatabase.query`, the `androidx.sqlite.db` types): Room opens the
 * database only through its driver.
 */
class TestDatabasesGuardTest {

    @Test
    fun `room builders are created only through TestDatabases`() {
        val offenders = testSourceFiles()
            .filterNot { file -> ALLOWED_FILES.any { file.invariantSeparatorsPath.endsWith("/$it") } }
            .flatMap { file ->
                // Matches across line breaks and refuses a file it can't read back to plain code.
                KotlinSourceText.references(file.readText(), ROOM_BUILDER).map { (line, _) -> "${file.path}:$line" }
            }
        if (offenders.isNotEmpty()) {
            fail(
                "Build test databases with TestDatabases.inMemory/file, not Room directly:\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `tests reach Room only through driver APIs`() {
        val offenders = testSourceFiles()
            .filterNot { file -> SUPPORT_SQLITE_ALLOWED.keys.any { file.invariantSeparatorsPath.endsWith("/$it") } }
            .flatMap { file ->
                KotlinSourceText.references(file.readText(), SUPPORT_SQLITE_USE).map { (line, _) -> "${file.path}:$line" }
            }
        if (offenders.isNotEmpty()) {
            fail(
                "Reach the database through TestDatabases.execute/query/raw or useReaderConnection/" +
                    "useWriterConnection, not SupportSQLite:\n" + offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `the support-sqlite scan sees each form and ignores comments and strings`() {
        val found = KotlinSourceText.references(
            "database.openHelper.writableDatabase.execSQL(sql)\n" +
                "db.withTransaction { }\n" +
                "val q: SupportSQLiteQuery = x\n" +
                "room.db.query(sql, null)\n" +
                "database.query(sql, null)\n" +
                "// database.openHelper in a comment\n" +
                "val s = \"db.query(\"\n" +
                "TestDatabases.raw(db).query(sql)\n" +
                "connection.queryRows(sql)\n" +
                "import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory\n" +
                "val f = FrameworkSQLiteOpenHelperFactory()\n" +
                "database\n    .query(sql, null)\n" +
                "roomDb.query(SimpleSQLiteQuery(sql))",
            SUPPORT_SQLITE_USE
        ).map { it.first }.distinct()
        assertEquals(listOf(1, 2, 3, 4, 5, 10, 11, 12, 14), found)
    }

    @Test
    fun `the scan sees direct builders and ignores one inside a string`() {
        val found = KotlinSourceText.references(
            "val a = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)\n" +
                "val b = Room\n    .inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)\n" +
                "val c = Room.inMemoryDatabaseBuilder<KashCalDatabase>(context)\n" +
                "val d = \"Room.databaseBuilder(context, KashCalDatabase::class.java, name)\"",
            ROOM_BUILDER
        ).map { it.first }
        assertEquals(listOf(1, 2, 4), found)
    }

    /** Returns the unit and instrumented test sources. */
    private fun testSourceFiles(): List<File> = listOf("src/test/kotlin", "src/androidTest/kotlin").flatMap { relative ->
        val root = listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
            ?: error("Test sources '$relative' not found from '${File(".").absolutePath}'")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        val ROOM_BUILDER =
            Regex("""\bRoom\s*\.\s*(inMemoryDatabaseBuilder|databaseBuilder)\s*(<[^>]*>)?\s*\(""")

        /** Holds the files allowed to create Room builders: the unit and instrumented helpers. */
        val ALLOWED_FILES = setOf("testutil/TestDatabases.kt", "testutil/DeviceTestDatabases.kt")

        /**
         * Matches SupportSQLite access: openHelper, withTransaction, compileStatement, the
         * `androidx.sqlite.db` package and its types (including the framework open helper and
         * SimpleSQLiteQuery), and Room's `query(` on the receiver names the tests give a Room
         * database. `RoomDatabase.isOpen` isn't matched: UI state classes have an `isOpen` too.
         */
        val SUPPORT_SQLITE_USE = Regex(
            """\bopenHelper\b|\bwithTransaction\b|\bcompileStatement\b|\bandroidx\.sqlite\.db\b|""" +
                """\bSupportSQLite\w*|\bFramework\w*OpenHelper\w*|\bSimpleSQLiteQuery\b|""" +
                """\b(db|database|room|roomDb|fresh|\w+\.db)\s*\.\s*query\s*\("""
        )

        /** Holds the files allowed SupportSQLite access, each with the reason. */
        val SUPPORT_SQLITE_ALLOWED = mapOf(
            "data/calendar_provider/SqliteCalendarProvider.kt" to
                "its .query( calls are on a framework SQLiteDatabase in the CalendarProvider test double",
        )
    }
}
