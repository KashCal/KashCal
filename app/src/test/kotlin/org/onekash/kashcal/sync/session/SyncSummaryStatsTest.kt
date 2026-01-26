package org.onekash.kashcal.sync.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SyncSummaryStats computation.
 */
class SyncSummaryStatsTest {

    private fun createSuccessSession(
        eventsWritten: Int = 0,
        eventsUpdated: Int = 0,
        eventsDeleted: Int = 0,
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
        eventsPushedDeleted = eventsPushedDeleted
    )

    private fun createPartialSession(parseErrors: Int = 1) = SyncSession(
        calendarId = 1L,
        calendarName = "Test Calendar",
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.FOREGROUND_PULL_TO_REFRESH,
        durationMs = 1000L,
        hrefsReported = 10,
        eventsFetched = 10,
        eventsWritten = 5,
        eventsUpdated = 0,
        eventsDeleted = 0,
        skippedParseError = parseErrors
    )

    private fun createFailedSession() = SyncSession(
        calendarId = 1L,
        calendarName = "Test Calendar",
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.FOREGROUND_PULL_TO_REFRESH,
        durationMs = 1000L,
        hrefsReported = 0,
        eventsFetched = 0,
        eventsWritten = 0,
        eventsUpdated = 0,
        eventsDeleted = 0,
        errorType = ErrorType.NETWORK
    )

    // totalSyncs tests

    @Test
    fun `totalSyncs counts all sessions`() {
        val sessions = listOf(
            createSuccessSession(),
            createPartialSession(),
            createFailedSession()
        )
        val stats = computeStats(sessions)
        assertEquals(3, stats.totalSyncs)
    }

    @Test
    fun `totalSyncs is zero for empty list`() {
        val stats = computeStats(emptyList())
        assertEquals(0, stats.totalSyncs)
    }

    // totalPushed tests

    @Test
    fun `totalPushed sums push stats across sessions`() {
        val sessions = listOf(
            createSuccessSession(eventsPushedCreated = 2, eventsPushedUpdated = 1, eventsPushedDeleted = 0),
            createSuccessSession(eventsPushedCreated = 1, eventsPushedUpdated = 2, eventsPushedDeleted = 1)
        )
        val stats = computeStats(sessions)
        assertEquals(7, stats.totalPushed)
    }

    @Test
    fun `totalPushed is zero when no push stats`() {
        val sessions = listOf(
            createSuccessSession(eventsWritten = 5),
            createSuccessSession(eventsUpdated = 3)
        )
        val stats = computeStats(sessions)
        assertEquals(0, stats.totalPushed)
    }

    // totalPulled tests

    @Test
    fun `totalPulled sums pull changes across sessions`() {
        val sessions = listOf(
            createSuccessSession(eventsWritten = 5, eventsUpdated = 3, eventsDeleted = 1),
            createSuccessSession(eventsWritten = 2, eventsUpdated = 1, eventsDeleted = 0)
        )
        val stats = computeStats(sessions)
        assertEquals(12, stats.totalPulled)
    }

    @Test
    fun `totalPulled is zero when no pull changes`() {
        val sessions = listOf(
            createSuccessSession(eventsPushedCreated = 5),
            createSuccessSession(eventsPushedUpdated = 3)
        )
        val stats = computeStats(sessions)
        assertEquals(0, stats.totalPulled)
    }

    // Combined push/pull tests

    @Test
    fun `stats capture both push and pull totals`() {
        val sessions = listOf(
            createSuccessSession(
                eventsWritten = 5, eventsUpdated = 2, eventsDeleted = 1,
                eventsPushedCreated = 2, eventsPushedUpdated = 1, eventsPushedDeleted = 0
            ),
            createSuccessSession(
                eventsWritten = 3, eventsUpdated = 0, eventsDeleted = 0,
                eventsPushedCreated = 1, eventsPushedUpdated = 0, eventsPushedDeleted = 1
            )
        )
        val stats = computeStats(sessions)
        assertEquals(5, stats.totalPushed)  // 3 + 2 from first, 1 + 1 from second
        assertEquals(11, stats.totalPulled)  // 8 from first, 3 from second
    }

    // issueCount tests

    @Test
    fun `issueCount is zero when all sessions succeed`() {
        val sessions = listOf(
            createSuccessSession(),
            createSuccessSession(eventsWritten = 3)
        )
        val stats = computeStats(sessions)
        assertEquals(0, stats.issueCount)
    }

    @Test
    fun `issueCount counts sessions with parse failures`() {
        val sessions = listOf(
            createSuccessSession(),
            createPartialSession()
        )
        val stats = computeStats(sessions)
        assertEquals(1, stats.issueCount)
    }

    @Test
    fun `issueCount counts failed sessions`() {
        val sessions = listOf(
            createSuccessSession(),
            createFailedSession()
        )
        val stats = computeStats(sessions)
        assertEquals(1, stats.issueCount)
    }

    @Test
    fun `issueCount counts all non-success sessions`() {
        val sessions = listOf(
            createSuccessSession(),
            createPartialSession(),
            createFailedSession()
        )
        val stats = computeStats(sessions)
        assertEquals(2, stats.issueCount)
    }

    @Test
    fun `issueCount is zero for empty list`() {
        val stats = computeStats(emptyList())
        assertEquals(0, stats.issueCount)
    }

    // Helper function mimicking SyncSessionStore.getSummaryStats
    private fun computeStats(sessions: List<SyncSession>): SyncSummaryStats {
        return SyncSummaryStats(
            totalSyncs = sessions.size,
            totalPushed = sessions.sumOf { it.totalPushed },
            totalPulled = sessions.sumOf { it.totalPullChanges },
            issueCount = sessions.count { it.status != SyncStatus.SUCCESS }
        )
    }
}
