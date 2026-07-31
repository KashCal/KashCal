package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renaming a tag must re-upload every affected *syncable* event with its new
 * tag, so a rename on one device reaches the CalDAV server and the user's other
 * devices. The rewrite already worked; the gap this covers is marking the
 * affected events dirty (PENDING_UPDATE + queued OPERATION_UPDATE) without
 * bumping SEQUENCE (a tag is cosmetic — no attendee re-notification), skipping
 * events that can't be pushed, and routing an exception's update to its master.
 * Backed by a real in-memory Room DB for fidelity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterRenameCategoryTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator

    private var caldavCalendarId: Long = 0
    private var localCalendarId: Long = 0
    private var readOnlyCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)

        val caldavAccount = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "a@example.test")
        )
        caldavCalendarId = database.calendarsDao().insert(
            Calendar(accountId = caldavAccount, caldavUrl = "https://a.example.test/cal/", displayName = "A", color = 0)
        )
        readOnlyCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = caldavAccount,
                caldavUrl = "https://a.example.test/sub.ics",
                displayName = "Sub",
                color = 0,
                isReadOnly = true
            )
        )
        val localAccount = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = LocalCalendarInitializer.LOCAL_EMAIL)
        )
        localCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = localAccount,
                caldavUrl = LocalCalendarInitializer.LOCAL_CALENDAR_URL,
                displayName = "Local",
                color = 0
            )
        )
    }

    @After
    fun teardown() = database.close()

    // ---- helpers ----

    private suspend fun seedEvent(
        calendarId: Long,
        categories: List<String>?,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        caldavUrl: String? = "https://a.example.test/cal/e.ics",
        sequence: Int = 0,
        originalEventId: Long? = null,
        uid: String = "uid-${System.nanoTime()}",
        localModifiedAt: Long? = 1L,
    ): Long {
        val now = System.currentTimeMillis()
        return database.eventsDao().insert(
            Event(
                uid = uid,
                calendarId = calendarId,
                title = "E",
                startTs = now,
                endTs = now + 3600000,
                dtstamp = now,
                categories = categories,
                syncStatus = syncStatus,
                caldavUrl = caldavUrl,
                sequence = sequence,
                originalEventId = originalEventId,
                localModifiedAt = localModifiedAt,
                updatedAt = localModifiedAt ?: 1L,
            )
        )
    }

    private suspend fun pendingUpdateOps(): List<PendingOperation> =
        database.pendingOperationsDao().getAll()
            .filter { it.operation == PendingOperation.OPERATION_UPDATE }

    private suspend fun categoriesOf(id: Long): List<String>? =
        database.eventsDao().getById(id)?.categories

    private suspend fun syncStatusOf(id: Long): SyncStatus? =
        database.eventsDao().getById(id)?.syncStatus

    // ---- rename propagates ----

    @Test
    fun `renames the tag and queues one UPDATE for a synced CalDAV event`() = runTest {
        val id = seedEvent(caldavCalendarId, listOf("Work"))

        val queued = eventWriter.renameCategory("Work", "Job")

        assertEquals(listOf("Job"), categoriesOf(id))
        assertEquals(SyncStatus.PENDING_UPDATE, syncStatusOf(id))
        assertEquals(1, queued)
        val ops = pendingUpdateOps()
        assertEquals(1, ops.size)
        assertEquals(id, ops.single().eventId)
    }

    @Test
    fun `queues one UPDATE per carrying event and none for non-carrying`() = runTest {
        val a = seedEvent(caldavCalendarId, listOf("Work"))
        val b = seedEvent(caldavCalendarId, listOf("Work", "Gym"))
        val c = seedEvent(caldavCalendarId, listOf("Gym")) // does not carry

        eventWriter.renameCategory("Work", "Job")

        val queuedIds = pendingUpdateOps().map { it.eventId }.toSet()
        assertEquals(setOf(a, b), queuedIds)
        assertEquals("non-carrying event untouched", listOf("Gym"), categoriesOf(c))
    }

    @Test
    fun `restamps localModifiedAt and updatedAt on a renamed synced event`() = runTest {
        val before = System.currentTimeMillis()
        val id = seedEvent(caldavCalendarId, listOf("Work"), localModifiedAt = 1L)

        eventWriter.renameCategory("Work", "Job")

        val row = database.eventsDao().getById(id)!!
        assertTrue("localModifiedAt restamped to rename time", row.localModifiedAt!! >= before)
        assertTrue("updatedAt restamped to rename time", row.updatedAt!! >= before)
    }

    // ---- cosmetic rename must not re-notify attendees ----

    @Test
    fun `does not bump SEQUENCE on a renamed event`() = runTest {
        val id = seedEvent(caldavCalendarId, listOf("Work"), sequence = 3)

        eventWriter.renameCategory("Work", "Job")

        assertEquals("a cosmetic rename must not bump SEQUENCE", 3, database.eventsDao().getById(id)!!.sequence)
    }

    @Test
    fun `serialized wire body carries the new category and the unchanged SEQUENCE`() = runTest {
        val id = seedEvent(caldavCalendarId, listOf("Work"), sequence = 3)

        eventWriter.renameCategory("Work", "Job")

        val ical = EventToICalEventMapper.toICalEvent(database.eventsDao().getById(id)!!)
        assertTrue("wire CATEGORIES carries the renamed tag", ical.categories.contains("Job"))
        assertTrue("old tag is gone from the wire body", !ical.categories.contains("Work"))
        assertEquals("wire SEQUENCE is not bumped by a cosmetic rename", 3, ical.sequence)
    }

    // ---- only syncable events are queued ----

    @Test
    fun `rewrites a local-only event but queues no operation for it`() = runTest {
        val id = seedEvent(localCalendarId, listOf("Work"), syncStatus = SyncStatus.SYNCED, caldavUrl = null)

        val queued = eventWriter.renameCategory("Work", "Job")

        assertEquals("local event still rewritten locally", listOf("Job"), categoriesOf(id))
        assertEquals(0, queued)
        assertTrue("no op queued for a local event", pendingUpdateOps().isEmpty())
    }

    @Test
    fun `rewrites a read-only subscription event but queues no operation for it`() = runTest {
        val id = seedEvent(readOnlyCalendarId, listOf("Work"))

        val queued = eventWriter.renameCategory("Work", "Job")

        assertEquals(listOf("Job"), categoriesOf(id))
        assertEquals(0, queued)
        assertTrue("read-only calendars are never pushed", pendingUpdateOps().isEmpty())
    }

    @Test
    fun `preserves PENDING_CREATE and queues no extra UPDATE`() = runTest {
        val id = seedEvent(caldavCalendarId, listOf("Work"), syncStatus = SyncStatus.PENDING_CREATE)

        val queued = eventWriter.renameCategory("Work", "Job")

        assertEquals("categories still rewritten", listOf("Job"), categoriesOf(id))
        assertEquals("must not downgrade PENDING_CREATE", SyncStatus.PENDING_CREATE, syncStatusOf(id))
        assertEquals(0, queued)
        assertTrue("the pending CREATE already carries the new categories", pendingUpdateOps().isEmpty())
    }

    @Test
    fun `leaves a PENDING_DELETE event queued for delete and does not resurrect it`() = runTest {
        // A soft-deleted CalDAV event still lives in the events table with its
        // categories intact and a queued DELETE. Re-stamping it PENDING_UPDATE
        // would resurrect it in the UI while the DELETE still drains — the
        // server deletes it, the device shows it active. Skip it.
        val id = seedEvent(caldavCalendarId, listOf("Work"), syncStatus = SyncStatus.PENDING_DELETE)

        val queued = eventWriter.renameCategory("Work", "Job")

        assertEquals("must not resurrect a soft-deleted event", SyncStatus.PENDING_DELETE, syncStatusOf(id))
        assertEquals(0, queued)
        assertTrue("no UPDATE op queued for a pending-delete event", pendingUpdateOps().isEmpty())
    }

    @Test
    fun `in a mix only the synced event is queued`() = runTest {
        val local = seedEvent(localCalendarId, listOf("Work"), syncStatus = SyncStatus.SYNCED, caldavUrl = null)
        val creating = seedEvent(caldavCalendarId, listOf("Work"), syncStatus = SyncStatus.PENDING_CREATE)
        val synced = seedEvent(caldavCalendarId, listOf("Work"), syncStatus = SyncStatus.SYNCED)

        eventWriter.renameCategory("Work", "Job")

        val queuedIds = pendingUpdateOps().map { it.eventId }
        assertEquals(listOf(synced), queuedIds)
        // All three still rewritten locally.
        assertEquals(listOf("Job"), categoriesOf(local))
        assertEquals(listOf("Job"), categoriesOf(creating))
        assertEquals(listOf("Job"), categoriesOf(synced))
    }

    @Test
    fun `rolls back the whole cascade atomically when the queue write fails`() = runTest {
        val id = seedEvent(caldavCalendarId, listOf("Work"))

        // Fault-inject at the queue-write layer: an explicit throwing spy (not a
        // relaxed mock) so the transaction that spans the category rewrite and
        // the pending-op insert must roll back as one unit.
        val throwingDb = spyk(database)
        val throwingPendingOps = spyk(database.pendingOperationsDao())
        coEvery { throwingPendingOps.insert(any()) } throws RuntimeException("boom")
        every { throwingDb.pendingOperationsDao() } returns throwingPendingOps
        val faultingWriter = EventWriter(throwingDb, occurrenceGenerator)

        try {
            faultingWriter.renameCategory("Work", "Job")
            fail("expected the queue write to throw")
        } catch (_: RuntimeException) {
            // expected
        }

        assertEquals("rewrite must roll back with the failed queue write", listOf("Work"), categoriesOf(id))
        assertEquals(SyncStatus.SYNCED, syncStatusOf(id))
        assertTrue(pendingUpdateOps().isEmpty())
    }

    // Note: an event with no matching calendar row is schema-unreachable — the
    // events table has a CASCADE foreign key on calendar_id, so an orphaned
    // calendarId can't exist. The production code still guards against a null
    // calendar defensively, but no test can seed the impossible state.

    // ---- exceptions route their update to the master ----

    @Test
    fun `an exception carrying the tag queues the update on its master`() = runTest {
        val master = seedEvent(caldavCalendarId, listOf("Work"), uid = "series-uid")
        val exception = seedEvent(
            caldavCalendarId,
            listOf("Work"),
            uid = "series-uid",
            originalEventId = master
        )

        eventWriter.renameCategory("Work", "Job")

        // Both rows rewritten.
        assertEquals(listOf("Job"), categoriesOf(master))
        assertEquals(listOf("Job"), categoriesOf(exception))
        // The UPDATE targets the master, exactly once (dedup master+exception).
        val ops = pendingUpdateOps()
        assertEquals(listOf(master), ops.map { it.eventId })
        assertEquals(SyncStatus.PENDING_UPDATE, syncStatusOf(master))
    }

    @Test
    fun `an exception whose master does not carry the tag still queues the master`() = runTest {
        val master = seedEvent(caldavCalendarId, listOf("Personal"), uid = "series-uid")
        val exception = seedEvent(
            caldavCalendarId,
            listOf("Work"),
            uid = "series-uid",
            originalEventId = master
        )

        eventWriter.renameCategory("Work", "Job")

        // The master never carried the tag — its own categories are untouched.
        assertEquals("master categories unchanged", listOf("Personal"), categoriesOf(master))
        assertEquals(listOf("Job"), categoriesOf(exception))
        // But the master is still queued so the exception's rewritten body ships
        // via the master's bundled PUT (queue-for-bundling, not because-changed).
        val ops = pendingUpdateOps()
        assertEquals(listOf(master), ops.map { it.eventId })
        assertEquals(SyncStatus.PENDING_UPDATE, syncStatusOf(master))
    }

    // ---- rename spanning multiple accounts routes per-account ----

    @Test
    fun `a rename spanning two accounts queues each event to its own account`() = runTest {
        // Second CalDAV account + calendar.
        val accountB = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "b@example.test")
        )
        val calendarB = database.calendarsDao().insert(
            Calendar(accountId = accountB, caldavUrl = "https://b.example.test/cal/", displayName = "B", color = 0)
        )
        val eventA = seedEvent(caldavCalendarId, listOf("Work"))
        val eventB = seedEvent(calendarB, listOf("Work"), caldavUrl = "https://b.example.test/cal/e.ics")

        eventWriter.renameCategory("Work", "Job")

        // Each event gets its own op; each op carries its own event (which
        // carries its calendar -> account), so the drain routes per-account.
        val queuedIds = pendingUpdateOps().map { it.eventId }.toSet()
        assertEquals(setOf(eventA, eventB), queuedIds)
    }

    // ---- a heavily-used tag exceeds SQLite's IN-clause variable limit ----

    @Test
    fun `renames a tag carried by more than the SQL variable limit of events`() = runTest {
        // A power user tags 1200 events "Work", then renames it. The batch
        // getByIds queries must chunk under SQLite's 999-bind-variable cap, or
        // the whole rename throws inside the transaction and silently no-ops.
        val count = 1200
        val ids = (1..count).map { seedEvent(caldavCalendarId, listOf("Work")) }

        val queued = eventWriter.renameCategory("Work", "Job")

        assertEquals(count, queued)
        assertEquals(count, pendingUpdateOps().size)
        assertEquals(listOf("Job"), categoriesOf(ids.first()))
        assertEquals(listOf("Job"), categoriesOf(ids.last()))
    }
}
