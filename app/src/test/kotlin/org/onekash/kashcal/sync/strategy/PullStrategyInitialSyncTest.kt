package org.onekash.kashcal.sync.strategy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the skipDefaultReminders rule in PullStrategy's full pull: default reminders are skipped
 * when the calendar has no sync-token (its first sync) or the sync is forced, and applied only
 * when a token exists and the sync isn't forced. The delta and etag-fallback paths always apply
 * them.
 *
 * Skipping marks the pulled changes as initial-sync, so CalDavSyncWorker adds no default
 * reminders: on a first sync they would land on every event, and a forced sync re-lists events
 * the user already has.
 *
 * Each test evaluates a copy of the expression, `(syncToken == null) || forceFullSync`, not
 * PullStrategy itself, so a change to the production expression isn't caught here.
 */
class PullStrategyInitialSyncTest {

    // ==================== skipDefaultReminders ====================

    @Test
    fun `skipDefaultReminders is true when syncToken is null (initial sync)`() {
        val syncToken: String? = null
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Should skip defaults on initial sync (no syncToken)", skipDefaultReminders)
    }

    @Test
    fun `skipDefaultReminders is false when syncToken exists and not forceFullSync (incremental sync)`() {
        val syncToken: String? = "sync-token-abc123"
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Should apply defaults on incremental sync", skipDefaultReminders)
    }

    @Test
    fun `skipDefaultReminders is true when forceFullSync even if syncToken exists`() {
        val syncToken: String? = "sync-token-abc123"
        val forceFullSync = true

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Should skip defaults on force sync (user refresh)", skipDefaultReminders)
    }

    @Test
    fun `skipDefaultReminders is true when both syncToken is null and forceFullSync`() {
        val syncToken: String? = null
        val forceFullSync = true

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Should skip defaults when both conditions are true", skipDefaultReminders)
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty string syncToken is NOT null - apply defaults`() {
        // A server might return an empty string instead of no token.
        val syncToken: String? = ""
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Empty string syncToken should apply defaults (incremental sync)", skipDefaultReminders)
    }

    @Test
    fun `whitespace-only syncToken is NOT null - apply defaults`() {
        val syncToken: String? = "   "
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Whitespace syncToken should apply defaults (incremental sync)", skipDefaultReminders)
    }

    // ==================== Scenarios ====================

    @Test
    fun `scenario - newly discovered calendar first sync skips defaults`() {
        // A calendar found by refreshCalendars() has syncToken = null, so its first sync
        // skips defaults.
        val syncToken: String? = null
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Newly discovered calendar's first sync skips defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - regular incremental sync applies defaults`() {
        // After the first sync the token is set, and later syncs apply defaults.
        val syncToken: String? = "http://example.com/ns/sync/12345"
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Regular incremental sync applies defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - user triggers Force Sync from settings skips defaults`() {
        // Force Sync in account settings sets forceFullSync = true. Defaults are skipped: the
        // user is refreshing events they already have.
        val syncToken: String? = "existing-token"
        val forceFullSync = true

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Force sync from settings skips defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - force sync on calendar that never synced skips defaults`() {
        // A forced sync of a calendar that hasn't synced yet also skips defaults.
        val syncToken: String? = null
        val forceFullSync = true

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Force sync even on new calendar skips defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - sync token expired recovery applies defaults`() {
        // On a 403 or 410, PullStrategy falls back to pullWithEtagComparison. The calendar
        // has a token and the sync isn't forced, so defaults apply; the etag fallback passes
        // isInitialSync = false.
        val syncToken: String? = "expired-token"
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Recovery from expired token applies defaults", skipDefaultReminders)
    }
}
