package org.onekash.kashcal.domain.changes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.model.SyncChange
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [RecentChangesRecorder] over a real Room database on disk: merging across syncs, the
 * 7-day prune, rows naming an event that is already gone, and that rows survive reopening the
 * database (an app restart).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecentChangesRecorderTest {

    private val dbName = "recent-changes-recorder-test"
    private lateinit var context: Context
    private lateinit var database: KashCalDatabase
    private lateinit var recorder: RecentChangesRecorder
    private var calendarId = 0L
    private var eventId = 0L
    private val day = 24 * 3_600_000L
    private val now = 1_790_000_000_000L

    private fun open(): KashCalDatabase =
        Room.databaseBuilder(context, KashCalDatabase::class.java, dbName).allowMainThreadQueries().build()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        database = open()
        recorder = RecentChangesRecorder(database)
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "self@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://x/a/", displayName = "Work", color = 7)
        )
        eventId = database.eventsDao().insert(
            Event(uid = "u1", calendarId = calendarId, title = "Standup", startTs = 1_000, endTs = 2_000,
                timezone = "UTC", dtstamp = 0)
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    private fun change(
        type: ChangeType,
        uid: String = "u1",
        id: Long? = if (type == ChangeType.DELETED) null else eventId,
        fields: Set<ChangedField> = emptySet(),
        previousStart: Long? = null,
        start: Long = 1_000,
        title: String = "Standup",
        instanceTs: Long = 0,
        recurring: Boolean = false,
    ) = SyncChange(
        type = type, eventId = id, eventTitle = title, eventStartTs = start, isAllDay = false,
        isRecurring = recurring, calendarId = calendarId,
        eventEndTs = start + 1_000, eventUid = uid, instanceTs = instanceTs, changedFields = fields,
        previousStartTs = previousStart, previousIsAllDay = previousStart?.let { false }
    )

    // ---- a series edit copied into an already-changed occurrence (iOS) ----

    private val slot = 8 * day

    /** Records a moved occurrence of series u1, as an earlier sync does; returns its event id. */
    private suspend fun recordMovedOccurrence(): Long {
        val overrideId = database.eventsDao().insert(
            Event(uid = "u1", calendarId = calendarId, title = "Standup", startTs = slot + 3_600_000,
                endTs = slot + 7_200_000, timezone = "UTC", dtstamp = 0, originalEventId = eventId,
                originalInstanceTime = slot)
        )
        recorder.record(listOf(change(ChangeType.MODIFIED, id = overrideId, instanceTs = slot, recurring = true,
            start = slot + 3_600_000, fields = setOf(ChangedField.TIME), previousStart = slot)), now)
        return overrideId
    }

    /** The later sync: the series is renamed and the client copies the title into the override. */
    private fun renameCopiedIntoOverride(overrideId: Long) = listOf(
        change(ChangeType.MODIFIED, recurring = true, fields = setOf(ChangedField.TITLE), title = "Daily"),
        change(ChangeType.MODIFIED, id = overrideId, instanceTs = slot, recurring = true,
            start = slot + 3_600_000, fields = setOf(ChangedField.TITLE), title = "Daily"),
    )

    private suspend fun row(instanceTs: Long) =
        database.recentChangesDao().getByKey(calendarId, "u1", instanceTs)

    @Test
    fun `an occurrence row takes the series' new title when the rename is copied into it`() = runTest {
        val overrideId = recordMovedOccurrence()

        val recorded = recorder.record(renameCopiedIntoOverride(overrideId), now + day)

        val occurrence = row(slot)!!
        assertEquals("Daily", occurrence.title)
        assertEquals(ChangeType.MODIFIED, occurrence.changeType)
        assertEquals(setOf(ChangedField.TIME), occurrence.changedFields)
        assertEquals(slot, occurrence.previousStartTs)
        assertEquals("the occurrence keeps the day its move arrived", now, occurrence.detectedAt)
        assertEquals(setOf(ChangedField.TITLE), row(0)!!.changedFields)
        assertEquals("only the series row is announced", listOf(0L), recorded.map { it.instanceTs })
    }

    @Test
    fun `a dismissed occurrence row stays dismissed when the rename is copied into it`() = runTest {
        val overrideId = recordMovedOccurrence()
        database.recentChangesDao().dismiss(listOf(row(slot)!!.id), now + 1)

        recorder.record(renameCopiedIntoOverride(overrideId), now + day)

        assertEquals(now + 1, row(slot)!!.dismissedAt)
        assertEquals(listOf(0L), visible().map { it.change.instanceTs })
    }

    @Test
    fun `a folded occurrence with no stored row adds none`() = runTest {
        recorder.record(renameCopiedIntoOverride(eventId), now)

        assertNull(row(slot))
        assertEquals(listOf(0L), visible().map { it.change.instanceTs })
    }

    @Test
    fun `a refreshed row naming a gone event keeps no event id and the batch records`() = runTest {
        val overrideId = recordMovedOccurrence()
        database.eventsDao().deleteById(overrideId)

        recorder.record(renameCopiedIntoOverride(overrideId), now + day)

        assertNull(row(slot)!!.eventId)
        assertEquals("Daily", row(slot)!!.title)
    }

    @Test
    fun `an alarm-only series edit keeps the stored occurrence row as it was`() = runTest {
        val overrideId = recordMovedOccurrence()
        val before = row(slot)!!

        // The series and its override both changed only things no category names.
        recorder.record(listOf(
            change(ChangeType.MODIFIED, recurring = true),
            change(ChangeType.MODIFIED, id = overrideId, instanceTs = slot, recurring = true, start = slot + 3_600_000),
        ), now + day)

        assertEquals(before, row(slot))
    }

    @Test
    fun `deleting a series removes its occurrence rows`() = runTest {
        recordMovedOccurrence()

        recorder.record(listOf(change(ChangeType.DELETED, recurring = true)), now + day)

        assertNull(row(slot))
        assertEquals(ChangeType.DELETED, row(0)!!.changeType)
        assertEquals(listOf(0L), visible().map { it.change.instanceTs })
    }

    private suspend fun visible() = database.recentChangesDao().observeVisible(0).first()

    @Test
    fun `record writes the entries and returns them`() = runTest {
        val recorded = recorder.record(listOf(change(ChangeType.NEW)), now)

        assertEquals(1, recorded.size)
        val row = visible().single()
        assertEquals(ChangeType.NEW, row.change.changeType)
        assertEquals(eventId, row.change.eventId)
        assertEquals(now, row.change.detectedAt)
        assertEquals("Work", row.calendarName)
    }

    @Test
    fun `a later change merges into the row and moves it to the newest time`() = runTest {
        recorder.record(listOf(change(ChangeType.MODIFIED, fields = setOf(ChangedField.TIME), previousStart = 500)), now)
        recorder.record(
            listOf(change(ChangeType.MODIFIED, fields = setOf(ChangedField.LOCATION), start = 3_000)),
            now + 1_000
        )

        val row = visible().single().change
        assertEquals(setOf(ChangedField.TIME, ChangedField.LOCATION), row.changedFields)
        assertEquals(500L, row.previousStartTs)
        assertEquals(3_000L, row.startTs)
        assertEquals(now + 1_000, row.detectedAt)
    }

    @Test
    fun `new then deleted becomes deleted`() = runTest {
        recorder.record(listOf(change(ChangeType.NEW)), now)
        recorder.record(listOf(change(ChangeType.DELETED)), now + 1)

        val row = visible().single().change
        assertEquals(ChangeType.DELETED, row.changeType)
        assertNull(row.eventId)
    }

    @Test
    fun `a dismissed row is replaced by a fresh one when the event changes again`() = runTest {
        recorder.record(listOf(change(ChangeType.NEW)), now)
        val id = visible().single().change.id
        database.recentChangesDao().dismiss(listOf(id), now + 1)

        recorder.record(listOf(change(ChangeType.MODIFIED, fields = setOf(ChangedField.TITLE))), now + 2)

        val row = visible().single().change
        assertEquals(ChangeType.MODIFIED, row.changeType)
        assertNull(row.dismissedAt)
    }

    @Test
    fun `rows older than seven days are pruned on the next record`() = runTest {
        recorder.record(listOf(change(ChangeType.NEW, uid = "old", id = null)), now)

        recorder.record(listOf(change(ChangeType.NEW, uid = "fresh", id = null)), now + RecentChangesRecorder.RETENTION_MS + 1)

        assertEquals(listOf("fresh"), visible().map { it.change.eventUid })
    }

    @Test
    fun `a change naming a deleted event is kept with no event id, and the batch is recorded`() = runTest {
        database.eventsDao().deleteById(eventId)

        recorder.record(listOf(change(ChangeType.NEW), change(ChangeType.NEW, uid = "other", id = null)), now)

        val rows = visible().associateBy { it.change.eventUid }
        assertEquals(2, rows.size)
        assertNull(rows.getValue("u1").change.eventId)
    }

    @Test
    fun `a sync whose changes are all skipped writes nothing and returns nothing`() = runTest {
        val recorded = recorder.record(listOf(change(ChangeType.MODIFIED)), now)

        assertTrue(recorded.isEmpty())
        assertTrue(visible().isEmpty())
    }

    @Test
    fun `rows survive reopening the database`() = runTest {
        recorder.record(listOf(change(ChangeType.NEW)), now)
        database.close()

        database = open()

        assertEquals(listOf("u1"), visible().map { it.change.eventUid })
    }
}
