package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural firewall: the device-calendar attendee path must stay
 * disjoint from the Room/CalDAV scheduling stack.
 *
 * Why this is a test, not a comment: device-event delivery is the OS sync
 * adapter's job — KashCal only writes provider rows. The whole class of CalDAV
 * scheduling bugs (iTIP REQUEST/REPLY/CANCEL, SEQUENCE versioning, ORGANIZER
 * mailto/urn wire encoding, SCHEDULE-STATUS read-back, outbox POST) is
 * *structurally impossible* on the device path only as long as that path never
 * reaches into the scheduling/iTIP/coordinator code. This guard fails loudly
 * the day someone wires the two together, instead of the wire-protocol bugs
 * silently re-appearing on device events.
 *
 * Implemented as a source scan (no ArchUnit/Konsist on the classpath): assert
 * the device provider source imports none of the forbidden scheduling symbols.
 */
class DevicePathFirewallTest {

    private val mainSrc = File(System.getProperty("user.dir"), "src/main/kotlin")

    private val deviceProviderFile = File(
        mainSrc,
        "org/onekash/kashcal/data/calendar_provider/AndroidCalendarProviderRepository.kt"
    )

    /**
     * Import-line fragments that would mean the device write path has reached
     * into the Room/CalDAV scheduling world. Matched against `import` lines
     * only, so an unrelated identifier in a comment/string can't trip it.
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
     * Self-check: the forbidden-fragment matcher must actually flag a known
     * scheduling import. Without this, a refactor that broke the matcher (e.g.
     * renamed packages) would silently turn the firewall into a no-op that
     * always passes.
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
