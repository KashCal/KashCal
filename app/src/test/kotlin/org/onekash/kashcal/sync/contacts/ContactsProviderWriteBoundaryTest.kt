package org.onekash.kashcal.sync.contacts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when a main source file outside `sync/contacts/` writes to the Android Contacts
 * Provider.
 *
 * Reads are allowed anywhere: the birthday and anniversary feature and attendee autocomplete
 * query `ContactsContract` from `data/contacts/`. A stray `applyBatch` or
 * `CALLER_IS_SYNCADAPTER` write from a ViewModel or the domain layer would bypass the
 * per-account (`ACCOUNT_NAME`/`ACCOUNT_TYPE`) scoping that keeps one login's pull from
 * clobbering another login's contacts, and would give contact sync a coupling to those
 * layers it must never have. So the guard fences the write surface, not the import.
 *
 * A file fails when it references `ContactsContract`, contains a provider-write marker and
 * lives outside `sync/contacts/`. It is a source scan, like its sibling boundary tests
 * `DevicePathFirewallTest` and `UiLayerPersistenceBoundaryTest`, since no architecture-test
 * library is on the classpath.
 */
class ContactsProviderWriteBoundaryTest {

    private companion object {
        /**
         * The only package allowed to hold Contacts Provider writes, with '/' separators; the
         * scan matches it against a path normalized to '/'.
         */
        const val ALLOWED_WRITE_PACKAGE = "org/onekash/kashcal/sync/contacts"

        /** A file only counts as a Contacts write if it names the contract... */
        const val CONTACTS_CONTRACT_MARKER = "ContactsContract"

        /**
         * ...and contains at least one of these provider-write markers. The read-only birthday
         * and attendee query code has none, so it isn't flagged. `bulkInsert` and the
         * sync-adapter query flag are here so a non-batch write path can't slip the fence.
         */
        val WRITE_MARKERS = listOf(
            ".applyBatch(",
            "ContentProviderOperation",
            "CALLER_IS_SYNCADAPTER",
            ".bulkInsert(",
        )

        /** Resolve the main/ source root regardless of where the runner starts. */
        fun mainSourceRoot(): File {
            val relative = "src/main/kotlin"
            val candidates = listOf(
                File(relative),        // working dir = app module
                File("app/$relative"), // working dir = repo root
            )
            return candidates.firstOrNull { it.isDirectory }
                ?: error(
                    "Could not locate the main/ source root from working dir " +
                        "'${File(".").absolutePath}'. Tried: " +
                        candidates.joinToString { it.path }
                )
        }
    }

    @Test
    fun `only the contact-sync layer writes to the Contacts Provider`() {
        val root = mainSourceRoot()
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue(
            "Expected to scan the main/ source tree but found no .kt files under ${root.path}",
            ktFiles.isNotEmpty()
        )

        val violations = ktFiles.filter { file ->
            val text = file.readText()
            val referencesContacts = text.contains(CONTACTS_CONTRACT_MARKER)
            val writesProvider = WRITE_MARKERS.any { text.contains(it) }
            val normalizedPath = file.path.replace(File.separatorChar, '/')
            val inAllowedPackage = normalizedPath.contains(ALLOWED_WRITE_PACKAGE)
            referencesContacts && writesProvider && !inAllowedPackage
        }

        assertTrue(
            buildString {
                appendLine(
                    "Contacts Provider writes must be confined to sync/contacts/ so the " +
                        "per-account (ACCOUNT_NAME/ACCOUNT_TYPE) scoping holds and contact sync " +
                        "stays decoupled from the domain/UI layers. Offending files:"
                )
                violations.forEach { appendLine("  ${it.path}") }
            },
            violations.isEmpty()
        )
    }

    /**
     * Self-check: the detector flags a file outside the allowed package that references the
     * contract and writes. Without it, a refactor that broke the matcher (renamed markers,
     * wrong path normalization) would silently turn the firewall into a no-op that always
     * passes.
     */
    @Test
    fun `detector flags a contacts write outside the allowed package`() {
        val referencesContacts = "ContactsContract.RawContacts.CONTENT_URI".contains(CONTACTS_CONTRACT_MARKER)
        val writesProvider = WRITE_MARKERS.any {
            "resolver.applyBatch(ContactsContract.AUTHORITY, ops)".contains(it)
        }
        val fakeOffenderPath = "src/main/kotlin/org/onekash/kashcal/ui/viewmodels/HomeViewModel.kt"
            .replace(File.separatorChar, '/')
        val inAllowedPackage = fakeOffenderPath.contains(ALLOWED_WRITE_PACKAGE)

        assertTrue(
            "Detector failed to flag a real Contacts-write violation outside sync/contacts/",
            referencesContacts && writesProvider && !inAllowedPackage
        )
    }

    /**
     * Self-check the other direction: the same write under sync/contacts/ must not be flagged,
     * or the guard would block the layer it permits.
     */
    @Test
    fun `detector permits a contacts write inside the allowed package`() {
        val allowedPath = "src/main/kotlin/$ALLOWED_WRITE_PACKAGE/ContactsProviderRepository.kt"
            .replace(File.separatorChar, '/')
        assertTrue(
            "Detector must permit Contacts writes inside sync/contacts/",
            allowedPath.contains(ALLOWED_WRITE_PACKAGE)
        )
    }
}
