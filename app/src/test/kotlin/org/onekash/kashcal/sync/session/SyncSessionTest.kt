package org.onekash.kashcal.sync.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SyncSession status determination and computed properties.
 */
class SyncSessionTest {

    private fun createSession(
        errorType: ErrorType? = null,
        skippedParseError: Int = 0,
        eventsWritten: Int = 0,
        eventsUpdated: Int = 0,
        eventsDeleted: Int = 0,
        fallbackUsed: Boolean = false,
        fetchFailedCount: Int = 0,
        // Push statistics
        eventsPushedCreated: Int = 0,
        eventsPushedUpdated: Int = 0,
        eventsPushedDeleted: Int = 0
    ) = SyncSession(
        calendarId = 1L,
        calendarName = "Test Calendar",
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.FOREGROUND_PULL_TO_REFRESH,
        durationMs = 1000L,
        hrefsReported = 10,
        eventsFetched = 10,
        eventsWritten = eventsWritten,
        eventsUpdated = eventsUpdated,
        eventsDeleted = eventsDeleted,
        eventsPushedCreated = eventsPushedCreated,
        eventsPushedUpdated = eventsPushedUpdated,
        eventsPushedDeleted = eventsPushedDeleted,
        skippedParseError = skippedParseError,
        errorType = errorType,
        fallbackUsed = fallbackUsed,
        fetchFailedCount = fetchFailedCount
    )

    // Status determination tests

    @Test
    fun `status is FAILED when errorType is set`() {
        val session = createSession(errorType = ErrorType.NETWORK)
        assertEquals(SyncStatus.FAILED, session.status)
    }

    @Test
    fun `status is PARTIAL when parse errors exist`() {
        val session = createSession(skippedParseError = 3)
        assertEquals(SyncStatus.PARTIAL, session.status)
    }

    @Test
    fun `status is SUCCESS when no errors and no parse failures`() {
        val session = createSession()
        assertEquals(SyncStatus.SUCCESS, session.status)
    }

    @Test
    fun `status is FAILED even with parse errors if errorType is set`() {
        // errorType takes priority over parse failures
        val session = createSession(
            errorType = ErrorType.AUTH,
            skippedParseError = 5
        )
        assertEquals(SyncStatus.FAILED, session.status)
    }

    @Test
    fun `status is SUCCESS when fallback used but no parse errors`() {
        // Fallback is transparent - doesn't affect status
        val session = createSession(fallbackUsed = true, fetchFailedCount = 2)
        assertEquals(SyncStatus.SUCCESS, session.status)
    }

    // totalChanges tests

    @Test
    fun `totalChanges sums written, updated, deleted`() {
        val session = createSession(
            eventsWritten = 5,
            eventsUpdated = 3,
            eventsDeleted = 2
        )
        assertEquals(10, session.totalChanges)
    }

    @Test
    fun `totalChanges is zero when no changes`() {
        val session = createSession()
        assertEquals(0, session.totalChanges)
    }

    // hasChanges tests

    @Test
    fun `hasChanges is true when totalChanges greater than zero`() {
        val session = createSession(eventsWritten = 1)
        assertTrue(session.hasChanges)
    }

    @Test
    fun `hasChanges is false when no changes`() {
        val session = createSession()
        assertFalse(session.hasChanges)
    }

    // hasParseFailures tests

    @Test
    fun `hasParseFailures is true when skippedParseError greater than zero`() {
        val session = createSession(skippedParseError = 1)
        assertTrue(session.hasParseFailures)
    }

    @Test
    fun `hasParseFailures is false when no parse errors`() {
        val session = createSession()
        assertFalse(session.hasParseFailures)
    }

    // Edge cases

    @Test
    fun `all error types result in FAILED status`() {
        ErrorType.entries.forEach { errorType ->
            val session = createSession(errorType = errorType)
            assertEquals("ErrorType $errorType should result in FAILED", SyncStatus.FAILED, session.status)
        }
    }

    // Push statistics tests

    @Test
    fun `totalPushed sums created, updated, deleted`() {
        val session = createSession(
            eventsPushedCreated = 2,
            eventsPushedUpdated = 3,
            eventsPushedDeleted = 1
        )
        assertEquals(6, session.totalPushed)
    }

    @Test
    fun `totalPushed is zero when no push stats`() {
        val session = createSession()
        assertEquals(0, session.totalPushed)
    }

    @Test
    fun `hasPushChanges is true when push stats greater than zero`() {
        val session = createSession(eventsPushedCreated = 1)
        assertTrue(session.hasPushChanges)
    }

    @Test
    fun `hasPushChanges is false when no push stats`() {
        val session = createSession()
        assertFalse(session.hasPushChanges)
    }

    @Test
    fun `hasAnyChanges is true when only push changes`() {
        val session = createSession(eventsPushedCreated = 1)
        assertTrue(session.hasAnyChanges)
    }

    @Test
    fun `hasAnyChanges is true when only pull changes`() {
        val session = createSession(eventsWritten = 1)
        assertTrue(session.hasAnyChanges)
    }

    @Test
    fun `hasAnyChanges is true when both push and pull changes`() {
        val session = createSession(
            eventsPushedCreated = 1,
            eventsWritten = 2
        )
        assertTrue(session.hasAnyChanges)
    }

    @Test
    fun `hasAnyChanges is false when no changes`() {
        val session = createSession()
        assertFalse(session.hasAnyChanges)
    }

    // Backward compatibility tests

    @Test
    fun `hasChanges still works for pull-only (backward compat)`() {
        val session = createSession(eventsWritten = 1)
        assertTrue(session.hasChanges)  // Original property
        assertTrue(session.hasPullChanges)  // New alias
    }

    @Test
    fun `totalChanges still works for pull-only (backward compat)`() {
        val session = createSession(eventsWritten = 5, eventsUpdated = 3, eventsDeleted = 2)
        assertEquals(10, session.totalChanges)  // Original property
        assertEquals(10, session.totalPullChanges)  // New alias
    }
}
