package org.onekash.kashcal.sync.quirks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Differential proof that the current reserved-collection skip filters can only ever
 * skip a SUBSET of what the last publicly-released version skipped — so tightening the
 * filter can only REVEAL collections that were wrongly hidden, never newly hide one a
 * user currently sees.
 *
 * The reserved-word skip filter (`shouldSkipCalendar` / `shouldSkipAddressBook`) is pure
 * redundancy layered on top of the positive `<calendar>` / `<addressbook>` resourcetype
 * gate. It runs only on collections that already carry that resourcetype, and its result
 * drops the collection UNCONDITIONALLY. So its only failure mode is a false-drop — hiding
 * a real collection — which is exactly a user-facing regression. This test pins that the
 * current filter never introduces such a regression relative to what shipped.
 *
 * Method: the last publicly-released predicates (release v2026.08.08-3) are transcribed
 * verbatim below as reference oracles. A broad corpus of (href, displayName) pairs — real
 * collection shapes, reserved words as whole segments and as substrings, reserved display
 * names, and adversarial edges — is run through both the reference oracle and the current
 * production predicate. The invariant asserted for every input:
 *
 *     currentSkips(x)  ⇒  shippedSkips(x)
 *
 * i.e. the current skip-set is a subset of the shipped skip-set. A violation means the
 * current code hides a collection the shipped code showed — a regression. The test also
 * records that the current filter is a STRICT subset (it skips strictly fewer inputs), so
 * the corpus actually exercises the difference rather than trivially passing on equality.
 */
class ReservedCollectionFilterMonotonicityTest {

    private val defaultQuirks = DefaultQuirks("https://dav.example.test")
    private val icloudQuirks = ICloudQuirks()
    private val cardDavQuirks = DefaultCardDavQuirks(serverBaseUrl = "https://dav.example.test/")

    // ---- Reference oracles: the predicates as they shipped in release v2026.08.08-3 ----
    // Transcribed verbatim from commit 1cf0aefc6. Do NOT "fix" these to match current
    // behavior — they are the historical baseline the subset property is proven against.

    private fun shippedDefaultSkipsCalendar(href: String, displayName: String?): Boolean {
        val hrefLower = href.lowercase()
        val nameLower = displayName?.lowercase().orEmpty()
        return hrefLower.contains("inbox") ||
            hrefLower.contains("outbox") ||
            hrefLower.contains("notification") ||
            hrefLower.endsWith("/tasks/") ||
            nameLower == "tasks" ||
            nameLower == "reminders"
    }

    private fun shippedICloudSkipsCalendar(href: String, displayName: String?): Boolean {
        val hrefLower = href.lowercase()
        val nameLower = displayName?.lowercase().orEmpty()
        return hrefLower.contains("inbox") ||
            hrefLower.contains("outbox") ||
            hrefLower.contains("notification") ||
            nameLower.contains("tasks") ||
            nameLower.contains("reminders")
    }

    private fun shippedCardDavSkipsAddressBook(href: String, displayName: String?): Boolean {
        val hrefLower = href.lowercase()
        val nameLower = displayName?.lowercase().orEmpty()
        return hrefLower.contains("inbox") ||
            hrefLower.contains("outbox") ||
            hrefLower.contains("notification") ||
            nameLower == "inbox" ||
            nameLower == "notifications"
    }

    // ---- Corpus: whole-segment reserved words, substrings, reserved display names, edges ----

    private val hrefs = listOf(
        // Real user collections (must be kept by both).
        "/calendars/user/personal/",
        "/calendars/user/work/",
        "/calendars/user/family-events/",
        "/addressbooks/alice/default/",
        // Reserved words as WHOLE segments (skipped by both).
        "/calendars/user/inbox/",
        "/calendars/user/outbox/",
        "/calendars/user/notification/",
        "/calendars/user/notifications/",
        "/addressbooks/alice/inbox/",
        "/addressbooks/alice/notifications/",
        // Reserved words as SUBSTRINGS of a real segment (the original bug — shipped hid
        // these, current keeps them; this is the intended reveal).
        "/calendars/user/my-inbox-friends/",
        "/calendars/user/outbox-archive/",
        "/calendars/notifications-events/personal/",
        "/inboxman/calendars/work/",
        "/testuser1/notifications-contacts/",
        "/testuser1/my-inbox-friends/",
        "/inbox-user/contacts/",
        // Terminal `tasks` forms.
        "/calendars/user/tasks/",
        "/calendars/user/tasks",
        "/calendars/tasks/personal/",
        "/calendars/user/mytasks/",
        // Reserved word without trailing slash (terminal segment).
        "/calendars/user/inbox",
        "/calendars/user/outbox",
        // Case variation.
        "/calendars/user/INBOX/",
        "/calendars/user/Tasks/",
    )

    private val displayNames = listOf(
        null,
        "Personal",
        "Work Calendar",
        "Tasks",
        "tasks",
        "Reminders",
        "reminders",
        "Household tasks list",
        "Reminders from Mom",
        "Inbox",
        "Notifications",
        "My Inbox Friends",
        "Notifications Contacts",
    )

    @Test
    fun `generic CalDAV filter never hides a calendar the last release showed`() {
        var strictlyFewer = 0
        for (href in hrefs) {
            for (name in displayNames) {
                val shipped = shippedDefaultSkipsCalendar(href, name)
                val current = defaultQuirks.shouldSkipCalendar(href, name)
                assertTrue(
                    "REGRESSION: generic CalDAV now hides a calendar the last release surfaced: " +
                        "href='$href' name='$name' (current skips, shipped kept)",
                    !current || shipped,
                )
                if (shipped && !current) strictlyFewer++
            }
        }
        assertTrue(
            "corpus never exercises the difference — the subset property passes trivially",
            strictlyFewer > 0,
        )
    }

    @Test
    fun `iCloud CalDAV filter never hides a calendar the last release showed`() {
        var strictlyFewer = 0
        for (href in hrefs) {
            for (name in displayNames) {
                val shipped = shippedICloudSkipsCalendar(href, name)
                val current = icloudQuirks.shouldSkipCalendar(href, name)
                assertTrue(
                    "REGRESSION: iCloud CalDAV now hides a calendar the last release surfaced: " +
                        "href='$href' name='$name' (current skips, shipped kept)",
                    !current || shipped,
                )
                if (shipped && !current) strictlyFewer++
            }
        }
        assertTrue(
            "corpus never exercises the difference — the subset property passes trivially",
            strictlyFewer > 0,
        )
    }

    @Test
    fun `CardDAV filter never hides an address book the last release showed`() {
        var strictlyFewer = 0
        for (href in hrefs) {
            for (name in displayNames) {
                val shipped = shippedCardDavSkipsAddressBook(href, name)
                val current = cardDavQuirks.shouldSkipAddressBook(href, name)
                assertTrue(
                    "REGRESSION: CardDAV now hides an address book the last release surfaced: " +
                        "href='$href' name='$name' (current skips, shipped kept)",
                    !current || shipped,
                )
                if (shipped && !current) strictlyFewer++
            }
        }
        assertTrue(
            "corpus never exercises the difference — the subset property passes trivially",
            strictlyFewer > 0,
        )
    }

    @Test
    fun `the reveal is real - a substring-in-segment collection the last release hid is now kept`() {
        // Concrete anchors for the user-facing win: the exact shapes the original bug hid.
        assertTrue(shippedCardDavSkipsAddressBook("/testuser1/notifications-contacts/", "Notifications Contacts"))
        assertFalse(cardDavQuirks.shouldSkipAddressBook("/testuser1/notifications-contacts/", "Notifications Contacts"))

        assertTrue(shippedDefaultSkipsCalendar("/calendars/user/my-inbox-friends/", "My Inbox Friends"))
        assertFalse(defaultQuirks.shouldSkipCalendar("/calendars/user/my-inbox-friends/", "My Inbox Friends"))

        // And the display-name-only reveal: a real events calendar the user named "Tasks".
        assertTrue(shippedDefaultSkipsCalendar("/calendars/user/todo/", "Tasks"))
        assertFalse(defaultQuirks.shouldSkipCalendar("/calendars/user/todo/", "Tasks"))
    }
}
