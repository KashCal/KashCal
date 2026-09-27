package org.onekash.kashcal.sync.quirks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Checks that the reserved-collection skip filters skip only a subset of what release
 * v2026.08.08-3 skipped, so they can reveal a wrongly hidden collection but never hide one a
 * user saw.
 *
 * The reserved-word filter (`shouldSkipCalendar`, `shouldSkipAddressBook`) is redundancy on top
 * of the positive `<calendar>` or `<addressbook>` resourcetype gate: it runs only on collections
 * that carry that resourcetype, and a skip drops the collection unconditionally. Its only
 * failure mode is hiding a real collection, a user-facing regression.
 *
 * The v2026.08.08-3 predicates are transcribed verbatim below as reference oracles. A corpus of
 * (href, displayName) pairs (real collection shapes, reserved words as whole segments and as
 * substrings, reserved display names, edge cases) runs through the oracle and the production
 * predicate, asserting for every input:
 *
 *     currentSkips(x)  ⇒  shippedSkips(x)
 *
 * Each corpus test also asserts the subset is strict (some input the oracle skips is kept now),
 * so the corpus exercises the difference and can't pass on equality.
 */
class ReservedCollectionFilterMonotonicityTest {

    private val defaultQuirks = DefaultQuirks("https://dav.example.test")
    private val icloudQuirks = ICloudQuirks()
    private val cardDavQuirks = DefaultCardDavQuirks(serverBaseUrl = "https://dav.example.test/")

    // ---- Reference oracles: the predicates shipped in v2026.08.08-3 ----
    // Transcribed verbatim from commit 1cf0aefc6. Don't "fix" these to match current
    // behavior: they are the baseline the subset property is checked against.

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
        // Reserved words as whole segments (skipped by both).
        "/calendars/user/inbox/",
        "/calendars/user/outbox/",
        "/calendars/user/notification/",
        "/calendars/user/notifications/",
        "/addressbooks/alice/inbox/",
        "/addressbooks/alice/notifications/",
        // Reserved words as substrings of a real segment: the oracle hides these and the
        // current filter keeps them, the intended reveal.
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
        // Concrete shapes the oracle hid and the current filter keeps.
        assertTrue(shippedCardDavSkipsAddressBook("/testuser1/notifications-contacts/", "Notifications Contacts"))
        assertFalse(cardDavQuirks.shouldSkipAddressBook("/testuser1/notifications-contacts/", "Notifications Contacts"))

        assertTrue(shippedDefaultSkipsCalendar("/calendars/user/my-inbox-friends/", "My Inbox Friends"))
        assertFalse(defaultQuirks.shouldSkipCalendar("/calendars/user/my-inbox-friends/", "My Inbox Friends"))

        // The display-name-only reveal: a real events calendar the user named "Tasks".
        assertTrue(shippedDefaultSkipsCalendar("/calendars/user/todo/", "Tasks"))
        assertFalse(defaultQuirks.shouldSkipCalendar("/calendars/user/todo/", "Tasks"))
    }
}
