package org.onekash.kashcal.domain.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.model.SyncChange

/**
 * Tests [RecentChangeClassifier]: which of a sync's changes become Recent changes rows, and how
 * a recurring event's changes fold into one row per event or changed occurrence.
 */
class RecentChangeClassifierTest {

    private val hour = 3_600_000L
    private val seriesStart = 1_790_000_000_000L
    private val slot = seriesStart + 7 * 24 * hour

    private fun change(
        type: ChangeType,
        uid: String = "u",
        instanceTs: Long = 0,
        calendarId: Long = 1,
        fields: Set<ChangedField> = emptySet(),
        start: Long = if (instanceTs != 0L) instanceTs else seriesStart,
        previousStart: Long? = null,
        cancelled: List<Long> = emptyList(),
        recurring: Boolean = instanceTs != 0L,
        placeholderSeries: Boolean = false,
        replacedPlaceholder: Boolean = false,
        firstPull: Boolean = false,
        forced: Boolean = false,
        title: String = "Weekly sync",
        eventId: Long? = if (type == ChangeType.DELETED) null else 10,
    ) = SyncChange(
        type = type, eventId = eventId, eventTitle = title, eventStartTs = start,
        isAllDay = false, isRecurring = recurring,
        calendarId = calendarId, eventEndTs = start + hour, eventUid = uid, instanceTs = instanceTs,
        changedFields = fields, previousStartTs = previousStart,
        previousIsAllDay = previousStart?.let { false }, cancelledInstances = cancelled,
        seriesIsPlaceholder = placeholderSeries, replacedPlaceholder = replacedPlaceholder,
        isFirstPull = firstPull, isForcedPull = forced
    )

    private fun classify(vararg changes: SyncChange) = RecentChangeClassifier.classify(changes.toList()).entries

    @Test
    fun `a first pull records nothing`() {
        assertTrue(classify(change(ChangeType.NEW, firstPull = true)).isEmpty())
    }

    @Test
    fun `new, changed and deleted events are recorded`() {
        val entries = classify(
            change(ChangeType.NEW, uid = "a"),
            change(ChangeType.MODIFIED, uid = "b", fields = setOf(ChangedField.LOCATION)),
            change(ChangeType.DELETED, uid = "c"),
        )
        assertEquals(listOf(ChangeType.NEW, ChangeType.MODIFIED, ChangeType.DELETED), entries.map { it.type })
    }

    @Test
    fun `a change naming no field is not recorded`() {
        assertTrue(classify(change(ChangeType.MODIFIED)).isEmpty())
    }

    @Test
    fun `each newly cancelled instance becomes a cancelled row and the series row goes`() {
        val entries = classify(change(ChangeType.MODIFIED, recurring = true, cancelled = listOf(slot)))

        val cancelled = entries.single()
        assertEquals(ChangeType.DELETED, cancelled.type)
        assertEquals(slot, cancelled.instanceTs)
        assertEquals(slot, cancelled.startTs)
        assertEquals("the series duration carries to the cancelled slot", slot + hour, cancelled.endTs)
        assertEquals("Weekly sync", cancelled.title)
        assertNull(cancelled.eventId)
    }

    @Test
    fun `a series with a real change and a cancelled instance keeps both rows`() {
        val entries = classify(
            change(ChangeType.MODIFIED, recurring = true, fields = setOf(ChangedField.TITLE), cancelled = listOf(slot))
        )
        assertEquals(setOf(0L, slot), entries.map { it.instanceTs }.toSet())
    }

    @Test
    fun `a first-time moved occurrence of an existing series reads as a change, not new`() {
        val entry = classify(
            change(ChangeType.NEW, instanceTs = slot, start = slot + hour, previousStart = slot, fields = setOf(ChangedField.TIME))
        ).single()

        assertEquals(ChangeType.MODIFIED, entry.type)
        assertEquals(slot, entry.previousStartTs)
        assertEquals(setOf(ChangedField.TIME), entry.changedFields)
    }

    @Test
    fun `a first-time override with no visible difference is not recorded`() {
        assertTrue(classify(change(ChangeType.NEW, instanceTs = slot)).isEmpty())
    }

    @Test
    fun `an occurrence of a placeholder series stays new and is recorded`() {
        val entry = classify(change(ChangeType.NEW, instanceTs = slot, placeholderSeries = true)).single()
        assertEquals(ChangeType.NEW, entry.type)
    }

    @Test
    fun `a series replacing its placeholder is recorded as new`() {
        val entry = classify(change(ChangeType.MODIFIED, recurring = true, replacedPlaceholder = true)).single()
        assertEquals(ChangeType.NEW, entry.type)
        assertTrue(entry.changedFields.isEmpty())
    }

    @Test
    fun `a new series with a changed occurrence is one row`() {
        val entries = classify(
            change(ChangeType.NEW, recurring = true),
            change(ChangeType.NEW, instanceTs = slot, start = slot + hour, previousStart = slot, fields = setOf(ChangedField.TIME)),
        )
        assertEquals(0L, entries.single().instanceTs)
        assertEquals(ChangeType.NEW, entries.single().type)
    }

    @Test
    fun `a series rename copied into its overrides folds into the series row`() {
        val entries = classify(
            change(ChangeType.MODIFIED, recurring = true, fields = setOf(ChangedField.TITLE), title = "v2"),
            change(ChangeType.MODIFIED, instanceTs = slot, fields = setOf(ChangedField.TITLE), title = "v2"),
            change(ChangeType.MODIFIED, instanceTs = slot + 7 * 24 * hour, fields = setOf(ChangedField.TITLE), title = "v2"),
        )
        assertEquals(listOf(0L), entries.map { it.instanceTs })
    }

    @Test
    fun `an occurrence with its own time change stays beside the series row`() {
        val entries = classify(
            change(ChangeType.MODIFIED, recurring = true, fields = setOf(ChangedField.TITLE)),
            change(ChangeType.MODIFIED, instanceTs = slot, fields = setOf(ChangedField.TITLE, ChangedField.TIME), previousStart = slot),
        )
        assertEquals(setOf(0L, slot), entries.map { it.instanceTs }.toSet())
    }

    @Test
    fun `occurrence deletions fold into their deleted series`() {
        val entries = classify(
            change(ChangeType.DELETED, recurring = true),
            change(ChangeType.DELETED, instanceTs = slot),
        )
        assertEquals(listOf(0L), entries.map { it.instanceTs })
    }

    @Test
    fun `the same uid in another calendar never folds across calendars`() {
        val entries = classify(
            change(ChangeType.NEW, recurring = true, calendarId = 1),
            change(ChangeType.NEW, instanceTs = slot, calendarId = 2, previousStart = slot, fields = setOf(ChangedField.TIME)),
        )
        assertEquals(setOf(1L to 0L, 2L to slot), entries.map { it.calendarId to it.instanceTs }.toSet())
        assertEquals(ChangeType.MODIFIED, entries.single { it.calendarId == 2L }.type)
    }

    @Test
    fun `a forced pull records changes and deletions but nothing new`() {
        val entries = classify(
            change(ChangeType.NEW, uid = "old", forced = true),
            change(ChangeType.MODIFIED, uid = "edit", fields = setOf(ChangedField.TITLE), forced = true),
            change(ChangeType.DELETED, uid = "gone", forced = true),
            change(ChangeType.MODIFIED, uid = "series", recurring = true, cancelled = listOf(slot), forced = true),
        )
        assertEquals(setOf("edit", "gone", "series"), entries.map { it.eventUid }.toSet())
        assertTrue(entries.none { it.type == ChangeType.NEW })
    }

    @Test
    fun `a forced pull of a series new to the window with its first-time override records nothing`() {
        val entries = classify(
            change(ChangeType.NEW, recurring = true, forced = true),
            change(ChangeType.NEW, instanceTs = slot, start = slot + hour, forced = true),
        )
        assertTrue("got $entries", entries.isEmpty())
    }

    @Test
    fun `a series rename copied into an override hands the override back for its stored row`() {
        val result = RecentChangeClassifier.classify(listOf(
            change(ChangeType.MODIFIED, recurring = true, fields = setOf(ChangedField.TITLE), title = "Daily sync"),
            change(ChangeType.MODIFIED, instanceTs = slot, start = slot + hour, fields = setOf(ChangedField.TITLE),
                title = "Daily sync", eventId = 11),
        ))
        assertEquals(listOf(0L), result.entries.map { it.instanceTs })
        val refresh = result.refreshes.single()
        assertEquals(slot, refresh.instanceTs)
        assertEquals("Daily sync", refresh.title)
        assertEquals(11L, refresh.eventId)
    }

    @Test
    fun `a forced pull drops a series replacing its placeholder, since it reads as new`() {
        assertTrue(classify(change(ChangeType.MODIFIED, recurring = true, replacedPlaceholder = true, forced = true)).isEmpty())
    }

    @Test
    fun `a cancelled changed occurrence is one row with what the user last saw`() {
        // The series gains the EXDATE and the pull prunes the moved occurrence at that slot.
        val entries = classify(
            change(ChangeType.MODIFIED, recurring = true, cancelled = listOf(slot)),
            change(ChangeType.DELETED, instanceTs = slot, start = slot + 2 * hour, title = "Moved sync"),
        )

        val row = entries.single()
        assertEquals(ChangeType.DELETED, row.type)
        assertEquals(slot, row.instanceTs)
        assertEquals(slot + 2 * hour, row.startTs)
        assertEquals("Moved sync", row.title)
    }

    @Test
    fun `the order of the two cancel signals doesn't matter`() {
        val entries = classify(
            change(ChangeType.DELETED, instanceTs = slot, start = slot + 2 * hour, title = "Moved sync"),
            change(ChangeType.MODIFIED, recurring = true, cancelled = listOf(slot)),
        )
        assertEquals("Moved sync", entries.single().title)
    }
}
