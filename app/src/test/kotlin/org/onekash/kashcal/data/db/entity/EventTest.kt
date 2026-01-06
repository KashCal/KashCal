package org.onekash.kashcal.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Event entity.
 *
 * Tests computed properties and default values.
 */
class EventTest {

    private fun createEvent(
        rrule: String? = null,
        originalEventId: Long? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = Event(
        id = 1L,
        uid = "test-uid-123@example.com",
        calendarId = 1L,
        title = "Test Event",
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        dtstamp = System.currentTimeMillis(),
        rrule = rrule,
        originalEventId = originalEventId,
        syncStatus = syncStatus
    )

    // ========== Computed Property Tests ==========

    @Test
    fun `isRecurring returns true when rrule is set`() {
        val event = createEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertTrue(event.isRecurring)
    }

    @Test
    fun `isRecurring returns false when rrule is null`() {
        val event = createEvent(rrule = null)
        assertFalse(event.isRecurring)
    }

    @Test
    fun `isException returns true when originalEventId is set`() {
        val event = createEvent(originalEventId = 100L)
        assertTrue(event.isException)
    }

    @Test
    fun `isException returns false when originalEventId is null`() {
        val event = createEvent(originalEventId = null)
        assertFalse(event.isException)
    }

    @Test
    fun `needsSync returns true for PENDING_CREATE`() {
        val event = createEvent(syncStatus = SyncStatus.PENDING_CREATE)
        assertTrue(event.needsSync)
    }

    @Test
    fun `needsSync returns true for PENDING_UPDATE`() {
        val event = createEvent(syncStatus = SyncStatus.PENDING_UPDATE)
        assertTrue(event.needsSync)
    }

    @Test
    fun `needsSync returns true for PENDING_DELETE`() {
        val event = createEvent(syncStatus = SyncStatus.PENDING_DELETE)
        assertTrue(event.needsSync)
    }

    @Test
    fun `needsSync returns false for SYNCED`() {
        val event = createEvent(syncStatus = SyncStatus.SYNCED)
        assertFalse(event.needsSync)
    }

    @Test
    fun `isPendingDelete returns true only for PENDING_DELETE`() {
        assertTrue(createEvent(syncStatus = SyncStatus.PENDING_DELETE).isPendingDelete)
        assertFalse(createEvent(syncStatus = SyncStatus.SYNCED).isPendingDelete)
        assertFalse(createEvent(syncStatus = SyncStatus.PENDING_CREATE).isPendingDelete)
        assertFalse(createEvent(syncStatus = SyncStatus.PENDING_UPDATE).isPendingDelete)
    }

    // ========== Default Value Tests ==========

    @Test
    fun `default status is CONFIRMED`() {
        val event = createEvent()
        assertEquals("CONFIRMED", event.status)
    }

    @Test
    fun `default transp is OPAQUE`() {
        val event = createEvent()
        assertEquals("OPAQUE", event.transp)
    }

    @Test
    fun `default classification is PUBLIC`() {
        val event = createEvent()
        assertEquals("PUBLIC", event.classification)
    }

    @Test
    fun `default isAllDay is false`() {
        val event = createEvent()
        assertFalse(event.isAllDay)
    }

    @Test
    fun `default sequence is 0`() {
        val event = createEvent()
        assertEquals(0, event.sequence)
    }

    @Test
    fun `default syncRetryCount is 0`() {
        val event = createEvent()
        assertEquals(0, event.syncRetryCount)
    }

    @Test
    fun `default syncStatus is SYNCED`() {
        val event = Event(
            uid = "test",
            calendarId = 1L,
            title = "Test",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L
        )
        assertEquals(SyncStatus.SYNCED, event.syncStatus)
    }

    // ========== Nullable Field Tests ==========

    @Test
    fun `optional fields are null by default`() {
        val event = createEvent()
        assertNull(event.location)
        assertNull(event.description)
        assertNull(event.timezone)
        assertNull(event.organizerEmail)
        assertNull(event.organizerName)
        assertNull(event.rdate)
        assertNull(event.exdate)
        assertNull(event.duration)
        assertNull(event.reminders)
        assertNull(event.extraProperties)
        assertNull(event.caldavUrl)
        assertNull(event.etag)
        assertNull(event.lastSyncError)
        assertNull(event.localModifiedAt)
        assertNull(event.serverModifiedAt)
    }

    // ========== Copy Tests ==========

    @Test
    fun `copy preserves all values`() {
        val original = Event(
            id = 1L,
            uid = "uid",
            calendarId = 2L,
            title = "Title",
            location = "Location",
            description = "Description",
            startTs = 1000L,
            endTs = 2000L,
            timezone = "America/New_York",
            isAllDay = true,
            status = "TENTATIVE",
            transp = "TRANSPARENT",
            classification = "PRIVATE",
            organizerEmail = "org@test.com",
            organizerName = "Organizer",
            rrule = "FREQ=DAILY",
            rdate = "20241225T120000Z",
            exdate = "20241226T120000Z",
            duration = "PT1H",
            originalEventId = 10L,
            originalInstanceTime = 5000L,
            originalSyncId = "sync-id",
            reminders = listOf("-PT15M"),
            extraProperties = mapOf("X-TEST" to "value"),
            dtstamp = 3000L,
            caldavUrl = "https://caldav.example.com/event.ics",
            etag = "\"etag123\"",
            sequence = 5,
            syncStatus = SyncStatus.PENDING_UPDATE,
            lastSyncError = "Network error",
            syncRetryCount = 2,
            localModifiedAt = 4000L,
            serverModifiedAt = 3500L,
            createdAt = 100L,
            updatedAt = 200L
        )

        val copy = original.copy()
        assertEquals(original, copy)
    }

    @Test
    fun `copy with modified field creates new instance`() {
        val original = createEvent()
        val modified = original.copy(title = "New Title")

        assertEquals("Test Event", original.title)
        assertEquals("New Title", modified.title)
        assertEquals(original.id, modified.id)
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `event can be both recurring and exception`() {
        // Edge case: Exception event that also has its own RRULE
        // (rare but valid in RFC 5545)
        val event = createEvent(
            rrule = "FREQ=DAILY",
            originalEventId = 100L
        )
        assertTrue(event.isRecurring)
        assertTrue(event.isException)
    }

    @Test
    fun `empty strings are valid for text fields`() {
        val event = Event(
            uid = "",
            calendarId = 1L,
            title = "",
            location = "",
            description = "",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L
        )
        assertEquals("", event.uid)
        assertEquals("", event.title)
        assertEquals("", event.location)
        assertEquals("", event.description)
    }
}
