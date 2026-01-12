package org.onekash.kashcal.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SyncStatus enum.
 *
 * Ensures enum values and ordinals remain stable for database compatibility.
 */
class SyncStatusTest {

    @Test
    fun `enum has exactly 4 values`() {
        assertEquals(4, SyncStatus.entries.size)
    }

    @Test
    fun `SYNCED value exists and has ordinal 0`() {
        assertEquals(0, SyncStatus.SYNCED.ordinal)
        assertEquals("SYNCED", SyncStatus.SYNCED.name)
    }

    @Test
    fun `PENDING_CREATE value exists and has ordinal 1`() {
        assertEquals(1, SyncStatus.PENDING_CREATE.ordinal)
        assertEquals("PENDING_CREATE", SyncStatus.PENDING_CREATE.name)
    }

    @Test
    fun `PENDING_UPDATE value exists and has ordinal 2`() {
        assertEquals(2, SyncStatus.PENDING_UPDATE.ordinal)
        assertEquals("PENDING_UPDATE", SyncStatus.PENDING_UPDATE.name)
    }

    @Test
    fun `PENDING_DELETE value exists and has ordinal 3`() {
        assertEquals(3, SyncStatus.PENDING_DELETE.ordinal)
        assertEquals("PENDING_DELETE", SyncStatus.PENDING_DELETE.name)
    }

    @Test
    fun `valueOf returns correct enum for valid names`() {
        assertEquals(SyncStatus.SYNCED, SyncStatus.valueOf("SYNCED"))
        assertEquals(SyncStatus.PENDING_CREATE, SyncStatus.valueOf("PENDING_CREATE"))
        assertEquals(SyncStatus.PENDING_UPDATE, SyncStatus.valueOf("PENDING_UPDATE"))
        assertEquals(SyncStatus.PENDING_DELETE, SyncStatus.valueOf("PENDING_DELETE"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `valueOf throws for invalid name`() {
        SyncStatus.valueOf("INVALID")
    }

    @Test
    fun `entries returns all values in ordinal order`() {
        val entries = SyncStatus.entries
        assertEquals(SyncStatus.SYNCED, entries[0])
        assertEquals(SyncStatus.PENDING_CREATE, entries[1])
        assertEquals(SyncStatus.PENDING_UPDATE, entries[2])
        assertEquals(SyncStatus.PENDING_DELETE, entries[3])
    }

    @Test
    fun `all pending statuses are not SYNCED`() {
        val pendingStatuses = listOf(
            SyncStatus.PENDING_CREATE,
            SyncStatus.PENDING_UPDATE,
            SyncStatus.PENDING_DELETE
        )
        pendingStatuses.forEach { status ->
            assertTrue("$status should not be SYNCED", status != SyncStatus.SYNCED)
        }
    }
}
