package org.onekash.kashcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.KotlinSourceText
import java.io.File

/**
 * Guards the UI layer's persistence boundary.
 *
 * The UI layers (Compose screens, ViewModels, UI models, the Glance widgets and
 * the activities in the root package) must go through the domain layer for all data access:
 * EventCoordinator/EventReader for Room, DeviceEventReader/DeviceEventWriter for
 * Android's calendar storage. They must never reach directly for Room (DAOs,
 * the database class, migrations/converters, Room's own APIs) or for the
 * CalendarProvider repository, because that couples presentation to the storage
 * layer and lets direct access creep back into ViewModels and widget providers.
 *
 * This is a source-scanning invariant, not a runtime one. It scans every `.kt`
 * file under each UI root, plus every file directly in the root package except
 * the Application class, and checks two things:
 * - import lines, and
 * - fully-qualified or simple-name references in code, so a reference written
 *   out in full without an import can't slip past.
 * Comments and string contents are blanked before matching, so prose or text
 * that names a class is not a violation, and code next to a comment is still
 * seen. A file the reader loses track of (an unterminated string or comment in
 * its eyes) fails the scan instead of being silently skipped.
 *
 * Room entities (`data.db.entity.*`) and the `TitleSuggestion` query projection
 * are deliberately allowed: the domain layer returns them as its data types, so
 * the UI consumes them as plain values. Likewise every
 * `data.calendar_provider` value type and helper (DeviceEvent, DeviceCalendar,
 * DeviceAttendee, canonicalAttendeeEmail, CalendarProviderManager, ...) stays
 * allowed; only the repository classes are fenced off.
 *
 * The domain layer may import Room and the repository: it is the gateway and
 * owns transaction boundaries. Only the UI layers are fenced off here.
 */
class UiLayerPersistenceBoundaryTest {

    private companion object {
        /** UI source roots, relative to the app module's kotlin source dir. */
        val UI_ROOTS = listOf(
            "org/onekash/kashcal/ui",
            "org/onekash/kashcal/widget",
        )

        /**
         * The app's root package. Every file directly in it is a UI entry point
         * (MainActivity, SettingsActivity, ...) unless it declares the
         * Application class, which is app wiring, not UI.
         */
        const val ROOT_PACKAGE_DIR = "org/onekash/kashcal"

        private val APPLICATION_CLASS =
            Regex("""\bclass\s+\w+\s*(\([^)]*\))?\s*:\s*(android\.app\.)?Application\s*\(""")

        const val ROOM_PREFIX = "androidx.room"
        const val DB_PACKAGE = "org.onekash.kashcal.data.db"
        const val ENTITY_PACKAGE = "$DB_PACKAGE.entity"
        const val CALENDAR_PROVIDER_PACKAGE = "org.onekash.kashcal.data.calendar_provider"

        /** Non-entity db symbols the domain layer returns as plain values. */
        val PERMITTED_DB_SYMBOLS = setOf(
            "$DB_PACKAGE.dao.TitleSuggestion",
        )

        val REPOSITORY_CLASSES = setOf(
            "$CALENDAR_PROVIDER_PACKAGE.CalendarProviderRepository",
            "$CALENDAR_PROVIDER_PACKAGE.AndroidCalendarProviderRepository",
        )

        /**
         * A dotted name starting at `androidx` or `org`, allowing whitespace
         * around the dots, so a name continued on the next line is one match.
         */
        private val DOTTED_NAME = Regex("""\b(?:androidx|org)(?:\s*\.\s*[A-Za-z_*]\w*)+""")
        private val REPOSITORY_NAME =
            Regex("""\b(Android)?CalendarProviderRepository\b""")

        /**
         * True if an import target is persistence machinery the UI must not
         * touch: anything under Room's runtime or the app's db package, except
         * the entity value types and [PERMITTED_DB_SYMBOLS]. Wildcards
         * (`data.db.*`) count as forbidden since they pull in the database
         * class and DAOs.
         */
        fun isForbiddenImport(imported: String): Boolean {
            fun under(prefix: String) = imported == prefix || imported.startsWith("$prefix.")
            if (under(ROOM_PREFIX)) return true
            if (imported in PERMITTED_DB_SYMBOLS) return false
            return under(DB_PACKAGE) && !imported.startsWith("$ENTITY_PACKAGE.")
        }

        /**
         * True if an import target is a CalendarProvider repository class, or a
         * wildcard over its package (which would pull the repository in).
         */
        fun isForbiddenRepositoryImport(imported: String): Boolean =
            imported in REPOSITORY_CLASSES || imported == "$CALENDAR_PROVIDER_PACKAGE.*"

        /** The imported symbol of an `import` line, alias stripped. */
        fun importTarget(line: String): String =
            line.trim().removePrefix("import ").substringBefore(" as ").trim()

        /** The code part of a single source line (comments and literal contents blanked). */
        fun codeLine(line: String): String = KotlinSourceText.codeLines(line).single()

        /** True if a root-package file is UI, i.e. it doesn't declare the Application class. */
        fun isRootUiFile(source: String): Boolean =
            !APPLICATION_CLASS.containsMatchIn(KotlinSourceText.codeLines(source).joinToString("\n"))

        /**
         * A boundary rule: patterns matched over a file's whole blanked text,
         * each with the test a match must pass to be a violation. Matching the
         * whole text, not line by line, means imports joined by `;`, names
         * split across lines and code on the package line are all seen.
         */
        class Rule(private val checks: List<Pair<Regex, (String) -> Boolean>>) {
            /** (line, matched name) of every violation in [source]. */
            fun hits(source: String): List<Pair<Int, String>> =
                checks.flatMap { (pattern, isViolation) ->
                    KotlinSourceText.references(source, pattern).filter { isViolation(it.second) }
                }.distinctBy { it.first }.sortedBy { it.first }
        }

        /** Room, or the app's db package other than the permitted value types. */
        val ROOM_RULE = Rule(listOf(DOTTED_NAME to ::isForbiddenImport))

        /** The repository classes by name, qualified or not, and a wildcard over their package. */
        val REPOSITORY_RULE = Rule(
            listOf(
                REPOSITORY_NAME to { _: String -> true },
                DOTTED_NAME to ::isForbiddenRepositoryImport,
            )
        )

        /** True if a code fragment names Room or a non-permitted db symbol in full. */
        fun roomReferenceIn(code: String): Boolean = ROOM_RULE.hits(code).isNotEmpty()

        /** True if a code fragment names a repository class, qualified or not. */
        fun repositoryReferenceIn(code: String): Boolean = REPOSITORY_RULE.hits(code).isNotEmpty()

        /** Resolve the main/ kotlin source dir regardless of where the runner starts. */
        fun mainSourceRoot(): File {
            val relative = "src/main/kotlin"
            val candidates = listOf(
                File(relative),                 // working dir = app module
                File("app/$relative"),          // working dir = repo root
            )
            return candidates.firstOrNull { it.isDirectory }
                ?: error(
                    "Could not locate the main/ source root from working dir " +
                        "'${File(".").absolutePath}'. Tried: " +
                        candidates.joinToString { it.path }
                )
        }

        /** Every scanned UI file, keyed by its path relative to org/onekash/kashcal. */
        fun uiFiles(): Map<String, File> {
            val main = mainSourceRoot()
            val base = File(main, "org/onekash/kashcal")
            val files = mutableListOf<File>()
            for (uiRoot in UI_ROOTS) {
                val root = File(main, uiRoot)
                val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                // Fails on an empty or wrong dir, which would scan nothing and pass.
                assertTrue(
                    "Expected to scan $uiRoot but found no .kt files under ${root.path}",
                    ktFiles.isNotEmpty()
                )
                files += ktFiles
            }
            val rootFiles = File(main, ROOT_PACKAGE_DIR).listFiles { f -> f.isFile && f.extension == "kt" }
                .orEmpty()
            assertTrue("Expected .kt files directly in $ROOT_PACKAGE_DIR", rootFiles.isNotEmpty())
            files += rootFiles.filter { isRootUiFile(it.readText()) }
            return files.associateBy { it.relativeTo(base).invariantSeparatorsPath }
        }

        /** One offending line, as "path:line  text". */
        data class Violation(val file: String, val line: Int, val text: String) {
            override fun toString() = "$file:$line  $text"
        }

        /** Violations of [rule] in one file's [source], reported by line. */
        fun violationsIn(path: String, source: String, rule: Rule): List<Violation> {
            val raw = source.split("\n")
            return rule.hits(source).map { (line, _) -> Violation(path, line, raw[line - 1].trim()) }
        }

        /** Scan every UI file with [rule]. */
        fun scan(rule: Rule): List<Violation> =
            uiFiles().flatMap { (path, file) -> violationsIn(path, file.readText(), rule) }
    }

    @Test
    fun `ui, widget and root-package UI files do not touch Room or the database package`() {
        val violations = scan(ROOM_RULE)

        assertTrue(
            buildString {
                appendLine(
                    "UI and widget layers must not touch Room directly. Route data access " +
                        "through the domain layer (EventCoordinator/EventReader). Offending lines:"
                )
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun `ui, widget and root-package UI files do not touch the calendar provider repository`() {
        val violations = scan(REPOSITORY_RULE)

        assertTrue(
            buildString {
                appendLine(
                    "UI code must not use CalendarProviderRepository. Read device events " +
                        "through DeviceEventReader and write them through DeviceEventWriter. " +
                        "Offending lines:"
                )
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty()
        )
    }

    /**
     * Self-check: the matcher must flag every flavor of persistence import.
     * Without this, a refactor that broke the matcher (renamed packages, a
     * prefix typo) would silently turn the guard into a no-op that always passes.
     */
    @Test
    fun `matcher flags persistence imports`() {
        listOf(
            "androidx.room.withTransaction",
            "androidx.room.Room",
            "org.onekash.kashcal.data.db.dao.EventsDao",
            "org.onekash.kashcal.data.db.KashCalDatabase",
            "org.onekash.kashcal.data.db.migration.MIGRATION_22_23",
            "org.onekash.kashcal.data.db.converter.Converters",
            "org.onekash.kashcal.data.db.*",
            "org.onekash.kashcal.data.db.dao.*",
        ).forEach {
            assertTrue("Matcher failed to flag a real violation: $it", isForbiddenImport(it))
        }
    }

    @Test
    fun `matcher flags calendar provider repository imports`() {
        listOf(
            "org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository",
            "org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository",
            "org.onekash.kashcal.data.calendar_provider.*",
        ).forEach {
            assertTrue("Matcher failed to flag a repository import: $it", isForbiddenRepositoryImport(importTarget("import $it")))
        }
        assertTrue(
            "Matcher failed to flag an aliased repository import",
            isForbiddenRepositoryImport(importTarget("import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository as Repo"))
        )
    }

    @Test
    fun `matcher permits calendar provider value types and helpers`() {
        listOf(
            "org.onekash.kashcal.data.calendar_provider.DeviceEvent",
            "org.onekash.kashcal.data.calendar_provider.DeviceCalendar",
            "org.onekash.kashcal.data.calendar_provider.DeviceAttendee",
            "org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance",
            "org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail",
            "org.onekash.kashcal.data.calendar_provider.cleanCategoryNames",
            "org.onekash.kashcal.data.calendar_provider.CalendarProviderManager",
            "org.onekash.kashcal.data.calendar_provider.CalendarProviderRepositoryX",
            "org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository",
        ).forEach {
            assertFalse("Matcher wrongly flagged an allowed import: $it", isForbiddenRepositoryImport(it))
            assertFalse("Matcher wrongly flagged an allowed import: $it", isForbiddenImport(it))
        }
        assertFalse(isForbiddenImport("org.onekash.kashcal.data.db.dao.TitleSuggestion"))
        listOf("CalendarProviderRepositoryX()", "FakeCalendarProviderRepository()").forEach {
            assertFalse("Simple-name rule wrongly flagged: $it", repositoryReferenceIn(it))
        }
    }

    @Test
    fun `body scanner flags code references and ignores comments`() {
        val repoFqn = "val r: org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository"
        assertTrue(repositoryReferenceIn(codeLine(repoFqn)))
        assertTrue(repositoryReferenceIn(codeLine("    private val repo: AndroidCalendarProviderRepository,")))
        assertFalse(repositoryReferenceIn(codeLine("    // $repoFqn")))
        assertFalse(
            repositoryReferenceIn(
                KotlinSourceText.codeLines(
                    "    /**\n     * [org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository]\n     */"
                ).joinToString("\n")
            )
        )
        assertFalse(repositoryReferenceIn(codeLine("    /** CalendarProviderRepository */")))
        assertFalse(repositoryReferenceIn(codeLine("    is Device -> true // already filtered by CalendarProviderRepository")))

        assertTrue(roomReferenceIn(codeLine("    val dao = org.onekash.kashcal.data.db.dao.EventsDao()")))
        assertTrue(roomReferenceIn(codeLine("    androidx.room.withTransaction(db) {}")))
        assertFalse(roomReferenceIn(codeLine("    val s: List<org.onekash.kashcal.data.db.dao.TitleSuggestion>")))
        assertFalse(roomReferenceIn(codeLine("    val e: org.onekash.kashcal.data.db.entity.Event")))
    }

    /**
     * Self-check spellings a line-based scan misses: code that follows a comment
     * on the same line, code after a URL string, and a reference inside a string
     * template must all be seen.
     */
    @Test
    fun `scan sees code next to comments, after strings and inside templates`() {
        fun repositoryHits(source: String) =
            violationsIn("Probe.kt", source, REPOSITORY_RULE)

        assertEquals(1, repositoryHits("/* note */ val r: CalendarProviderRepository? = null").size)
        assertEquals(1, repositoryHits("/* note */ import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository").size)
        assertEquals(
            listOf(3),
            repositoryHits("/*\n a comment\n */ val r: CalendarProviderRepository? = null").map { it.line },
        )
        assertEquals(1, repositoryHits("val u = \"https://example.test\"; val r: CalendarProviderRepository? = null").size)
        assertEquals(
            1,
            repositoryHits(
                "val s = \"${'$'}{org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository::class}\""
            ).size,
        )
        assertEquals(
            1,
            violationsIn("Probe.kt", "/* x */ val d = org.onekash.kashcal.data.db.dao.EventsDao()", ROOM_RULE).size,
        )
    }

    /**
     * Self-check spellings that a line-by-line import check misses: two imports
     * joined by `;`, an import split before a dot, and code on the package line.
     */
    @Test
    fun `scan sees imports joined, split across lines, and code on the package line`() {
        fun roomHits(source: String) = violationsIn("Probe.kt", source, ROOM_RULE)
        fun repositoryHits(source: String) = violationsIn("Probe.kt", source, REPOSITORY_RULE)

        assertEquals(
            listOf(1),
            roomHits("import kotlin.text.Regex; import org.onekash.kashcal.data.db.dao.EventsDao\nclass X(val d: EventsDao)")
                .map { it.line },
        )
        assertEquals(
            1,
            repositoryHits(
                "import kotlin.text.Regex; import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository as R"
            ).size,
        )
        assertEquals(1, roomHits("import org.onekash.kashcal.data\n    .db.dao.EventsDao").size)
        assertEquals(1, repositoryHits("package org.onekash.kashcal.ui; val r: CalendarProviderRepository? = null").size)
        assertEquals(1, repositoryHits("import org.onekash.kashcal.data.calendar_provider.*").size)
        assertTrue("a package line alone is fine", roomHits("package org.onekash.kashcal.ui\n").isEmpty())
    }

    /** Self-check that names appearing only inside strings or comments aren't flagged. */
    @Test
    fun `scan ignores names inside strings and comments`() {
        fun repositoryHits(source: String) =
            violationsIn("Probe.kt", source, REPOSITORY_RULE)

        assertTrue(repositoryHits("val s = \"CalendarProviderRepository\"").isEmpty())
        assertTrue(repositoryHits("val s = \"\"\"\nuses CalendarProviderRepository\n\"\"\"").isEmpty())
        assertTrue(repositoryHits("/*\n CalendarProviderRepository\n */\nval a = 1").isEmpty())
        assertTrue(repositoryHits("/** [CalendarProviderRepository] */ val a = 1").isEmpty())
        assertTrue(
            violationsIn("Probe.kt", "val s = \"org.onekash.kashcal.data.db.dao.EventsDao\"", ROOM_RULE).isEmpty()
        )
    }

    /**
     * Self-check the root-package rule: every file directly in the app's root
     * package is a UI entry point unless it declares the Application class.
     */
    @Test
    fun `root package files count as UI unless they declare the Application`() {
        assertTrue(isRootUiFile("class SettingsActivity : FragmentActivity() {}"))
        assertTrue(isRootUiFile("class NewEntry : ComponentActivity()"))
        assertTrue(isRootUiFile("object Helper"))
        assertFalse(isRootUiFile("@HiltAndroidApp\nclass KashCalApplication : Application(), Configuration.Provider {}"))
        assertFalse(isRootUiFile("class App : android.app.Application()"))
        // The Application superclass named only in a comment doesn't exempt a file.
        assertTrue(isRootUiFile("// : Application()\nclass Other : FragmentActivity()"))

        val scanned = uiFiles().keys
        assertTrue("MainActivity.kt must be scanned", "MainActivity.kt" in scanned)
        assertTrue("SettingsActivity.kt must be scanned", "SettingsActivity.kt" in scanned)
        assertFalse("KashCalApplication.kt is not UI", "KashCalApplication.kt" in scanned)
    }

    /**
     * Self-check the other direction: entity value types and look-alike
     * packages must not be flagged, or the guard would block the data types the
     * domain layer hands to the UI.
     */
    @Test
    fun `matcher permits entities and unrelated packages`() {
        listOf(
            "org.onekash.kashcal.data.db.entity.Event",
            "org.onekash.kashcal.data.db.entity.*",
            "org.onekash.kashcal.data.dbutil.Something",
            "org.onekash.kashcal.domain.reader.EventReader",
            "androidx.roomx.Other",
        ).forEach {
            assertFalse("Matcher wrongly flagged an allowed import: $it", isForbiddenImport(it))
        }
    }
}
