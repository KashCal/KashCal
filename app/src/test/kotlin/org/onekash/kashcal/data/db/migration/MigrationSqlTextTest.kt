package org.onekash.kashcal.data.db.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.onekash.kashcal.testutil.KotlinSourceText
import java.io.File

/**
 * Tests that no shipped migration changes the SQL text it sends SQLite. [MigrationSource] reads
 * every string literal in `Migrations.kt` passed to a SQL call, a SQL helper or a list, or kept in
 * a `val`, with whitespace collapsed, and files it under the migration whose body holds it (or
 * `shared`, for helpers and constants that shipped migrations use) and under the call it goes to.
 * `migration-sql/Migrations.sql.txt` is the reference set of those texts, recorded from the file
 * as it was on `SupportSQLiteDatabase`.
 *
 * Each recorded migration and `shared` must pass exactly its recorded texts to the same calls, as
 * many times; a changed table name, column, WHERE clause, validated name or helper fails here
 * even where the reference dumps of [MigrationDifferentialTest] have no row to show it. The order
 * of statements is not checked; those dumps check the outcome. A migration the reference doesn't
 * hold is new and may pass any text. The one text `shared` may add is `SELECT changes()`, which
 * reads the row count of the UPDATE or DELETE before it. A missing reference file is recorded and
 * the test fails, so a deleted reference can't pass unnoticed.
 */
class MigrationSqlTextTest {

    @Test
    fun `no shipped migration passes different SQL text`() {
        val actual = sqlTexts(source())
        val reference = referenceFile()
        if (!reference.isFile) {
            reference.parentFile.mkdirs()
            reference.writeText(actual.joinToString("\n", postfix = "\n") { "${it.owner}\t${it.call}\t${it.text}" })
            fail("recorded ${reference.path} with ${actual.size} texts; check it in and rerun")
        }
        val expected = reference.readLines().filter { it.isNotEmpty() }.map { line ->
            val (owner, call, text) = line.split('\t', limit = 3)
            MigrationSource.SqlText(owner, call, text)
        }
        val expectedByOwner = expected.groupBy({ it.owner }, { "${it.call}: ${it.text}" })
        val actualByOwner = actual.groupBy({ it.owner }, { "${it.call}: ${it.text}" })
        val problems = mutableListOf<String>()
        for ((owner, texts) in expectedByOwner) {
            val now = actualByOwner[owner].orEmpty()
                .filterNot { owner == SHARED && it == "${MigrationSource.SQL}: $ALLOWED_ADDITION" }
            if (texts.groupingBy { it }.eachCount() != now.groupingBy { it }.eachCount()) {
                problems += "$owner:\n  no longer passed: ${(texts - now.toSet()).ifEmpty { texts.diffCounts(now) }}" +
                    "\n  not recorded: ${now - texts.toSet()}"
            }
        }
        val unknownShared = actualByOwner.keys - expectedByOwner.keys
        assertTrue("texts outside any migration must be recorded: $unknownShared", SHARED !in unknownShared)
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `texts are filed under the migration whose body holds them and the call they go to`() {
        val sample = """
            private fun helper() { connection.execSQL("HELPER SQL") }
            val MIGRATION_1_2 = object : Migration(1, 2) {
                override fun migrate(connection: SQLiteConnection) {
                    if (x) { connection.execSQL("ONE") }
                    for (col in listOf("a", "b")) columnExists(connection, "t", col)
                }
            }
            val MIGRATION_2_3 = object : Migration(2, 3) {
                override fun migrate(connection: SQLiteConnection) {
                    val sql = "DELETE FROM t"
                    connection.execSQL(sql)
                    dropIndexIfExists(connection, "i")
                }
            }
        """.trimIndent()
        assertEquals(
            listOf(
                "MIGRATION_1_2 columnExists t", "MIGRATION_1_2 list a", "MIGRATION_1_2 list b",
                "MIGRATION_1_2 sql ONE", "MIGRATION_2_3 dropIndexIfExists i", "MIGRATION_2_3 val DELETE FROM t",
                "shared sql HELPER SQL",
            ),
            sqlTexts(sample).map { "${it.owner} ${it.call} ${it.text}" },
        )
    }

    @Test
    fun `the extractor finds literals in calls and constants and skips comments`() {
        val sample = """
            fun f() {
                // db.execSQL("COMMENTED OUT")
                db.execSQL(""${'"'}
                    UPDATE t
                    SET a = 1
                ""${'"'}.trimIndent())
                db.query("SELECT x FROM t WHERE y = ?", arrayOf("lit")).use { }
                Log.d(TAG, "not SQL")
                count(db, "SELECT COUNT(*) FROM t /* (paren) */")
            }
            private const val PART =
                "a = 1" +
                    "AND b"
            private val NAMES = listOf("x").map { "${'$'}{it}_y" }
            val MIGRATION_1_2 = object : Migration(1, 2) { fun g() = "inside body" }
        """.trimIndent()
        assertEquals(
            listOf(
                "${'$'}{it}_y", "AND b", "SELECT COUNT(*) FROM t /* (paren) */", "SELECT x FROM t WHERE y = ?",
                "UPDATE t SET a = 1", "a = 1", "lit", "x",
            ),
            sqlTexts(sample).map { it.text }.sorted(),
        )
    }

    private fun referenceFile(): File {
        val relative = "src/test/resources/migration-sql/Migrations.sql.txt"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.parentFile.parentFile.isDirectory }
            ?: File(relative)
    }

    private companion object {
        const val ALLOWED_ADDITION = "SELECT changes()"
        const val SHARED = MigrationSource.SHARED

        fun source() = MigrationSource.text()
        fun sqlTexts(source: String) = MigrationSource.sqlTexts(source)

        private fun List<String>.diffCounts(other: List<String>): Map<String, Pair<Int, Int>> {
            val mine = groupingBy { it }.eachCount()
            val theirs = other.groupingBy { it }.eachCount()
            return mine.filter { (k, v) -> theirs[k] != v }.mapValues { (k, v) -> v to (theirs[k] ?: 0) }
        }
    }
}
