package org.onekash.kashcal.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [SyncChange], focused on [SyncChange.isFromInitialSync], which the sync worker reads to
 * decide whether a new pulled event gets the default reminder.
 */
class SyncChangeTest {

    private fun createSyncChange(
        type: ChangeType = ChangeType.NEW,
        eventId: Long? = 1L,
        isFromInitialSync: Boolean = false
    ) = SyncChange(
        calendarId = 1L,
        eventEndTs = 0L,
        eventUid = "uid",
        type = type,
        eventId = eventId,
        eventTitle = "Test Event",
        eventStartTs = System.currentTimeMillis(),
        isAllDay = false,
        isRecurring = false,
        isFromInitialSync = isFromInitialSync
    )

    @Test
    fun `isFromInitialSync defaults to false`() {
        val change = SyncChange(
            calendarId = 1L,
            eventEndTs = 0L,
            eventUid = "uid",
            type = ChangeType.NEW,
            eventId = 1L,
            eventTitle = "Test Event",
            eventStartTs = System.currentTimeMillis(),
            isAllDay = false,
            isRecurring = false
            // isFromInitialSync omitted, so its default applies
        )

        assertFalse(change.isFromInitialSync)
    }

    @Test
    fun `isFromInitialSync can be set to true`() {
        val change = createSyncChange(isFromInitialSync = true)

        assertTrue(change.isFromInitialSync)
    }

    @Test
    fun `isFromInitialSync can be set to false explicitly`() {
        val change = createSyncChange(isFromInitialSync = false)

        assertFalse(change.isFromInitialSync)
    }

    @Test
    fun `isFromInitialSync is preserved in copy`() {
        val original = createSyncChange(isFromInitialSync = true)

        val copied = original.copy(eventTitle = "Modified Title")

        assertTrue(copied.isFromInitialSync)
        assertEquals("Modified Title", copied.eventTitle)
    }

    @Test
    fun `isFromInitialSync can be changed in copy`() {
        val original = createSyncChange(isFromInitialSync = true)

        val copied = original.copy(isFromInitialSync = false)

        assertFalse(copied.isFromInitialSync)
    }

    @Test
    fun `NEW change type with isFromInitialSync true represents initial sync event`() {
        val change = createSyncChange(
            type = ChangeType.NEW,
            isFromInitialSync = true
        )

        assertEquals(ChangeType.NEW, change.type)
        assertTrue(change.isFromInitialSync)
    }

    @Test
    fun `NEW change type with isFromInitialSync false represents incremental sync event`() {
        val change = createSyncChange(
            type = ChangeType.NEW,
            isFromInitialSync = false
        )

        assertEquals(ChangeType.NEW, change.type)
        assertFalse(change.isFromInitialSync)
    }

    @Test
    fun `MODIFIED change type preserves isFromInitialSync`() {
        val change = createSyncChange(
            type = ChangeType.MODIFIED,
            isFromInitialSync = false
        )

        assertEquals(ChangeType.MODIFIED, change.type)
        assertFalse(change.isFromInitialSync)
    }

    @Test
    fun `DELETED change type preserves isFromInitialSync`() {
        val change = createSyncChange(
            type = ChangeType.DELETED,
            eventId = null, // Deleted events have null eventId
            isFromInitialSync = false
        )

        assertEquals(ChangeType.DELETED, change.type)
        assertFalse(change.isFromInitialSync)
    }
}
