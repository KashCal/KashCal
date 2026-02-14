package org.onekash.kashcal.sync.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SyncSessionBuilder.
 *
 * Tests:
 * - Default build produces all-zero session
 * - Pipeline setters (hrefsReported, eventsFetched)
 * - Increment methods accumulate
 * - Push stats
 * - Skip increment methods
 * - Error and truncated fields
 * - hasMissingEvents / missingCount derived fields
 * - Fluent chaining
 */
class SyncSessionBuilderTest {

    private fun createBuilder() = SyncSessionBuilder(
        calendarId = 1L,
        calendarName = "Test Calendar",
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.FOREGROUND_MANUAL
    )

    @Test
    fun `default build produces all-zero session`() {
        val session = createBuilder().build()

        assertEquals(1L, session.calendarId)
        assertEquals("Test Calendar", session.calendarName)
        assertEquals(SyncType.INCREMENTAL, session.syncType)
        assertEquals(SyncTrigger.FOREGROUND_MANUAL, session.triggerSource)
        assertEquals(0, session.hrefsReported)
        assertEquals(0, session.eventsFetched)
        assertEquals(0, session.eventsWritten)
        assertEquals(0, session.eventsUpdated)
        assertEquals(0, session.eventsDeleted)
        assertEquals(0, session.eventsPushedCreated)
        assertEquals(0, session.eventsPushedUpdated)
        assertEquals(0, session.eventsPushedDeleted)
        assertEquals(0, session.skippedParseError)
        assertEquals(0, session.skippedPendingLocal)
        assertEquals(0, session.skippedEtagUnchanged)
        assertEquals(0, session.skippedOrphanedException)
        assertEquals(0, session.skippedConstraintError)
        assertEquals(0, session.skippedRecentlyPushed)
        assertEquals(0, session.abandonedParseErrors)
        assertTrue(session.tokenAdvanced)
        assertNull(session.errorType)
        assertNull(session.errorStage)
        assertNull(session.errorMessage)
        assertFalse(session.truncated)
        assertFalse(session.hasMissingEvents)
        assertEquals(0, session.missingCount)
    }

    @Test
    fun `pipeline setters set correct fields`() {
        val session = createBuilder()
            .setHrefsReported(50)
            .setEventsFetched(45)
            .build()

        assertEquals(50, session.hrefsReported)
        assertEquals(45, session.eventsFetched)
    }

    @Test
    fun `incrementWritten accumulates`() {
        val session = createBuilder()
            .incrementWritten()
            .incrementWritten()
            .incrementWritten()
            .build()

        assertEquals(3, session.eventsWritten)
    }

    @Test
    fun `incrementUpdated accumulates`() {
        val session = createBuilder()
            .incrementUpdated()
            .incrementUpdated()
            .build()

        assertEquals(2, session.eventsUpdated)
    }

    @Test
    fun `incrementDeleted accumulates`() {
        val session = createBuilder()
            .incrementDeleted()
            .build()

        assertEquals(1, session.eventsDeleted)
    }

    @Test
    fun `addDeleted adds bulk count`() {
        val session = createBuilder()
            .incrementDeleted()
            .addDeleted(10)
            .build()

        assertEquals(11, session.eventsDeleted)
    }

    @Test
    fun `setPushStats sets all three push fields`() {
        val session = createBuilder()
            .setPushStats(created = 3, updated = 5, deleted = 2)
            .build()

        assertEquals(3, session.eventsPushedCreated)
        assertEquals(5, session.eventsPushedUpdated)
        assertEquals(2, session.eventsPushedDeleted)
    }

    @Test
    fun `skip increment methods accumulate`() {
        val builder = createBuilder()
        repeat(2) { builder.incrementSkipParseError() }
        repeat(3) { builder.incrementSkipPendingLocal() }
        repeat(1) { builder.incrementSkipEtagUnchanged() }
        repeat(4) { builder.incrementSkipOrphanedException() }
        repeat(2) { builder.incrementSkipConstraintError() }
        repeat(5) { builder.incrementSkipRecentlyPushed() }

        val session = builder.build()
        assertEquals(2, session.skippedParseError)
        assertEquals(3, session.skippedPendingLocal)
        assertEquals(1, session.skippedEtagUnchanged)
        assertEquals(4, session.skippedOrphanedException)
        assertEquals(2, session.skippedConstraintError)
        assertEquals(5, session.skippedRecentlyPushed)
    }

    @Test
    fun `getSkippedParseError returns accumulated count`() {
        val builder = createBuilder()
        assertEquals(0, builder.getSkippedParseError())

        builder.incrementSkipParseError()
        builder.incrementSkipParseError()
        assertEquals(2, builder.getSkippedParseError())
    }

    @Test
    fun `setAbandonedParseErrors sets field`() {
        val session = createBuilder()
            .setAbandonedParseErrors(7)
            .build()

        assertEquals(7, session.abandonedParseErrors)
    }

    @Test
    fun `setTokenAdvanced false overrides default true`() {
        val session = createBuilder()
            .setTokenAdvanced(false)
            .build()

        assertFalse(session.tokenAdvanced)
    }

    @Test
    fun `setError sets errorType, errorStage, and errorMessage`() {
        val session = createBuilder()
            .setError(ErrorType.AUTH, "push", "401 Unauthorized")
            .build()

        assertEquals(ErrorType.AUTH, session.errorType)
        assertEquals("push", session.errorStage)
        assertEquals("401 Unauthorized", session.errorMessage)
    }

    @Test
    fun `setError without message leaves errorMessage null`() {
        val session = createBuilder()
            .setError(ErrorType.NETWORK, "connect")
            .build()

        assertEquals(ErrorType.NETWORK, session.errorType)
        assertEquals("connect", session.errorStage)
        assertNull(session.errorMessage)
    }

    @Test
    fun `setTruncated sets field`() {
        val session = createBuilder()
            .setTruncated(true)
            .build()

        assertTrue(session.truncated)
    }

    @Test
    fun `hasMissingEvents true when hrefsReported greater than eventsFetched`() {
        val session = createBuilder()
            .setHrefsReported(10)
            .setEventsFetched(7)
            .build()

        assertTrue(session.hasMissingEvents)
        assertEquals(3, session.missingCount)
    }

    @Test
    fun `hasMissingEvents false when counts equal`() {
        val session = createBuilder()
            .setHrefsReported(10)
            .setEventsFetched(10)
            .build()

        assertFalse(session.hasMissingEvents)
        assertEquals(0, session.missingCount)
    }

    @Test
    fun `missingCount coerced to zero when fetched exceeds reported`() {
        val session = createBuilder()
            .setHrefsReported(5)
            .setEventsFetched(8)
            .build()

        assertFalse(session.hasMissingEvents)
        assertEquals(0, session.missingCount)
    }

    @Test
    fun `fluent chaining returns same builder instance`() {
        val builder = createBuilder()
        val result = builder
            .setHrefsReported(1)
            .setEventsFetched(1)
            .incrementWritten()
            .incrementUpdated()
            .incrementDeleted()
            .addDeleted(1)
            .setPushStats(0, 0, 0)
            .incrementSkipParseError()
            .incrementSkipPendingLocal()
            .incrementSkipEtagUnchanged()
            .incrementSkipOrphanedException()
            .incrementSkipConstraintError()
            .incrementSkipRecentlyPushed()
            .setAbandonedParseErrors(0)
            .setTokenAdvanced(true)
            .setError(ErrorType.NETWORK, "test")
            .setTruncated(false)

        // All setters return the builder for chaining
        assertTrue(result === builder)
    }

    @Test
    fun `durationMs is positive`() {
        val session = createBuilder().build()
        assertTrue("durationMs should be >= 0", session.durationMs >= 0)
    }
}
