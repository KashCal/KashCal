package org.onekash.kashcal.sync.contacts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural firewall: only the contact-sync layer may *write* to the
 * Android Contacts Provider.
 *
 * Reading contacts is fine anywhere — the birthday/anniversary feature already
 * queries `ContactsContract` from `data/contacts/`, and attendee autocomplete
 * reads it too. Writing is the dangerous half: a stray `applyBatch` /
 * `CALLER_IS_SYNCADAPTER` write from a ViewModel or the domain layer would
 * bypass the per-account (`ACCOUNT_NAME`/`ACCOUNT_TYPE`) scoping that keeps one
 * login's pull from clobbering another login's contacts, and would give contact
 * sync a coupling to the presentation/domain layers it must never have. So this
 * guard fences the *write* surface, not the import.
 *
 * The property enforced: any source file that references `ContactsContract`
 * AND performs a provider write must live under `sync/contacts/`. It passes
 * today (no such writer exists yet) and exists to fail loudly the moment the
 * CardDAV contact-sync write path lands anywhere else — the same "guard the
 * boundary so it can't silently regress" idea as DevicePathFirewallTest and
 * UiLayerPersistenceBoundaryTest.
 *
 * Implemented as a source scan (no ArchUnit/Konsist on the classpath), matching
 * the two sibling boundary tests.
 */
class ContactsProviderWriteBoundaryTest {

    private companion object {
        /**
         * The only package allowed to hold Contacts Provider writes. Paths use
         * '/' so the check is filesystem-separator-agnostic in the assertion
         * message; matching is done against a normalized path below.
         */
        const val ALLOWED_WRITE_PACKAGE = "org/onekash/kashcal/sync/contacts"

        /** A file only counts as a Contacts write if it names the contract... */
        const val CONTACTS_CONTRACT_MARKER = "ContactsContract"

        /**
         * ...AND contains at least one of these provider-write signatures.
         * These are the batch-write markers the sync path uses; they are
         * absent from the read-only birthday/attendee query code, so those
         * files are not flagged. `bulkInsert` and the sync-adapter query flag
         * are included so a non-batch write path can't slip the fence.
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
     * Self-check: the detector must actually flag a file that both references
     * the contract and performs a write from outside the allowed package.
     * Without this, a refactor that broke the matcher (renamed markers, wrong
     * path normalization) would silently turn the firewall into a no-op that
     * always passes.
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
     * Self-check the other direction: a file under sync/contacts/ that does the
     * exact same write must NOT be flagged, or the guard would block the very
     * layer it's meant to permit.
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
