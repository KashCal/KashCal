package org.onekash.kashcal.sync.carddav

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the CardDAV contact-sync stack and the CalDAV calendar-sync stack isolated at the source
 * level.
 *
 * Contact sync ships its own WebDAV client (`OkHttpCardDavClient`) instead of extracting a shared
 * base from the CalDAV client. The duplicated WebDAV-verb code buys a guarantee: a bug in the
 * contact-sync path can't reach calendar sync, and calendar sync can't grow a dependency on
 * contact sync. That holds only while neither side imports the other's protocol-specific code,
 * so this test fails the day someone wires the two together.
 *
 * The boundary is drawn around protocol-specific symbols, not whole packages, so generic
 * infrastructure stays shared: `DigestAuthenticator` (in the same `sync.client` package), the
 * `CalDavXmlParser` multistatus, href and etag skeleton (`sync.parser`), the scheduler and the
 * credential store. Neither side may import the other protocol's client, strategy or engine.
 *
 * Implemented as a source scan (no ArchUnit or Konsist on the classpath), like
 * `DevicePathFirewallTest` and `ContactsProviderWriteBoundaryTest`.
 */
class CardDavCalDavIsolationTest {

    private companion object {
        /** Source-package path fragments that hold the contact-sync stack. */
        val CONTACT_SYNC_PACKAGES = listOf(
            "org/onekash/kashcal/sync/carddav",
            "org/onekash/kashcal/sync/contacts",
        )

        /**
         * CalDAV client, strategy and engine symbols the contact-sync stack may never import,
         * matched against `import` lines only. `.sync.client.CalDavClient` also matches
         * `CalDavClientFactory` as a prefix. `DigestAuthenticator` and `CalDavXmlParser` are
         * absent on purpose so they stay shareable.
         */
        val FORBIDDEN_CALDAV_IMPORTS = listOf(
            ".sync.client.CalDavClient",       // interface + CalDavClientFactory
            ".sync.client.OkHttpCalDavClient", // concrete CalDAV transport
            ".sync.strategy.",                 // PullStrategy / PushStrategy / ConflictResolver
            ".sync.engine.",                   // CalDavSyncEngine
        )

        /**
         * Path fragments of the CalDAV client, strategy and engine source files. Any of these
         * importing a contact-sync symbol would couple calendar sync to contact sync.
         */
        val CALDAV_CORE_FILE_FRAGMENTS = listOf(
            "org/onekash/kashcal/sync/client/CalDavClient",
            "org/onekash/kashcal/sync/client/OkHttpCalDavClient",
            "org/onekash/kashcal/sync/client/CalDavClientFactory",
            "org/onekash/kashcal/sync/strategy/",
            "org/onekash/kashcal/sync/engine/",
        )

        /** Contact-sync symbols the CalDAV core may never import. */
        val FORBIDDEN_CONTACT_IMPORTS = listOf(
            ".sync.carddav.",
            ".sync.contacts.",
        )

        fun mainSourceRoot(): File {
            val relative = "src/main/kotlin"
            val candidates = listOf(File(relative), File("app/$relative"))
            return candidates.firstOrNull { it.isDirectory }
                ?: error(
                    "Could not locate the main/ source root from working dir " +
                        "'${File(".").absolutePath}'. Tried: " +
                        candidates.joinToString { it.path }
                )
        }

        fun normalize(path: String): String = path.replace(File.separatorChar, '/')

        fun importLines(file: File): List<String> =
            file.readLines().map { it.trim() }.filter { it.startsWith("import ") }
    }

    @Test
    fun `contact-sync stack imports no CalDAV client, strategy, or engine symbol`() {
        val root = mainSourceRoot()
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "Expected to scan the main/ source tree but found no .kt files under ${root.path}",
            ktFiles.isNotEmpty()
        )

        val violations = ktFiles
            .filter { file -> CONTACT_SYNC_PACKAGES.any { normalize(file.path).contains(it) } }
            .flatMap { file ->
                importLines(file)
                    .filter { line -> FORBIDDEN_CALDAV_IMPORTS.any { line.contains(it) } }
                    .map { "${normalize(file.path)}: $it" }
            }

        assertTrue(
            buildString {
                appendLine(
                    "Contact sync ships its own WebDAV client on purpose so a CardDAV bug " +
                        "cannot reach calendar sync. It must not import CalDAV's client, strategy, " +
                        "or engine (DigestAuthenticator and the generic XML parser are fine). " +
                        "Offending imports:"
                )
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun `CalDAV core imports no contact-sync symbol`() {
        val root = mainSourceRoot()
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        val violations = ktFiles
            .filter { file -> CALDAV_CORE_FILE_FRAGMENTS.any { normalize(file.path).contains(it) } }
            .flatMap { file ->
                importLines(file)
                    .filter { line -> FORBIDDEN_CONTACT_IMPORTS.any { line.contains(it) } }
                    .map { "${normalize(file.path)}: $it" }
            }

        assertTrue(
            buildString {
                appendLine(
                    "Calendar sync must not grow a dependency on the contact-sync feature — " +
                        "keeping it independent is what makes contact-sync changes unable to " +
                        "regress it. CalDAV client/strategy/engine imported a contact-sync symbol:"
                )
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty()
        )
    }

    /**
     * Checks the matcher flags known cross-stack imports in both directions. Without this, a
     * refactor that broke the matcher (renamed packages, wrong normalization) would silently
     * turn the firewall into a no-op that always passes.
     */
    @Test
    fun `matcher flags known cross-stack imports both directions`() {
        val caldavIntoContacts = listOf(
            "import org.onekash.kashcal.sync.client.CalDavClient",
            "import org.onekash.kashcal.sync.client.CalDavClientFactory",
            "import org.onekash.kashcal.sync.client.OkHttpCalDavClient",
            "import org.onekash.kashcal.sync.strategy.PullStrategy",
            "import org.onekash.kashcal.sync.engine.CalDavSyncEngine",
        )
        caldavIntoContacts.forEach { line ->
            assertTrue(
                "Firewall failed to flag a CalDAV import a contact-sync file must not have: $line",
                FORBIDDEN_CALDAV_IMPORTS.any { line.contains(it) }
            )
        }

        val contactsIntoCaldav = listOf(
            "import org.onekash.kashcal.sync.carddav.OkHttpCardDavClient",
            "import org.onekash.kashcal.sync.contacts.ContactPullStrategy",
        )
        contactsIntoCaldav.forEach { line ->
            assertTrue(
                "Firewall failed to flag a contact-sync import the CalDAV core must not have: $line",
                FORBIDDEN_CONTACT_IMPORTS.any { line.contains(it) }
            )
        }
    }

    /**
     * Checks the matcher doesn't flag shared generic infrastructure, or the guard would block
     * the reuse it permits and push contact sync toward duplicating auth and parser code.
     */
    @Test
    fun `matcher permits shared generic infrastructure`() {
        val allowedInContactSync = listOf(
            "import org.onekash.kashcal.sync.client.DigestAuthenticator",
            "import org.onekash.kashcal.sync.parser.CalDavXmlParser",
            "import org.onekash.kashcal.sync.scheduler.SyncScheduler",
            "import okhttp3.OkHttpClient",
        )
        allowedInContactSync.forEach { line ->
            assertTrue(
                "Shared generic infrastructure must stay importable by contact sync: $line",
                FORBIDDEN_CALDAV_IMPORTS.none { line.contains(it) }
            )
        }
    }
}
