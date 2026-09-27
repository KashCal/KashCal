package org.onekash.kashcal.sync.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [SyncSession.status] and the computed change, push, parse-failure and warning properties.
 */
class SyncSessionTest {

    private fun createSession(
        errorType: ErrorType? = null,
        skippedParseError: Int = 0,
        eventsWritten: Int = 0,
        eventsUpdated: Int = 0,
        eventsDeleted: Int = 0,
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
        errorType = errorType
    )

    // Status

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
        // errorType wins over parse failures.
        val session = createSession(
            errorType = ErrorType.AUTH,
            skippedParseError = 5
        )
        assertEquals(SyncStatus.FAILED, session.status)
    }

    // totalChanges

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

    // hasChanges

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

    // hasParseFailures

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

    // Push statistics

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

    // Warnings

    @Test
    fun `hasWarnings is false when warnings is null (default)`() {
        val session = createSession()
        assertFalse(session.hasWarnings)
    }

    @Test
    fun `hasWarnings is false when warnings is empty list`() {
        val session = createSession().copy(warnings = emptyList())
        assertFalse(session.hasWarnings)
    }

    @Test
    fun `hasWarnings is true when warnings list is not empty`() {
        val session = createSession().copy(warnings = listOf("Parse error for event.ics"))
        assertTrue(session.hasWarnings)
    }

    // Pull aliases: totalPullChanges and hasPullChanges match totalChanges and hasChanges

    @Test
    fun `hasChanges still works for pull-only (backward compat)`() {
        val session = createSession(eventsWritten = 1)
        assertTrue(session.hasChanges)
        assertTrue(session.hasPullChanges)  // alias
    }

    @Test
    fun `totalChanges still works for pull-only (backward compat)`() {
        val session = createSession(eventsWritten = 5, eventsUpdated = 3, eventsDeleted = 2)
        assertEquals(10, session.totalChanges)
        assertEquals(10, session.totalPullChanges)  // alias
    }
}
