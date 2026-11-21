package org.onekash.kashcal.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.KotlinSourceText
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Keeps the `event_ics` table behind [EventIcsSource]: only its entity, its DAO, the migrations
 * and their tests, and the debug report's row counts name it in code or SQL text. A query
 * elsewhere could read the pieces out of order or write one without the others, and the
 * reader's gap check would then fail the event's sync and export. Comments don't count; string
 * contents do, since that is where SQL lives.
 */
class EventIcsGuardTest {

    @Test
    fun `nothing outside the store's files names the table`() {
        val root = resolveProjectRoot()
        val hits = listOf("app/src/main/kotlin", "app/src/test/kotlin")
            .flatMap { dir -> File(root, dir).walk().filter { it.isFile && it.extension == "kt" }.toList() }
            .filterNot { file -> ALLOWED.any { file.invariantSeparatorsPath.endsWith(it) } }
            .filterNot { file -> file.invariantSeparatorsPath.contains(MIGRATION_TESTS) }
            .flatMap { file -> namesTable(file.readText()).map { "${file.relativeTo(root)}:$it" } }
        assertTrue("event_ics named outside its store:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `a query naming the table is caught and a comment is not`() {
        val source = """
            // reads event_ics directly
            @Query("SELECT * FROM event_ics WHERE event_id = :id")
            fun parts(id: Long): List<String>
        """.trimIndent()
        assertEquals(listOf(2), namesTable(source))
    }

    private companion object {
        val TABLE = Regex("""\bevent_ics\b""")
        const val MIGRATION_TESTS = "/src/test/kotlin/org/onekash/kashcal/data/db/migration/"
        val ALLOWED = listOf(
            "data/db/entity/EventIcsPart.kt",
            "data/db/dao/EventIcsDao.kt",
            "data/db/migration/Migrations.kt",
            "domain/diagnostics/DebugReportExporter.kt",
            "data/db/EventIcsGuardTest.kt",
        )

        /** Returns the 1-based lines of [source] whose code or string text names the table. */
        fun namesTable(source: String): List<Int> =
            KotlinSourceText.commentFreeLines(source).withIndex()
                .filter { (_, line) -> TABLE.containsMatchIn(line) }
                .map { it.index + 1 }
    }
}
