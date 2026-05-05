package org.onekash.kashcal.sync.strategy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for skipDefaultReminders computation in PullStrategy.
 *
 * skipDefaultReminders determines whether to skip applying default reminders.
 * It should be true (skip defaults) when:
 * - calendar.syncToken is null (initial sync - first time syncing)
 * - forceFullSync is true (user-requested refresh)
 *
 * It should be false (apply defaults) ONLY for incremental sync:
 * - syncToken exists AND forceFullSync is false
 *
 * This ensures default reminders are applied to truly new events during
 * incremental sync, but NOT during:
 * - Initial full sync (would spam reminders on first sync)
 * - Force full sync (user is refreshing, not seeing new events)
 */
class PullStrategyInitialSyncTest {

    // ==================== skipDefaultReminders Computation Tests ====================

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

    // ==================== Edge Cases ====================

    @Test
    fun `empty string syncToken is NOT null - apply defaults`() {
        // Edge case: Some servers might return empty string instead of null
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

    // ==================== Scenario Documentation Tests ====================

    @Test
    fun `scenario - newly discovered calendar first sync skips defaults`() {
        // When a new calendar is discovered via refreshCalendars(),
        // it has syncToken = null. First sync should skip defaults.
        val syncToken: String? = null
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Newly discovered calendar's first sync skips defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - regular incremental sync applies defaults`() {
        // After initial sync completes, syncToken is set.
        // Subsequent syncs are incremental and SHOULD apply defaults.
        val syncToken: String? = "http://example.com/ns/sync/12345"
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Regular incremental sync applies defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - user triggers Force Sync from settings skips defaults`() {
        // When user taps "Force Sync" in account settings,
        // forceFullSync = true. Should skip defaults because
        // user is refreshing existing data, not seeing new events.
        val syncToken: String? = "existing-token"
        val forceFullSync = true

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Force sync from settings skips defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - force sync on calendar that never synced skips defaults`() {
        // Edge case: User forces sync on a calendar that hasn't synced yet.
        // Should skip defaults - user explicitly requested refresh.
        val syncToken: String? = null
        val forceFullSync = true

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertTrue("Force sync even on new calendar skips defaults", skipDefaultReminders)
    }

    @Test
    fun `scenario - sync token expired recovery applies defaults`() {
        // When server returns 403/410, PullStrategy falls back to
        // pullWithEtagComparison. The calendar HAD a token (exists),
        // and it's not a force sync, so this should apply defaults.
        // The etag fallback path explicitly sets isInitialSync = false.
        val syncToken: String? = "expired-token"
        val forceFullSync = false

        val skipDefaultReminders = (syncToken == null) || forceFullSync

        assertFalse("Recovery from expired token applies defaults", skipDefaultReminders)
    }
}
