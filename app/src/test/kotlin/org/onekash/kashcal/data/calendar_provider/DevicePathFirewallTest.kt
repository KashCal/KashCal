package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.KotlinSourceText
import java.io.File

/**
 * Keeps the device-calendar path disjoint from the Room/CalDAV scheduling stack.
 *
 * Device-event delivery is the OS sync adapter's job; KashCal only writes provider rows. The
 * CalDAV scheduling bugs (iTIP REQUEST/REPLY/CANCEL, SEQUENCE versioning, ORGANIZER mailto/urn
 * wire encoding, SCHEDULE-STATUS read-back, outbox POST) can't occur on the device path only
 * while it never reaches the scheduling, iTIP or coordinator code. This test fails the day
 * someone wires the two together, instead of those bugs silently reappearing on device events.
 *
 * Implemented as source scans (no ArchUnit or Konsist on the classpath): the provider
 * repository's imports are checked against a list of scheduling symbols, and the domain-side
 * device files (every Device*.kt in domain/reader, domain/writer and util, plus the same-package
 * code they use) are checked in full, imports and code bodies alike.
 */
class DevicePathFirewallTest {

    private val mainSrc = File(System.getProperty("user.dir"), "src/main/kotlin")

    private val deviceProviderFile = File(
        mainSrc,
        "org/onekash/kashcal/data/calendar_provider/AndroidCalendarProviderRepository.kt"
    )

    /**
     * Import-line fragments that mean the provider repository has reached the Room/CalDAV
     * scheduling stack. Matched against `import` lines only, so an unrelated identifier in a
     * comment or string can't trip it.
     */
    private val forbiddenImportFragments = listOf(
        ".domain.coordinator.",      // EventCoordinator / EventWriter (Room write orchestration)
        ".domain.scheduling.",       // iTIP / SEQUENCE / itip-builder stack
        ".sync.",                    // PushStrategy / PullStrategy / SyncEngine / outbox
        "ITipBuilder",
        "EventCoordinator",
        "EventWriter",
        "data.db.entity.Attendee",   // the Room attendee entity + its iTIP wire fields
    )

    @Test
    fun `device provider repository imports no scheduling or coordinator symbols`() {
        assertTrue(
            "Expected device provider source at ${deviceProviderFile.path}",
            deviceProviderFile.exists()
        )
        val importLines = deviceProviderFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }

        val violations = importLines.filter { line ->
            forbiddenImportFragments.any { line.contains(it) }
        }

        assertTrue(
            "The device-calendar write path must not depend on the Room/CalDAV " +
                "scheduling stack — delivery is the OS sync adapter's job. " +
                "Found forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * The domain-side device files (DeviceEventReader, DeviceEventWriter, the ICS
     * importer the writer delegates to, and any new Device*.kt beside them) sit
     * on the same side of the firewall: they wrap the provider repository and
     * must not reach Room event orchestration, the Room attendee data, iTIP
     * scheduling or CalDAV sync.
     *
     * Checked on the whole source with comments and string contents blanked:
     * - any dotted name (import, alias target, or fully-qualified in code, even
     *   split across lines or after a `;`) under a forbidden package or naming a
     *   forbidden class;
     * - the bare names EventCoordinator / EventWriter / EventReader, which the
     *   reader and writer could use from their own package with no import (word
     *   boundaries keep DeviceEventWriter and DeviceEventReader from matching);
     * - the same checks on every same-package file whose top-level declarations
     *   a device file uses, followed transitively, so a helper in the same
     *   package can't carry the dependency in under another name.
     * Imports of other app packages are not followed: they are explicit, and the
     * data layer the device path relies on legitimately reaches the widgets.
     */
    private val deviceDomainDirs = listOf(
        "org/onekash/kashcal/domain/reader",
        "org/onekash/kashcal/domain/writer",
        "org/onekash/kashcal/util",
    )

    private val knownDeviceDomainFiles = listOf(
        "org/onekash/kashcal/domain/reader/DeviceEventReader.kt",
        "org/onekash/kashcal/domain/writer/DeviceEventWriter.kt",
        "org/onekash/kashcal/util/DeviceCalendarImporter.kt",
    )

    private val forbiddenPackages = listOf(
        "org.onekash.kashcal.domain.coordinator.",
        "org.onekash.kashcal.domain.scheduling.",
        "org.onekash.kashcal.sync.",
        "org.onekash.icaldav.scheduling.",
        "org.onekash.kashcal.data.db.dao.",
        "androidx.room.",
    )

    private val forbiddenClasses = listOf(
        "org.onekash.kashcal.domain.writer.EventWriter",
        "org.onekash.kashcal.domain.reader.EventReader",
        "org.onekash.kashcal.data.db.entity.Attendee",
    )

    private val dottedName = Regex("""\b(?:androidx|org)(?:\s*\.\s*[A-Za-z_*]\w*)+""")
    private val forbiddenSimpleName = Regex("""\b(EventCoordinator|EventWriter|EventReader)\b""")

    /** Top-level, non-private declarations of a file (classes, objects, functions, properties). */
    private val topLevelDeclaration = Regex(
        """(?m)^((?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:public|internal|private|protected|data|sealed|abstract|open|enum|annotation|value|inline|const|suspend|operator|infix|fun)\s+)*)""" +
            """(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>\n]*>\s*)?(?:[\w.]+\.)?(\w+)"""
    )

    private fun isForbiddenName(name: String): Boolean =
        forbiddenPackages.any { name.startsWith(it) || name == it.removeSuffix(".") } ||
            forbiddenClasses.any { name == it || name.startsWith("$it.") }

    /** Forbidden references made directly in [source], as (line, name). */
    private fun directHits(source: String): List<Pair<Int, String>> =
        (
            KotlinSourceText.references(source, dottedName).filter { isForbiddenName(it.second) } +
                KotlinSourceText.references(source, forbiddenSimpleName)
            ).distinctBy { it.first }.sortedBy { it.first }

    private fun declaredNames(source: String): Set<String> =
        topLevelDeclaration.findAll(KotlinSourceText.codeLines(source).joinToString("\n"))
            .filterNot { "private" in it.groupValues[1] }
            .map { it.groupValues[2] }
            .toSet()

    /**
     * Returns the forbidden dependencies of the device file [path], reached directly or through
     * the same-package files in [siblings] (file name to source), as "file:line  text" entries.
     */
    private fun domainViolations(path: String, source: String, siblings: Map<String, String>): List<String> {
        fun describe(file: String, text: String, hits: List<Pair<Int, String>>): List<String> {
            val raw = text.split("\n")
            return hits.map { (line, _) -> "$file:$line  ${raw[line - 1].trim()}" }
        }

        val violations = describe(path, source, directHits(source)).toMutableList()
        val names = siblings.mapValues { (_, text) -> declaredNames(text) }
        val visited = mutableSetOf(path.substringAfterLast('/'))
        val queue = ArrayDeque(listOf(path to source))
        while (queue.isNotEmpty()) {
            val (user, text) = queue.removeFirst()
            val code = KotlinSourceText.codeLines(text).joinToString("\n")
            for ((file, declared) in names) {
                if (file in visited) continue
                val used = declared.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(code) }
                if (used.isEmpty()) continue
                visited += file
                val sibling = siblings.getValue(file)
                describe(file, sibling, directHits(sibling)).forEach {
                    violations += "$it  (reached from ${user.substringAfterLast('/')} via ${used.joinToString()})"
                }
                queue.addLast(file to sibling)
            }
        }
        return violations
    }

    @Test
    fun `device domain files depend on no coordinator, Room attendee, scheduling or sync code`() {
        val deviceFiles = deviceDomainDirs.flatMap { dir ->
            File(mainSrc, dir).listFiles { f -> f.isFile && f.name.startsWith("Device") && f.extension == "kt" }
                .orEmpty()
                .map { "$dir/${it.name}" }
        }
        knownDeviceDomainFiles.forEach {
            assertTrue("Expected device domain source $it to be scanned", it in deviceFiles)
        }

        val violations = deviceFiles.flatMap { relative ->
            val file = File(mainSrc, relative)
            val siblings = file.parentFile.listFiles { f -> f.isFile && f.extension == "kt" && f != file }
                .orEmpty()
                .associate { it.name to it.readText() }
            domainViolations(relative, file.readText(), siblings)
        }

        assertTrue(
            "Device events must not go through EventCoordinator, the Room event reader or " +
                "writer, Room attendee data, scheduling or sync. Found:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `domain check catches every way of reaching the forbidden code`() {
        fun hits(source: String) = domainViolations("Probe.kt", source, emptyMap())

        listOf(
            "import org.onekash.kashcal.domain.coordinator.EventCoordinator",
            "import org.onekash.kashcal.domain.coordinator.EventCoordinator as C",
            "import org.onekash.kashcal.domain.writer.EventWriter as W",
            "import org.onekash.kashcal.domain.reader.EventReader",
            "import org.onekash.icaldav.scheduling.ITipBuilder",
            "import org.onekash.kashcal.sync.push.PushStrategy",
            "import org.onekash.kashcal.sync.*",
            "import org.onekash.kashcal.data.db.entity.Attendee",
            "import org.onekash.kashcal.data.db.dao.AttendeesDao",
            "import androidx.room.withTransaction",
            // Two imports on one line, and an import split before a dot.
            "import kotlin.text.Regex; import org.onekash.kashcal.sync.scheduler.SyncScheduler",
            "import org.onekash.kashcal\n    .sync.scheduler.SyncScheduler",
            // Same-package use needs no import at all.
            "package org.onekash.kashcal.domain.writer\nclass X(private val w: EventWriter)",
            "package org.onekash.kashcal.domain.reader\nclass X(private val r: EventReader)",
            "package org.onekash.kashcal.domain.writer; class X(private val w: EventWriter)",
            // Fully-qualified names in code, including behind a leading comment.
            "val k = org.onekash.kashcal.sync.scheduler.SyncScheduler::class",
            "/* note */ val k = org.onekash.icaldav.scheduling.ITipBuilder::class",
            "val k = \"${'$'}{org.onekash.kashcal.domain.coordinator.EventCoordinator::class}\"",
        ).forEach { assertEquals("Domain check missed: $it", 1, hits(it).size) }
    }

    @Test
    fun `domain check follows same-package helpers to what they depend on`() {
        val siblings = mapOf(
            "IcsExporter.kt" to "package org.onekash.kashcal.util\n" +
                "import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper\n" +
                "class IcsExporter",
            "Relay.kt" to "package org.onekash.kashcal.util\nfun relay() = IcsExporter()",
            "Clean.kt" to "package org.onekash.kashcal.util\nfun computeSomething() = 1",
            "Hidden.kt" to "package org.onekash.kashcal.util\n" +
                "import org.onekash.kashcal.sync.X\nprivate fun computeSomething() = 2",
        )

        val direct = domainViolations(
            "util/DeviceProbe.kt",
            "package org.onekash.kashcal.util\nclass DeviceProbe(private val e: IcsExporter)",
            siblings,
        )
        assertEquals(1, direct.size)
        assertTrue(direct.single().startsWith("IcsExporter.kt:2"))

        val twoHops = domainViolations(
            "util/DeviceProbe.kt",
            "package org.onekash.kashcal.util\nval x = relay()",
            siblings,
        )
        assertEquals("reached through Relay.kt", 1, twoHops.size)

        val clean = domainViolations(
            "util/DeviceProbe.kt",
            "package org.onekash.kashcal.util\nval x = computeSomething()",
            siblings,
        )
        assertTrue("a private declaration elsewhere isn't what the device file uses: $clean", clean.isEmpty())
    }

    @Test
    fun `domain check does not flag the device classes, comments or strings`() {
        fun hits(source: String) = domainViolations("Probe.kt", source, emptyMap())

        listOf(
            "import org.onekash.kashcal.domain.writer.DeviceEventWriter",
            "import org.onekash.kashcal.domain.writer.DeviceEventDraft",
            "import org.onekash.kashcal.domain.reader.DeviceEventReader",
            "import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository",
            "import org.onekash.kashcal.data.db.entity.Event",
            "import org.onekash.kashcal.data.db.entity.AttendeeRole",
            "import kotlinx.coroutines.sync.Mutex",
            "package org.onekash.kashcal.domain.writer",
            "class DeviceEventWriter(private val draft: DeviceEventDraft, val s: DeviceEventSaveResult)",
            "/** The device counterpart of [EventWriter] and [EventReader]. */ class Y",
            "// goes through EventCoordinator? No.",
            "val s = \"EventCoordinator\"",
        ).forEach { assertEquals("Domain check wrongly flagged: $it", 0, hits(it).size) }
    }

    /**
     * Checks that the forbidden-fragment matcher flags known scheduling imports. Without it, a
     * refactor that broke the matcher (e.g. renamed packages) would silently turn the firewall
     * into a no-op that always passes.
     */
    @Test
    fun `matcher flags a known scheduling import`() {
        val knownBad = listOf(
            "import org.onekash.kashcal.domain.coordinator.EventCoordinator",
            "import org.onekash.kashcal.sync.push.PushStrategy",
            "import org.onekash.kashcal.data.db.entity.Attendee",
        )
        knownBad.forEach { line ->
            assertTrue(
                "Firewall matcher failed to flag a real violation: $line",
                forbiddenImportFragments.any { line.contains(it) }
            )
        }
    }
}
