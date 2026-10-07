package org.onekash.kashcal.domain.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField

/** Tests [RecentChangeMerge]: how a later change to the same event updates its row. */
class RecentChangeMergeTest {

    private fun entry(
        type: ChangeType,
        fields: Set<ChangedField> = emptySet(),
        previousStart: Long? = null,
        start: Long = 1_000,
        title: String = "T",
        eventId: Long? = if (type == ChangeType.DELETED) null else 5,
    ) = RecentChangeEntry(
        calendarId = 1, eventUid = "u", instanceTs = 0, eventId = eventId, type = type,
        title = title, startTs = start, endTs = start + 10, isAllDay = false, isRecurring = false,
        changedFields = fields, previousStartTs = previousStart, previousIsAllDay = previousStart?.let { false }
    )

    @Test
    fun `no earlier row takes the change as is`() {
        val incoming = entry(ChangeType.MODIFIED, setOf(ChangedField.TIME), previousStart = 500)
        assertEquals(incoming, RecentChangeMerge.merge(null, incoming))
    }

    @Test
    fun `new then changed stays new with nothing to show as changed`() {
        val merged = RecentChangeMerge.merge(
            entry(ChangeType.NEW),
            entry(ChangeType.MODIFIED, setOf(ChangedField.TIME), previousStart = 1_000, start = 2_000, title = "T2")
        )
        assertEquals(ChangeType.NEW, merged.type)
        assertTrue(merged.changedFields.isEmpty())
        assertNull(merged.previousStartTs)
        assertEquals("the snapshot is the latest", "T2", merged.title)
        assertEquals(2_000L, merged.startTs)
    }

    @Test
    fun `anything then deleted is deleted`() {
        for (first in ChangeType.entries) {
            val merged = RecentChangeMerge.merge(entry(first), entry(ChangeType.DELETED))
            assertEquals("$first then DELETED", ChangeType.DELETED, merged.type)
            assertNull(merged.eventId)
        }
    }

    @Test
    fun `deleted then back takes the later type`() {
        assertEquals(ChangeType.NEW, RecentChangeMerge.merge(entry(ChangeType.DELETED), entry(ChangeType.NEW)).type)
        assertEquals(
            ChangeType.MODIFIED,
            RecentChangeMerge.merge(entry(ChangeType.DELETED), entry(ChangeType.MODIFIED, setOf(ChangedField.TITLE))).type
        )
    }

    @Test
    fun `changed twice accumulates fields and keeps the oldest previous start`() {
        val merged = RecentChangeMerge.merge(
            entry(ChangeType.MODIFIED, setOf(ChangedField.TIME), previousStart = 500, start = 1_000),
            entry(ChangeType.MODIFIED, setOf(ChangedField.TIME, ChangedField.LOCATION), previousStart = 1_000, start = 1_500)
        )
        assertEquals(ChangeType.MODIFIED, merged.type)
        assertEquals(setOf(ChangedField.TIME, ChangedField.LOCATION), merged.changedFields)
        assertEquals("the time the user last knew", 500L, merged.previousStartTs)
        assertEquals(1_500L, merged.startTs)
    }

    @Test
    fun `a later move keeps an earlier change's fields with its own previous start`() {
        val merged = RecentChangeMerge.merge(
            entry(ChangeType.MODIFIED, setOf(ChangedField.TITLE)),
            entry(ChangeType.MODIFIED, setOf(ChangedField.TIME), previousStart = 1_000, start = 1_500)
        )
        assertEquals(setOf(ChangedField.TITLE, ChangedField.TIME), merged.changedFields)
        assertEquals(1_000L, merged.previousStartTs)
    }
}
