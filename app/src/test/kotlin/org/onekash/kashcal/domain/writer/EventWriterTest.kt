package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive tests for EventWriter.
 *
 * Tests cover:
 * - Create events (single and recurring)
 * - Update events
 * - Delete events (soft delete for sync)
 * - Edit single occurrence (exception creation)
 * - Delete single occurrence (EXDATE)
 * - Split series (this and all future)
 * - Move event to different calendar
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventWriterTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0
    private var iCloudCalendar2Id: Long = 0
    private var localCalendarId: Long = 0
    private var otherAccountCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)

        // Create test accounts and calendars
        runTest {
            val testAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "https://caldav.icloud.com/test/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
            iCloudCalendar2Id = database.calendarsDao().insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "https://caldav.icloud.com/work/",
                    displayName = "Work Calendar",
                    color = 0xFFFF5722.toInt()
                )
            )

            val localAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "local")
            )
            localCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = localAccountId,
                    caldavUrl = "local://default",
                    displayName = "Local",
                    color = 0xFF4CAF50.toInt()
                )
            )

            // A SECOND synced account (distinct from test@icloud.com) for
            // cross-account move tests.
            val otherAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "other@nextcloud.test")
            )
            otherAccountCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = otherAccountId,
                    caldavUrl = "https://nc.test/remote.php/dav/calendars/other/personal/",
                    displayName = "Other Account",
                    color = 0xFF9C27B0.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Category usage tracking ==========

    @Test
    fun `createEvent records category usage with the save timestamp`() = runTest {
        val event = createBaseEvent().copy(categories = listOf("Work", "Gym"))

        eventWriter.createEvent(event, isLocal = true)

        val work = database.categoryDao().getByName("Work")
        val gym = database.categoryDao().getByName("Gym")
        assertNotNull("saving an event registers its tags", work)
        assertNotNull(gym)
        assertTrue("recency is stamped at save time", work!!.lastUsedAt > 0L)
    }

    @Test
    fun `createEvent with no categories touches nothing`() = runTest {
        eventWriter.createEvent(createBaseEvent(), isLocal = true)

        assertEquals(0, database.categoryDao().observeAll().first().size)
    }

    @Test
    fun `re-saving an event never clobbers a tag's custom color`() = runTest {
        // The user has chosen a custom color for "Work".
        database.categoryDao().setColor("Work", 0xFF4457C9.toInt(), now = 1L)
        val created = eventWriter.createEvent(
            createBaseEvent().copy(categories = listOf("Work")),
            isLocal = true
        )

        eventWriter.updateEvent(created.copy(title = "Edited"), isLocal = true)

        assertEquals(
            "usage tracking must preserve the chosen color",
            0xFF4457C9.toInt(),
            database.categoryDao().getByName("Work")!!.color
        )
    }

    @Test
    fun `updateEvent advances category recency`() = runTest {
        database.categoryDao().touch("Work", now = 1L)
        val created = eventWriter.createEvent(
            createBaseEvent().copy(categories = listOf("Work")),
            isLocal = true
        )
        val afterCreate = database.categoryDao().getByName("Work")!!.lastUsedAt

        eventWriter.updateEvent(created.copy(title = "Edited"), isLocal = true)

        assertTrue(
            "editing a tagged event bumps the tag's recency",
            database.categoryDao().getByName("Work")!!.lastUsedAt >= afterCreate
        )
    }

    @Test
    fun `recordCategoryUsage registers a brand-new tag as a colorless registry row`() = runTest {
        // The device save path reconciles tags through this entry point rather
        // than a Room createEvent. A never-seen name must join the registry so
        // it becomes selectable and colorable — with no color assigned yet.
        eventWriter.recordCategoryUsage(listOf("Errand"))

        val errand = database.categoryDao().getByName("Errand")
        assertNotNull("a freshly-applied tag joins the shared registry", errand)
        assertNull("a new tag has no color until the user picks one", errand!!.color)
        assertTrue("recency is stamped so it surfaces in suggestions", errand.lastUsedAt > 0L)
    }

    @Test
    fun `recordCategoryUsage bumps recency without clobbering an existing color`() = runTest {
        database.categoryDao().setColor("Work", 0xFF4457C9.toInt(), now = 1L)

        eventWriter.recordCategoryUsage(listOf("Work"))

        val work = database.categoryDao().getByName("Work")!!
        assertEquals("the user's chosen color survives usage tracking", 0xFF4457C9.toInt(), work.color)
        assertTrue("reusing the tag bumps its recency", work.lastUsedAt > 1L)
    }

    // ========== Create Event ==========

    @Test
    fun `createEvent generates UID if not provided`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event)

        assertTrue(created.uid.isNotBlank())
        assertTrue(created.uid.contains("@kashcal.onekash.org"))
    }

    @Test
    fun `createEvent preserves provided UID`() = runTest {
        val event = createBaseEvent().copy(uid = "custom-uid@example.com")

        val created = eventWriter.createEvent(event)

        assertEquals("custom-uid@example.com", created.uid)
    }

    @Test
    fun `createEvent sets PENDING_CREATE status for CalDAV calendar`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event, isLocal = false)

        assertEquals(SyncStatus.PENDING_CREATE, created.syncStatus)
    }

    @Test
    fun `createEvent sets SYNCED status for local calendar`() = runTest {
        val event = createBaseEvent().copy(calendarId = localCalendarId)

        val created = eventWriter.createEvent(event, isLocal = true)

        assertEquals(SyncStatus.SYNCED, created.syncStatus)
    }

    @Test
    fun `createEvent generates single occurrence for non-recurring event`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event)

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertEquals(1, occurrences.size)
        assertEquals(created.startTs, occurrences[0].startTs)
    }

    @Test
    fun `createEvent generates multiple occurrences for recurring event`() = runTest {
        val event = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10")

        val created = eventWriter.createEvent(event)

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertEquals(10, occurrences.size)
    }

    @Test
    fun `createEvent queues pending operation for CalDAV calendar`() = runTest {
        val event = createBaseEvent()

        val created = eventWriter.createEvent(event, isLocal = false)

        val pendingOps = database.pendingOperationsDao().getForEvent(created.id)
        assertEquals(1, pendingOps.size)
        assertEquals(PendingOperation.OPERATION_CREATE, pendingOps[0].operation)
    }

    // ========== createImportedSeries (ICS file import: master + exceptions) ==========

    /**
     * Build a master + N exception events as they arrive from an ICS file
     * import: all sharing one source UID, exceptions distinguished by
     * originalInstanceTime, originalEventId not yet set (the writer sets it
     * after inserting the master). The caller (EventCoordinator) has already
     * regenerated the shared UID; this method just persists the linked series.
     */
    private fun importMasterWithExceptions(
        calendarId: Long,
        sharedUid: String,
        rrule: String = "FREQ=DAILY;COUNT=5"
    ): Pair<Event, List<Event>> {
        val now = System.currentTimeMillis()
        val start = now + 86400000
        val master = Event(
            uid = sharedUid,
            calendarId = calendarId,
            title = "Imported Series",
            startTs = start,
            endTs = start + 3600000,
            rrule = rrule,
            dtstamp = now
        )
        // Override the 3rd daily instance (start + 2 days), moved 2 hours later.
        val overriddenInstance = start + 2 * 86400000
        val exception = Event(
            uid = sharedUid,
            calendarId = calendarId,
            title = "Moved Instance",
            startTs = overriddenInstance + 2 * 3600000,
            endTs = overriddenInstance + 3 * 3600000,
            originalInstanceTime = overriddenInstance,
            dtstamp = now
        )
        return master to listOf(exception)
    }

    @Test
    fun `createImportedSeries persists master with occurrences from RRULE`() = runTest {
        val (master, exceptions) = importMasterWithExceptions(localCalendarId, "series-1@import")

        val saved = eventWriter.createImportedSeries(master, exceptions, isLocal = true)

        assertTrue("master row id assigned", saved.master.id > 0)
        // 5 daily occurrences; the overridden one is linked, not duplicated.
        val occurrences = database.occurrencesDao().getForEvent(saved.master.id)
        assertEquals(5, occurrences.size)
    }

    @Test
    fun `createImportedSeries links exception to master and shares UID`() = runTest {
        val (master, exceptions) = importMasterWithExceptions(localCalendarId, "series-2@import")

        val saved = eventWriter.createImportedSeries(master, exceptions, isLocal = true)

        assertEquals("one exception persisted", 1, saved.exceptions.size)
        val savedException = saved.exceptions[0]
        assertTrue("exception row id assigned", savedException.id > 0)
        assertEquals("exception shares master UID", saved.master.uid, savedException.uid)
        assertEquals("exception links to master", saved.master.id, savedException.originalEventId)
        assertEquals(
            "originalInstanceTime preserved",
            exceptions[0].originalInstanceTime,
            savedException.originalInstanceTime
        )
        assertNull("exception has no RRULE", savedException.rrule)
    }

    @Test
    fun `createImportedSeries renders overridden occurrence once via linkException`() = runTest {
        val (master, exceptions) = importMasterWithExceptions(localCalendarId, "series-3@import")

        val saved = eventWriter.createImportedSeries(master, exceptions, isLocal = true)

        val overriddenTime = exceptions[0].originalInstanceTime!!
        // The master's occurrence at the overridden instance time must carry the
        // exception link (Model B) — not a second standalone occurrence row.
        val linked = database.occurrencesDao().getForEvent(saved.master.id)
            .filter { it.exceptionEventId == saved.exceptions[0].id }
        assertEquals("exactly one linked occurrence", 1, linked.size)
        // No standalone occurrence rows under the exception's own event id.
        val standaloneUnderException = database.occurrencesDao().getForEvent(saved.exceptions[0].id)
        assertTrue("no standalone occurrence for exception", standaloneUnderException.isEmpty())
        // Total occurrence count for the series stays at the RRULE count.
        assertEquals(5, database.occurrencesDao().getForEvent(saved.master.id).size)
        // The linked occurrence was moved to the exception's modified start time,
        // not left at the original RRULE instant.
        assertEquals(
            "linked occurrence carries the exception's moved start time",
            saved.exceptions[0].startTs,
            linked.first().startTs
        )
        assertNotEquals(
            "override was actually moved off the original instant",
            overriddenTime,
            linked.first().startTs
        )
    }

    @Test
    fun `createImportedSeries on CalDAV queues one CREATE for master and none for exceptions`() = runTest {
        val (master, exceptions) = importMasterWithExceptions(testCalendarId, "series-4@import")

        val saved = eventWriter.createImportedSeries(master, exceptions, isLocal = false)

        val masterOps = database.pendingOperationsDao().getForEvent(saved.master.id)
        assertEquals("one CREATE queued on master", 1, masterOps.size)
        assertEquals(PendingOperation.OPERATION_CREATE, masterOps[0].operation)

        val exceptionOps = database.pendingOperationsDao().getForEvent(saved.exceptions[0].id)
        assertTrue("no pending op queued for exception (bundled by push)", exceptionOps.isEmpty())
    }

    @Test
    fun `createImportedSeries marks master pending and exceptions SYNCED on CalDAV`() = runTest {
        val (master, exceptions) = importMasterWithExceptions(testCalendarId, "series-5@import")

        val saved = eventWriter.createImportedSeries(master, exceptions, isLocal = false)

        assertEquals(SyncStatus.PENDING_CREATE, database.eventsDao().getById(saved.master.id)!!.syncStatus)
        assertEquals(
            "exception is SYNCED locally (bundled with master)",
            SyncStatus.SYNCED,
            database.eventsDao().getById(saved.exceptions[0].id)!!.syncStatus
        )
    }

    @Test
    fun `createImportedSeries marks master SYNCED on local calendar`() = runTest {
        val (master, exceptions) = importMasterWithExceptions(localCalendarId, "series-6@import")

        val saved = eventWriter.createImportedSeries(master, exceptions, isLocal = true)

        assertEquals(SyncStatus.SYNCED, database.eventsDao().getById(saved.master.id)!!.syncStatus)
        // No pending operations for a local series.
        assertTrue(database.pendingOperationsDao().getForEvent(saved.master.id).isEmpty())
    }

    @Test
    fun `createEvent does not queue operation for local calendar`() = runTest {
        val event = createBaseEvent().copy(calendarId = localCalendarId)

        val created = eventWriter.createEvent(event, isLocal = true)

        val pendingOps = database.pendingOperationsDao().getForEvent(created.id)
        assertEquals(0, pendingOps.size)
    }

    // ========== Update Event ==========

    @Test
    fun `updateEvent changes event fields`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated Title", location = "New Location"),
            isLocal = true
        )

        val fromDb = database.eventsDao().getById(updated.id)
        assertEquals("Updated Title", fromDb?.title)
        assertEquals("New Location", fromDb?.location)
    }

    @Test
    fun `updateEvent increments sequence when RRULE changes`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY"),
            isLocal = true
        )
        assertEquals(0, original.sequence)

        val updated = eventWriter.updateEvent(
            original.copy(rrule = "FREQ=WEEKLY"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence when timing changes`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(startTs = original.startTs + 3600000),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence for title change`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(title = "New Title"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence for location change`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(location = "Room B"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence when EXDATE changes`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY"),
            isLocal = true
        )

        val updated = eventWriter.updateEvent(
            original.copy(exdate = "${original.startTs + 86400000}"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence when RDATE changes`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(rdate = "${original.startTs + 172800000}"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence when DURATION changes`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(duration = "PT1H"),
            isLocal = true
        )

        val updated = eventWriter.updateEvent(
            original.copy(duration = "PT2H"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent increments sequence when STATUS transitions to CANCELLED`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(status = "CONFIRMED"),
            isLocal = true
        )

        val updated = eventWriter.updateEvent(
            original.copy(status = "CANCELLED"),
            isLocal = true
        )

        assertEquals(1, updated.sequence)
    }

    @Test
    fun `updateEvent does not increment sequence for description change`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        val updated = eventWriter.updateEvent(
            original.copy(description = "Bring the deck"),
            isLocal = true
        )

        assertEquals(0, updated.sequence)
    }

    @Test
    fun `updateEvent regenerates occurrences when RRULE changes`() = runTest {
        val original = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        assertEquals(5, database.occurrencesDao().getForEvent(original.id).size)

        eventWriter.updateEvent(
            original.copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )

        assertEquals(10, database.occurrencesDao().getForEvent(original.id).size)
    }

    @Test
    fun `updateEvent sets PENDING_UPDATE for synced event`() = runTest {
        // Create and mark as synced
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markSynced(original.id, "etag123", System.currentTimeMillis())

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated"),
            isLocal = false
        )

        assertEquals(SyncStatus.PENDING_UPDATE, updated.syncStatus)
    }

    @Test
    fun `updateEvent keeps PENDING_CREATE if never synced`() = runTest {
        val original = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, original.syncStatus)

        val updated = eventWriter.updateEvent(
            original.copy(title = "Updated"),
            isLocal = false
        )

        assertEquals(SyncStatus.PENDING_CREATE, updated.syncStatus)
    }

    // ========== Delete Event ==========

    @Test
    fun `deleteEvent hard deletes local-only event`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(calendarId = localCalendarId),
            isLocal = true
        )

        eventWriter.deleteEvent(event.id, isLocal = true)

        assertNull(database.eventsDao().getById(event.id))
    }

    @Test
    fun `deleteEvent soft deletes CalDAV event`() = runTest {
        // Create and mark as synced
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markSynced(event.id, "etag123", System.currentTimeMillis())

        eventWriter.deleteEvent(event.id, isLocal = false)

        val deleted = database.eventsDao().getById(event.id)
        assertNotNull(deleted)
        assertEquals(SyncStatus.PENDING_DELETE, deleted?.syncStatus)
    }

    @Test
    fun `deleteEvent hard deletes never-synced CalDAV event`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        assertEquals(SyncStatus.PENDING_CREATE, event.syncStatus)

        eventWriter.deleteEvent(event.id, isLocal = false)

        assertNull(database.eventsDao().getById(event.id))
    }

    @Test
    fun `deleteEvent removes occurrences`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        assertEquals(5, database.occurrencesDao().getForEvent(event.id).size)

        eventWriter.deleteEvent(event.id, isLocal = true)

        assertEquals(0, database.occurrencesDao().getForEvent(event.id).size)
    }

    @Test
    fun `deleteEvent queues DELETE operation for synced event`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markSynced(event.id, "etag123", System.currentTimeMillis())

        eventWriter.deleteEvent(event.id, isLocal = false)

        val pendingOps = database.pendingOperationsDao().getForEvent(event.id)
        assertTrue(pendingOps.any { it.operation == PendingOperation.OPERATION_DELETE })
    }

    // ========== Edit Single Occurrence (Exception) ==========

    @Test
    fun `editSingleOccurrence creates exception event`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val targetOccurrence = occurrences[2] // 3rd occurrence

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = Event(
                uid = "",
                calendarId = master.calendarId,
                title = "Modified Occurrence",
                startTs = targetOccurrence.startTs + 3600000, // 1 hour later
                endTs = targetOccurrence.endTs + 3600000,
                dtstamp = System.currentTimeMillis()
            ),
            isLocal = true
        )

        assertEquals("Modified Occurrence", exception.title)
        assertEquals(master.id, exception.originalEventId)
        assertEquals(targetOccurrence.startTs, exception.originalInstanceTime)
        assertNull(exception.rrule) // Exception cannot have RRULE
    }

    @Test
    fun `editSingleOccurrence copies master attendees to the new exception`() = runTest {
        // A rescheduled occurrence of a recurring meeting must carry the
        // series' attendees so the bundled exception VEVENT pushes them
        // (mirrors splitSeries). Without this the override VEVENT has zero
        // ATTENDEEs and that instance loses its invitee list on the wire.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val target = occurrences[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Moved", startTs = target.startTs + 3600000, endTs = target.endTs + 3600000
            ),
            isLocal = true
        )

        val exAttendees = database.attendeesDao().getForEventOnce(exception.id)
        assertEquals("exception inherits master's 2 attendees", 2, exAttendees.size)
        assertEquals(
            setOf("mailto:alice@example.test", "mailto:bob@example.test"),
            exAttendees.map { it.address }.toSet()
        )
    }

    @Test
    fun `editSingleOccurrence persists an edited attendee set to the new exception only`() = runTest {
        // Per-occurrence add: the user adds a guest to just this occurrence.
        // The edited set lands on the exception's own rows; the master series
        // is unchanged.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        // The edited set is the series guest plus a new per-occurrence guest.
        val edited = listOf(
            Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
            Attendee(eventId = 0, address = "mailto:carol@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
        )

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Just this one", startTs = target.startTs, endTs = target.endTs
            ),
            isLocal = true,
            attendees = edited
        )

        val exAttendees = database.attendeesDao().getForEventOnce(exception.id)
        assertEquals(
            setOf("mailto:alice@example.test", "mailto:carol@example.test"),
            exAttendees.map { it.address }.toSet()
        )
        // Master series keeps only its original guest.
        val masterAttendees = database.attendeesDao().getForEventOnce(master.id)
        assertEquals(
            setOf("mailto:alice@example.test"),
            masterAttendees.map { it.address }.toSet()
        )
    }

    @Test
    fun `editSingleOccurrence with edited attendees replaces a re-edited exception's rows`() = runTest {
        // Re-editing an occurrence that already has an override: the new
        // edited set replaces the exception's existing attendee rows.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        // First edit creates the exception, seeded with the master set.
        eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(title = "First", startTs = target.startTs, endTs = target.endTs),
            isLocal = true
        )

        // Second edit adds a per-occurrence guest.
        val edited = listOf(
            Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
            Attendee(eventId = 0, address = "mailto:dave@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
        )
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Second", startTs = target.startTs, endTs = target.endTs),
            isLocal = true,
            attendees = edited
        )

        val exAttendees = database.attendeesDao().getForEventOnce(exception.id)
        assertEquals(
            setOf("mailto:alice@example.test", "mailto:dave@example.test"),
            exAttendees.map { it.address }.toSet()
        )
    }

    @Test
    fun `editSingleOccurrence with null attendees leaves a re-edited exception's rows untouched`() = runTest {
        // Characterization of current behavior: a re-edit that doesn't touch
        // attendees (null) must not clobber the exception's existing rows.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        // First edit creates the exception with a divergent per-occurrence set.
        eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(title = "First", startTs = target.startTs, endTs = target.endTs),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:erin@example.test", partstat = "NEEDS-ACTION", sortOrder = 0)
            )
        )

        // Second edit changes only the title (null attendees).
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Retitled", startTs = target.startTs, endTs = target.endTs),
            isLocal = true
        )

        val exAttendees = database.attendeesDao().getForEventOnce(exception.id)
        assertEquals(
            "null attendees must preserve the exception's existing divergent set",
            setOf("mailto:erin@example.test"),
            exAttendees.map { it.address }.toSet()
        )
    }

    @Test
    fun `editSingleOccurrence links occurrence to exception`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = true
        )

        // v15.0.6: Find by exceptionEventId since occurrence times are updated to exception's times
        val updatedOccurrence = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull(updatedOccurrence)
        assertEquals(exception.id, updatedOccurrence?.exceptionEventId)
        // Verify times were updated to exception event's times
        assertEquals(exception.startTs, updatedOccurrence?.startTs)
        assertEquals(exception.endTs, updatedOccurrence?.endTs)
    }

    @Test
    fun `editSingleOccurrence throws for non-recurring event`() = runTest {
        val singleEvent = eventWriter.createEvent(createBaseEvent(), isLocal = true)

        try {
            eventWriter.editSingleOccurrence(
                masterEventId = singleEvent.id,
                occurrenceTimeMs = singleEvent.startTs,
                modifiedEvent = createBaseEvent(),
                isLocal = true
            )
            assertTrue("Should have thrown exception", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not recurring") == true)
        }
    }

    @Test
    fun `editSingleOccurrence exception has same UID as master`() = runTest {
        // RFC 5545: Exception MUST have same UID as master, distinguished by RECURRENCE-ID
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = true
        )

        // Exception UID must equal master UID (not master.uid-timestamp)
        assertEquals(master.uid, exception.uid)
        assertFalse(exception.uid.contains("-${targetOccurrence.startTs}"))
    }

    @Test
    fun `editSingleOccurrence queues UPDATE on master not CREATE on exception`() = runTest {
        // Create synced master event
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        // Mark master as synced with server URL
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/test/master.ics",
            "etag123",
            System.currentTimeMillis()
        )
        // Clear any pending operations from creation
        database.pendingOperationsDao().deleteForEvent(master.id)

        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = false
        )

        // Pending operation should be on MASTER (UPDATE), not exception (CREATE)
        val masterOps = database.pendingOperationsDao().getForEvent(master.id)
        assertEquals(1, masterOps.size)
        assertEquals(PendingOperation.OPERATION_UPDATE, masterOps[0].operation)

        // Exception should have NO pending operations
        val exceptionOps = database.pendingOperationsDao().getForEvent(exception.id)
        assertEquals(0, exceptionOps.size)
    }

    @Test
    fun `editSingleOccurrence exception has SYNCED status`() = runTest {
        // Exception is bundled with master for sync, so should be SYNCED locally
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        database.eventsDao().markCreatedOnServer(
            master.id,
            "https://caldav.icloud.com/test/master.ics",
            "etag123",
            System.currentTimeMillis()
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = false
        )

        // Exception is bundled with master, so it's marked SYNCED locally
        assertEquals(SyncStatus.SYNCED, exception.syncStatus)
    }

    @Test
    fun `editSingleOccurrence re-editing exception preserves UID`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        // First edit
        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified Once"),
            isLocal = true
        )

        // Second edit of same occurrence
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified Twice"),
            isLocal = true
        )

        // Should be same event with preserved UID
        assertEquals(exception1.id, exception2.id)
        assertEquals(exception1.uid, exception2.uid)
        assertEquals(master.uid, exception2.uid)
        assertEquals("Modified Twice", exception2.title)
    }

    // Rescheduling a single occurrence is an organizer timing change, so
    // the override MUST advance SEQUENCE (RFC 5546 §2.1.4) — matching the
    // master-edit and this-and-future paths. The baseline is the pristine
    // occurrence (master projected onto this occurrence's time), so the
    // structural master→exception difference doesn't masquerade as a change.
    @Test
    fun `editSingleOccurrence bumps SEQUENCE when occurrence is rescheduled`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        assertEquals(0, master.sequence)
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            // Reproduce the coordinator's lambda: derive from master, shift time.
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                startTs = target.startTs + 3_600_000L,
                endTs = target.endTs + 3_600_000L,
            ),
            isLocal = false
        )

        assertEquals("rescheduled occurrence must bump SEQUENCE", 1, exception.sequence)
    }

    // A cosmetic-only single-occurrence edit (notes) must NOT bump SEQUENCE,
    // or attendees get re-notified for nothing.
    @Test
    fun `editSingleOccurrence does not bump SEQUENCE for notes-only change`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            // Same occurrence time, only the notes differ.
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                description = "Added an agenda",
                startTs = target.startTs,
                endTs = target.endTs,
            ),
            isLocal = false
        )

        assertEquals("notes-only edit must not bump SEQUENCE", 0, exception.sequence)
    }

    // A retitled occurrence is attendee-facing, so it MUST bump SEQUENCE.
    @Test
    fun `editSingleOccurrence bumps SEQUENCE for title-only change`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            // Same occurrence time, only the title differs.
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                title = "Renamed occurrence",
                startTs = target.startTs,
                endTs = target.endTs,
            ),
            isLocal = false
        )

        assertEquals("title-only edit must bump SEQUENCE", 1, exception.sequence)
    }

    // Re-editing an already-materialized exception with a fresh timing change
    // bumps relative to the exception's own SEQUENCE, not the master's.
    @Test
    fun `editSingleOccurrence bumps SEQUENCE when re-editing exception with new timing`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        // First edit: notes-only, establishes the exception at occurrence time
        // without bumping (SEQUENCE stays 0).
        val firstEdit = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                description = "First agenda",
                startTs = target.startTs,
                endTs = target.endTs,
            ),
            isLocal = false
        )
        assertEquals("notes-only first edit should not bump", 0, firstEdit.sequence)

        // Second edit of the same occurrence: shift the time.
        val secondEdit = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                description = "First agenda",
                startTs = target.startTs + 3_600_000L,
                endTs = target.endTs + 3_600_000L,
            ),
            isLocal = false
        )

        assertEquals("re-edit timing change must bump exception SEQUENCE", 1, secondEdit.sequence)

        // Third edit, another time shift: the exception's own counter must
        // keep climbing (1 -> 2), not re-derive from the master's sequence.
        val thirdEdit = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                description = "First agenda",
                startTs = target.startTs + 7_200_000L,
                endTs = target.endTs + 7_200_000L,
            ),
            isLocal = false
        )

        assertEquals("successive re-edits must climb monotonically", 2, thirdEdit.sequence)
    }

    // Guards the pristine-occurrence projection for all-day masters: a
    // notes-only edit must not bump SEQUENCE even though the projection
    // recomputes endTs from the master's span and carries isAllDay. If the
    // projection ever desynced from the modified event on these fields, this
    // would false-fire and re-notify attendees.
    @Test
    fun `editSingleOccurrence does not bump SEQUENCE for notes-only change on all-day master`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5", isAllDay = true),
            isLocal = false
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = master.copy(
                id = 0,
                uid = "",
                rrule = null,
                description = "Added an agenda",
                startTs = target.startTs,
                endTs = target.endTs,
            ),
            isLocal = false
        )

        assertEquals("all-day notes-only edit must not bump SEQUENCE", 0, exception.sequence)
    }

    // ========== Delete Single Occurrence (EXDATE) ==========

    @Test
    fun `deleteSingleOccurrence adds EXDATE to master`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        assertNull(master.exdate)

        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]
        eventWriter.deleteSingleOccurrence(master.id, targetOccurrence.startTs, isLocal = true)

        val updated = database.eventsDao().getById(master.id)
        assertNotNull(updated?.exdate)
        assertTrue(updated!!.exdate!!.isNotBlank())
    }

    @Test
    fun `deleteSingleOccurrence marks occurrence as cancelled`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        eventWriter.deleteSingleOccurrence(master.id, targetOccurrence.startTs, isLocal = true)

        val updated = database.occurrencesDao().getOccurrenceAtTime(master.id, targetOccurrence.startTs)
        assertTrue(updated?.isCancelled == true)
    }

    @Test
    fun `deleteSingleOccurrence on a previously-edited occurrence cancels the right row`() = runTest {
        // Regression: a recurring occurrence the user has already edited
        // (creating an exception event) had its master-side occurrence row
        // updated to the EXCEPTION's modified start_ts. When the user
        // later deletes that exception via the form's Delete button,
        // EventWriter.deleteSingleOccurrence(masterId, originalInstanceTime)
        // calls cancelOccurrence which uses a 60-second time-tolerance
        // match — but the row no longer lives at originalInstanceTime,
        // it lives at the exception's modified time.
        //
        // The match silently fails: is_cancelled stays 0, exception_event_id
        // points at a now-deleted row, and the day card renders the slot
        // again under the master's title at the exception's modified time.
        // Visually identical to "the recurring event came back, no longer
        // an exception."
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val originalSlot = database.occurrencesDao().getForEvent(master.id)[2]
        val originalInstanceTime = originalSlot.startTs

        // Step 1: edit the occurrence → creates exception, moves the
        // master's occurrence row to the exception's modified time.
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = originalInstanceTime,
            modifiedEvent = Event(
                uid = "",
                calendarId = master.calendarId,
                title = "moved",
                startTs = originalInstanceTime + 3 * 3600_000L,
                endTs = originalInstanceTime + 4 * 3600_000L,
                dtstamp = System.currentTimeMillis()
            ),
            isLocal = true
        )

        // Sanity: the linked row is at the exception's modified time, not
        // the original instance time.
        val linkedRow = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull(linkedRow)
        assertEquals(exception.startTs, linkedRow!!.startTs)

        // Step 2: delete the (now-edited) occurrence — same path the form's
        // Delete button hits via handleRoomEventFormDelete.
        eventWriter.deleteSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = originalInstanceTime,
            isLocal = true
        )

        // The exception event row is gone (existing behavior, not the bug).
        assertNull(database.eventsDao().getById(exception.id))

        // The bug surface: the master's occurrence row at the exception's
        // modified time must now be marked cancelled OR removed entirely.
        // Today, neither happens — the row at exception.startTs sits there
        // with is_cancelled=0 and a dangling exception_event_id, making
        // the day card render the master's title at the modified time.
        val survivors = database.occurrencesDao().getForEvent(master.id)
        val staleRow = survivors.firstOrNull { it.startTs == exception.startTs }
        assertTrue(
            "After delete, the row at the exception's modified time must " +
                "be cancelled or absent (got: $staleRow)",
            staleRow == null || staleRow.isCancelled
        )

        // And no row should still point at the deleted exception.
        assertNull(database.occurrencesDao().getByExceptionEventId(exception.id))
    }

    @Test
    fun `deleteSingleOccurrence keeps other occurrences intact`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        eventWriter.deleteSingleOccurrence(master.id, targetOccurrence.startTs, isLocal = true)

        // All 5 still exist, just one is cancelled
        val allOccurrences = database.occurrencesDao().getForEvent(master.id)
        assertEquals(5, allOccurrences.size)
        assertEquals(1, allOccurrences.count { it.isCancelled })
    }

    // ========== Split Series ==========

    @Test
    fun `splitSeries truncates master and creates new event`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val splitPoint = occurrences[5].startTs // Split at 6th occurrence

        val newEvent = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitPoint,
            modifiedEvent = createBaseEvent().copy(
                title = "Future Series",
                rrule = "FREQ=DAILY;COUNT=10"
            ),
            isLocal = true
        )

        // COUNT-based RRULE: master keeps COUNT=pastCount, never UNTIL
        // (master keeps COUNT=pastCount, never UNTIL, on the split path
        // that preserves total instance count).
        val updatedMaster = database.eventsDao().getById(master.id)
        assertEquals("FREQ=DAILY;COUNT=5", updatedMaster?.rrule)

        // New event should exist
        assertNotNull(newEvent)
        assertEquals("Future Series", newEvent.title)
        assertNull(newEvent.originalEventId) // Not an exception
    }

    @Test
    fun `splitSeries removes occurrences after split point`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val splitPoint = occurrences[5].startTs

        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitPoint,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )

        // Master should have fewer occurrences
        val masterOccurrences = database.occurrencesDao().getForEvent(master.id)
        assertTrue(masterOccurrences.size < 10)
        assertTrue(masterOccurrences.all { it.startTs < splitPoint })
    }

    // ========== Delete This and Future ==========

    @Test
    fun `deleteThisAndFuture truncates series`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val deleteFrom = occurrences[5].startTs

        eventWriter.deleteThisAndFuture(master.id, deleteFrom, isLocal = true)

        // Master should have UNTIL
        val updated = database.eventsDao().getById(master.id)
        assertTrue(updated?.rrule?.contains("UNTIL=") == true)

        // Fewer occurrences
        val remaining = database.occurrencesDao().getForEvent(master.id)
        assertTrue(remaining.size < 10)
        assertTrue(remaining.all { it.startTs < deleteFrom })
    }

    @Test
    fun `deleteThisAndFuture deletes entire event if from first occurrence`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )

        eventWriter.deleteThisAndFuture(master.id, master.startTs, isLocal = true)

        // Event should be deleted
        assertNull(database.eventsDao().getById(master.id))
    }

    @Test
    fun `deleteThisAndFuture uses date-only UNTIL for all-day events`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                isAllDay = true
            ),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val deleteFrom = occurrences[5].startTs

        eventWriter.deleteThisAndFuture(master.id, deleteFrom, isLocal = true)

        val updated = database.eventsDao().getById(master.id)
        assertNotNull("Event should exist after deleteThisAndFuture", updated)
        val rrule = updated!!.rrule!!
        assertTrue("RRULE should contain UNTIL", rrule.contains("UNTIL="))
        // Date-only format: 8 digits, no 'T' (e.g., UNTIL=20260115)
        val untilMatch = Regex("UNTIL=([^;]+)").find(rrule)
        assertNotNull("UNTIL should be in RRULE: $rrule", untilMatch)
        val untilValue = untilMatch!!.groupValues[1]
        assertFalse("UNTIL should be date-only (no T) for all-day: $untilValue",
            untilValue.contains("T"))
        assertEquals("UNTIL should be 8-digit date", 8, untilValue.length)
    }

    @Test
    fun `splitSeries uses date-only UNTIL for all-day unbounded events`() = runTest {
        // Unbounded RRULE forces the UNTIL branch (the COUNT branch
        // preserves COUNT and never emits UNTIL).
        val master = eventWriter.createEvent(
            createBaseEvent().copy(
                rrule = "FREQ=DAILY",
                isAllDay = true
            ),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        // Sync window seeds at least a few occurrences for an unbounded
        // daily; index 5 lands well before any horizon-induced trim.
        val splitFrom = occurrences[5].startTs

        eventWriter.splitSeries(
            master.id, splitFrom,
            createBaseEvent().copy(rrule = "FREQ=DAILY", isAllDay = true),
            isLocal = true
        )

        val updated = database.eventsDao().getById(master.id)
        assertNotNull("Event should exist after splitSeries", updated)
        val rrule = updated!!.rrule!!
        val untilMatch = Regex("UNTIL=([^;]+)").find(rrule)
        assertNotNull("UNTIL should be in RRULE: $rrule", untilMatch)
        val untilValue = untilMatch!!.groupValues[1]
        assertFalse("UNTIL should be date-only for all-day: $untilValue",
            untilValue.contains("T"))
    }

    // ========== Split Series — total-count-preserving semantics ==========

    @Test
    fun `splitSeries preserves total count for COUNT-based series`() = runTest {
        // Master has COUNT=10. Split at occurrence index 2 means 2 past
        // occurrences (indices 0 and 1) before splitTime. Master should
        // keep COUNT=2; new series should keep COUNT=8. Total unchanged.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val splitFrom = occurrences[2].startTs

        val newEvent = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitFrom,
            // Caller passes the master's own RRULE — writer is
            // responsible for splitting the COUNT.
            modifiedEvent = createBaseEvent().copy(
                title = "Future series",
                rrule = "FREQ=DAILY;COUNT=10"
            ),
            isLocal = true
        )

        val updatedMaster = database.eventsDao().getById(master.id)
        assertEquals("FREQ=DAILY;COUNT=2", updatedMaster?.rrule)
        assertEquals("FREQ=DAILY;COUNT=8", newEvent.rrule)
        assertNull("master should not contain UNTIL on COUNT branch",
            Regex("UNTIL=").find(updatedMaster!!.rrule!!))
    }

    @Test
    fun `splitSeries on first occurrence updates master in place`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val priorMasterId = master.id

        val result = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = master.startTs,
            modifiedEvent = master.copy(title = "Renamed via this-and-future on first"),
            isLocal = true
        )

        // No new event row — split returns the master itself with
        // changes applied (mirrors deleteThisAndFuture's first-occ
        // shortcut at line 520).
        assertEquals(priorMasterId, result.id)
        val updated = database.eventsDao().getById(priorMasterId)
        assertEquals("Renamed via this-and-future on first", updated?.title)
        // Original RRULE survives — no truncation.
        assertEquals("FREQ=DAILY;COUNT=10", updated?.rrule)
    }

    @Test
    fun `splitSeries with pastCount zero falls back to ALL_EVENTS update`() = runTest {
        // splitTime > masterStart but < first expansion +interval would
        // produce master COUNT=0 (invalid). Helper produces COUNT=0;
        // splitSeries must detect and fall back to in-place ALL_EVENTS.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val priorMasterId = master.id

        val result = eventWriter.splitSeries(
            masterEventId = master.id,
            // 1 second after master start — strictly greater than
            // masterStart so the first-occurrence guard does NOT fire,
            // but no daily expansion has materialized yet (pastCount=0).
            splitTimeMs = master.startTs + 1L,
            modifiedEvent = master.copy(title = "pastCount zero edge"),
            isLocal = true
        )

        assertEquals(priorMasterId, result.id)
        val updated = database.eventsDao().getById(priorMasterId)
        assertEquals("pastCount zero edge", updated?.title)
        // No invalid COUNT=0 emitted, no UNTIL — original RRULE.
        assertEquals("FREQ=DAILY;COUNT=10", updated?.rrule)
    }

    @Test
    fun `splitSeries deletes future exception children`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val futureOccTs = occurrences[5].startTs

        // Create an exception event for occurrence 5 (future relative
        // to a split at occurrence 3).
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = futureOccTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Exception at occ 5",
                startTs = futureOccTs,
                endTs = futureOccTs + 3600_000,
                originalEventId = master.id,
                originalInstanceTime = futureOccTs
            ),
            isLocal = true
        )
        assertNotNull("exception should exist before split",
            database.eventsDao().getById(exception.id))

        // Split at occurrence 3 — exception at 5 is in the truncated
        // range and must be cleaned up (same shape as the cleanup
        // deleteThisAndFuture performs).
        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = occurrences[3].startTs,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )

        assertNull("exception in truncated range should be deleted",
            database.eventsDao().getById(exception.id))
    }

    @Test
    fun `splitSeries copies attendees to new series`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        // Attendees live in their own Room table; writer paths must
        // populate them via replaceForEvent rather than relying on
        // eventsDao.insert(Event), which doesn't touch the attendee
        // table.
        val attendees = listOf(
            Attendee(
                eventId = master.id,
                address = "alice.synthetic@example.test",
                displayName = "Alice Synthetic",
                role = "REQ-PARTICIPANT",
                partstat = "ACCEPTED",
                cutype = "INDIVIDUAL",
                rsvp = true,
                sortOrder = 0
            ),
            Attendee(
                eventId = master.id,
                address = "bob.synthetic@example.test",
                displayName = "Bob Synthetic",
                role = "REQ-PARTICIPANT",
                partstat = "NEEDS-ACTION",
                cutype = "INDIVIDUAL",
                rsvp = true,
                sortOrder = 1
            )
        )
        database.attendeesDao().replaceForEvent(master.id, attendees)

        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = occurrences[3].startTs,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )

        val onNewSeries = database.attendeesDao().getForEventOnce(newSeries.id)
        assertEquals("new series should carry both attendees",
            2, onNewSeries.size)
        val addresses = onNewSeries.map { it.address }.toSet()
        assertTrue(addresses.contains("alice.synthetic@example.test"))
        assertTrue(addresses.contains("bob.synthetic@example.test"))
    }

    @Test
    fun `splitSeries with explicit attendees writes the supplied set to the new series`() = runTest {
        // The this-and-future attendee-edit path: the user added a guest, so
        // the new series must carry the EDITED set, not a verbatim copy of the
        // master's attendees.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val edited = listOf(
            Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
            Attendee(eventId = 0, address = "mailto:carol@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
        )

        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = occurrences[4].startTs,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true,
            attendees = edited
        )

        val onNewSeries = database.attendeesDao().getForEventOnce(newSeries.id).map { it.address }.toSet()
        assertEquals(
            "new series carries the edited set including the added guest",
            setOf("mailto:alice@example.test", "mailto:carol@example.test"),
            onNewSeries
        )
        // The truncated (past) master keeps its original single attendee.
        val onMaster = database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        assertEquals(
            "past master retains its original attendee set",
            setOf("mailto:alice@example.test"),
            onMaster
        )
    }

    @Test
    fun `splitSeries at first occurrence collapses to in-place update and applies supplied attendees`() = runTest {
        // splitTimeMs <= masterStart collapses to updateMasterInPlace. The
        // edited attendee set must still land on the master — not be dropped
        // by the collapse branch.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val edited = listOf(
            Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
            Attendee(eventId = 0, address = "mailto:dan@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
        )

        val result = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = master.startTs, // first occurrence → collapse
            modifiedEvent = master.copy(title = "Renamed"),
            isLocal = true,
            attendees = edited
        )

        assertEquals("collapse updates the master in place", master.id, result.id)
        val onMaster = database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        assertEquals(
            "first-occurrence collapse applies the edited set, not a drop",
            setOf("mailto:alice@example.test", "mailto:dan@example.test"),
            onMaster
        )
    }

    @Test
    fun `updateEvent all-events attendee change cascades onto existing exception rows`() = runTest {
        // An ALL_EVENTS attendee edit lands on updateEvent. Existing time-only
        // exceptions were seeded with the master's OLD attendee list; the new
        // set must cascade onto them so the whole series stays consistent
        // (safe in this phase — no exception has a divergent set yet).
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        // Create a time-only exception (seeds master's single attendee).
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[2].startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Moved",
                startTs = occurrences[2].startTs + 3_600_000,
                endTs = occurrences[2].endTs + 3_600_000
            ),
            isLocal = true
        )
        assertEquals(1, database.attendeesDao().getForEventOnce(exception.id).size)

        // ALL_EVENTS edit: add a guest to the master.
        val edited = listOf(
            Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
            Attendee(eventId = 0, address = "mailto:erin@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
        )
        eventWriter.updateEvent(
            event = database.eventsDao().getById(master.id)!!.copy(title = "Series Renamed"),
            isLocal = true,
            attendees = edited
        )

        val onException = database.attendeesDao().getForEventOnce(exception.id).map { it.address }.toSet()
        assertEquals(
            "all-events attendee change cascades onto the existing exception",
            setOf("mailto:alice@example.test", "mailto:erin@example.test"),
            onException
        )
    }

    @Test
    fun `updateEvent all-events change does NOT clobber a customized per-occurrence override`() = runTest {
        // Once a guest is added to ONE occurrence, that override holds a
        // deliberately-divergent set. A later all-events edit must leave it
        // alone rather than overwriting the user's per-occurrence customization.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        // Customize one occurrence: add a per-occurrence guest (divergent set).
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[2].startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Just this one", startTs = occurrences[2].startTs, endTs = occurrences[2].endTs
            ),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:carol@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )

        // ALL_EVENTS edit: add a DIFFERENT guest to the whole series.
        eventWriter.updateEvent(
            event = database.eventsDao().getById(master.id)!!.copy(title = "Series Renamed"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:erin@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )

        val onException = database.attendeesDao().getForEventOnce(exception.id).map { it.address }.toSet()
        assertEquals(
            "the customized override keeps its own divergent set",
            setOf("mailto:alice@example.test", "mailto:carol@example.test"),
            onException
        )
    }

    @Test
    fun `updateEvent all-events change still cascades onto a seeded override with stamped PARTSTAT`() = runTest {
        // A seeded (non-customized) override matches the series addresses but
        // may carry server-stamped PARTSTAT differences. Divergence is judged
        // by address SET only, so it must still cascade.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occurrences[2].startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Moved", startTs = occurrences[2].startTs + 3_600_000, endTs = occurrences[2].endTs + 3_600_000
            ),
            isLocal = true
        )
        // Simulate a server-stamped PARTSTAT on the seeded override (same
        // address, different status) — must NOT be mistaken for customization.
        val seededRow = database.attendeesDao().getForEventOnce(exception.id).single()
        database.attendeesDao().replaceForEvent(
            exception.id,
            listOf(seededRow.copy(id = 0, partstat = "ACCEPTED"))
        )

        eventWriter.updateEvent(
            event = database.eventsDao().getById(master.id)!!.copy(title = "Series Renamed"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:erin@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )

        val onException = database.attendeesDao().getForEventOnce(exception.id).map { it.address }.toSet()
        assertEquals(
            "a seeded override (matching addresses) still cascades despite stamped PARTSTAT",
            setOf("mailto:alice@example.test", "mailto:erin@example.test"),
            onException
        )
    }

    @Test
    fun `updateEvent tombstones a removed synced guest into pending_cancels`() = runTest {
        // Removing an invited guest must enqueue a CANCEL for them (carrying the
        // captured delivery context) rather than silently dropping the row.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", scheduleAgent = "CLIENT", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        // Mark it as pushed so the removed guest counts as "synced" (on the wire).
        database.eventsDao().markCreatedOnServer(master.id, "https://caldav.icloud.com/test/${master.uid}.ics", "etag-1", System.currentTimeMillis())

        // Save with bob removed (survivor set = alice only).
        eventWriter.updateEvent(
            event = database.eventsDao().getById(master.id)!!.copy(title = "Renamed"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", scheduleAgent = "CLIENT", sortOrder = 0)
            )
        )

        val cancels = database.pendingCancelsDao().getForEvent(master.id)
        assertEquals(1, cancels.size)
        assertEquals("mailto:bob@example.test", cancels[0].address)
        assertNull("all-events removal has no recurrence scope", cancels[0].recurrenceId)
        // Survivor set persisted; removed guest gone from attendees.
        assertEquals(
            setOf("mailto:alice@example.test"),
            database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        )
    }

    @Test
    fun `updateEvent does not tombstone a removed never-synced guest`() = runTest {
        // A guest added locally and removed before the event ever synced was
        // never on the wire — no CANCEL is owed.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        // NOT marked created-on-server: caldavUrl stays null (never synced).

        eventWriter.updateEvent(
            event = database.eventsDao().getById(master.id)!!,
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0)
            )
        )

        assertEquals(
            "a never-synced removed guest owes no CANCEL",
            0, database.pendingCancelsDao().getForEvent(master.id).size
        )
    }

    @Test
    fun `updateEvent re-removing the same guest does not duplicate the pending cancel`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        database.eventsDao().markCreatedOnServer(master.id, "https://caldav.icloud.com/test/${master.uid}.ics", "etag-1", System.currentTimeMillis())
        val survivors = listOf(
            Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
        )

        eventWriter.updateEvent(database.eventsDao().getById(master.id)!!, isLocal = false, attendees = survivors)
        eventWriter.updateEvent(database.eventsDao().getById(master.id)!!, isLocal = false, attendees = survivors)

        assertEquals(1, database.pendingCancelsDao().getForEvent(master.id).size)
    }

    @Test
    fun `editSingleOccurrence removal enqueues a per-occurrence CANCEL scoped to the instance`() = runTest {
        // Removing a guest from just one occurrence cancels them for that
        // instance only (recurrence_id set); the master series keeps them.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        database.eventsDao().markCreatedOnServer(master.id, "https://caldav.icloud.com/test/${master.uid}.ics", "etag-1", System.currentTimeMillis())
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        // Edit just this occurrence, dropping bob (survivors = alice only).
        eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = target.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Just this", startTs = target.startTs, endTs = target.endTs),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )

        val cancels = database.pendingCancelsDao().getForEvent(master.id)
        assertEquals(1, cancels.size)
        assertEquals("mailto:bob@example.test", cancels[0].address)
        assertEquals("per-occurrence cancel is scoped to the instance", target.startTs, cancels[0].recurrenceId)
        // Master series still has both guests.
        assertEquals(
            setOf("mailto:alice@example.test", "mailto:bob@example.test"),
            database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        )
    }

    @Test
    fun `editThisAndFuture removal leaves the guest off the new series and enqueues no cancel`() = runTest {
        // This-and-future via split: the new series is a fresh UID the guest was
        // never on, so they are simply absent going forward (no explicit CANCEL);
        // the past series retains them.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        database.eventsDao().markCreatedOnServer(master.id, "https://caldav.icloud.com/test/${master.uid}.ics", "etag-1", System.currentTimeMillis())
        val splitTarget = database.occurrencesDao().getForEvent(master.id)[4]

        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitTarget.startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Future", startTs = splitTarget.startTs, endTs = splitTarget.endTs,
                rrule = "FREQ=DAILY;COUNT=6"
            ),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )

        // New series lacks bob; past series keeps both; no pending cancel.
        assertEquals(
            setOf("mailto:alice@example.test"),
            database.attendeesDao().getForEventOnce(newSeries.id).map { it.address }.toSet()
        )
        assertEquals(
            setOf("mailto:alice@example.test", "mailto:bob@example.test"),
            database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        )
        assertEquals(0, database.pendingCancelsDao().getForEvent(master.id).size)
        assertEquals(0, database.pendingCancelsDao().getForEvent(newSeries.id).size)
    }

    @Test
    fun `updateEvent on a non-recurring event does not touch exception rows`() = runTest {
        // The cascade must be gated on recurring-master; a plain non-recurring
        // attendee edit must not run the exception cascade (no-op, no surprise
        // writes). Verified by: an unrelated recurring series' exception is
        // untouched when we update a separate non-recurring event.
        val recurringMaster = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val occ = database.occurrencesDao().getForEvent(recurringMaster.id)
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = recurringMaster.id,
            occurrenceTimeMs = occ[1].startTs,
            modifiedEvent = createBaseEvent().copy(
                title = "Moved",
                startTs = occ[1].startTs + 3_600_000,
                endTs = occ[1].endTs + 3_600_000
            ),
            isLocal = true
        )
        val before = database.attendeesDao().getForEventOnce(exception.id).map { it.address }.toSet()

        val nonRecurring = eventWriter.createEvent(createBaseEvent(), isLocal = true)
        eventWriter.updateEvent(
            event = nonRecurring.copy(title = "Edited"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:frank@example.test", partstat = "NEEDS-ACTION", sortOrder = 0)
            )
        )

        val after = database.attendeesDao().getForEventOnce(exception.id).map { it.address }.toSet()
        assertEquals("unrelated recurring exception is untouched by a non-recurring update", before, after)
    }

    @Test
    fun `splitSeries with EXDATE in past range counts rule recurrences not survivors`() = runTest {
        // RFC 5545 §3.3.10: COUNT counts rule recurrences, not the
        // post-EXDATE survivor count. Splitting at occurrence index 5
        // of a COUNT=10 series with one EXDATE in [start, splitTime)
        // must yield master COUNT=5 (not 4 — that would silently drop
        // a past visible occurrence on re-expansion since EXDATE is
        // applied after the COUNT cap).
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitFrom = occurrences[5].startTs
        // EXDATE on the 3rd occurrence — strictly before splitFrom.
        // Stored as the millis-string CSV form the engine accepts.
        val masterWithExdate = database.eventsDao().getById(master.id)!!
            .copy(exdate = occurrences[2].startTs.toString())
        database.eventsDao().update(masterWithExdate)

        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitFrom,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )

        val updatedMaster = database.eventsDao().getById(master.id)!!
        // 5 rule recurrences before splitFrom (occurrences 0-4); 1 of
        // those is EXDATE'd, leaving 4 visible — but COUNT must reflect
        // the rule recurrences (5), so re-expansion yields 5 candidates
        // and the EXDATE filters to 4 visible. Without this fix master
        // gets COUNT=4 → re-expansion yields 4 candidates → after
        // EXDATE filter only 3 visible (lost an occurrence).
        assertEquals("FREQ=DAILY;COUNT=5", updatedMaster.rrule)
    }

    @Test
    fun `splitSeries with pastCount equal total falls back to ALL_EVENTS`() = runTest {
        // splitTimeMs after the last occurrence — pastCount == total.
        // Without a guard the helper would emit COUNT=0 (RFC 5545
        // forbids; ical4j won't expand) on the new series.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        // 1 day past the final occurrence's start — pastCount=5 == total.
        val splitFrom = occurrences.last().startTs + 86_400_000L
        val priorMasterId = master.id

        val result = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitFrom,
            modifiedEvent = master.copy(title = "Edited past end"),
            isLocal = true
        )

        // No new event row — split fell back to in-place ALL_EVENTS.
        assertEquals(priorMasterId, result.id)
        val updated = database.eventsDao().getById(priorMasterId)!!
        assertEquals("Edited past end", updated.title)
        // Original RRULE preserved — no truncation.
        assertEquals("FREQ=DAILY;COUNT=5", updated.rrule)
    }

    @Test
    fun `splitSeries bumps SEQUENCE on first-occurrence in-place fallback`() = runTest {
        // updateMasterInPlace must bump SEQUENCE for iTIP correctness
        // (RFC 5545 §3.8.7.4) when fields material to attendees change
        // — matches the public updateEvent path's behavior at line 121.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = false
        )
        val priorSequence = master.sequence

        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = master.startTs, // first-occurrence guard
            // Change timing to trigger the bump (matches updateEvent's
            // rruleChanged || timingChanged predicate).
            modifiedEvent = master.copy(
                title = "Renamed",
                startTs = master.startTs + 3_600_000L,
                endTs = master.endTs + 3_600_000L,
            ),
            isLocal = false
        )

        val updated = database.eventsDao().getById(master.id)!!
        assertEquals("SEQUENCE must bump on iTIP-relevant change",
            priorSequence + 1, updated.sequence)
    }

    @Test
    fun `splitSeries respects caller RRULE change on new series`() = runTest {
        // When the caller's modifiedEvent.rrule differs from the master's
        // rrule, the caller is intentionally changing the recurrence
        // pattern as part of "this and future." Honor the caller's rrule
        // rather than the helper's COUNT/UNTIL-rewritten copy of the
        // master's pattern.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitFrom = occurrences[2].startTs

        // Caller provides a different recurrence pattern (DAILY) and
        // would expect it to land on the new series rather than be
        // silently rewritten to the master's pattern.
        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitFrom,
            modifiedEvent = createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )

        assertEquals("Caller-supplied RRULE must win when it differs from master",
            "FREQ=DAILY;COUNT=5", newSeries.rrule)
    }

    // ========== Move Calendar ==========

    @Test
    fun `moveEventToCalendar updates calendar ID`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = true)
        assertEquals(testCalendarId, event.calendarId)

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val moved = database.eventsDao().getById(event.id)
        assertEquals(localCalendarId, moved?.calendarId)
    }

    @Test
    fun `moveEventToCalendar updates occurrence calendar IDs`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=3"),
            isLocal = true
        )

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertTrue(occurrences.all { it.calendarId == localCalendarId })
    }

    @Test
    fun `moveEventToCalendar does not change occurrence count`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val originalCount = database.occurrencesDao().getForEvent(event.id).size
        assertEquals(5, originalCount)

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val afterMoveCount = database.occurrencesDao().getForEvent(event.id).size
        assertEquals(originalCount, afterMoveCount)
    }

    @Test
    fun `moveEventToCalendar preserves occurrence IDs`() = runTest {
        val event = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=3"),
            isLocal = true
        )
        val originalIds = database.occurrencesDao().getForEvent(event.id).map { it.id }.sorted()

        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        val afterMoveIds = database.occurrencesDao().getForEvent(event.id).map { it.id }.sorted()
        assertEquals(originalIds, afterMoveIds)
    }

    @Test
    fun `moveEventToCalendar iCloud to iCloud queues MOVE operation`() = runTest {
        // Create synced event with caldavUrl
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        // Simulate that event was synced to server
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )
        // Clear any pending CREATE from initial create
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to different iCloud calendar (auto-detects both are CalDAV)
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // Verify MOVE operation queued with correct data
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals("https://caldav.icloud.com/test/event123.ics", ops[0].targetUrl)
        assertEquals(iCloudCalendar2Id, ops[0].targetCalendarId)
    }

    // ---- cross-account move is blocked for events with attendees ----
    // Moving an attendee-bearing event to a different account would send the
    // wrong ORGANIZER (the source account's address), which scheduling servers
    // reject/rewrite — re-inviting or stripping guests. Block it; the user can
    // duplicate into the other account instead (fresh UID, correct organizer).

    @Test
    fun `moveEventToCalendar cross-account with attendees is rejected`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id, "https://caldav.icloud.com/test/e.ics", "etag", System.currentTimeMillis()
        )
        database.attendeesDao().replaceForEvent(
            event.id,
            listOf(Attendee(eventId = event.id, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0))
        )
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Cross-account move (test@icloud.com -> other@nextcloud.test) must throw.
        var threw = false
        try {
            eventWriter.moveEventToCalendar(event.id, otherAccountCalendarId)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("cross-account move of an attendee event must be rejected", threw)

        // The event must stay put (calendar unchanged, no move op queued).
        val after = database.eventsDao().getById(event.id)
        assertEquals(testCalendarId, after?.calendarId)
        assertTrue(database.pendingOperationsDao().getForEvent(event.id).isEmpty())
    }

    @Test
    fun `moveEventToCalendar cross-account without attendees is allowed`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id, "https://caldav.icloud.com/test/e.ics", "etag", System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(event.id)

        // No attendees -> cross-account move proceeds (linked CREATE + DELETE).
        eventWriter.moveEventToCalendar(event.id, otherAccountCalendarId)

        assertEquals(otherAccountCalendarId, database.eventsDao().getById(event.id)?.calendarId)
        assertTrue(database.pendingOperationsDao().getForEvent(event.id).isNotEmpty())
    }

    @Test
    fun `moveEventToCalendar cross-account rejected when only an EXCEPTION has attendees`() = runTest {
        // Recurring master with NO attendees, but a per-occurrence edit adds a
        // guest to one occurrence (an exception row with its own attendee rows).
        // The cross-account guard must count exception attendees too, or the
        // move slips through and mis-schedules the exception's guest.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        database.eventsDao().markCreatedOnServer(
            master.id, "https://caldav.icloud.com/test/m.ics", "etag", System.currentTimeMillis()
        )
        val occTs = master.startTs + 86_400_000L // second occurrence
        eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = occTs,
            modifiedEvent = master.copy(rrule = null, title = "Just this one"),
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:guest@example.test", partstat = "NEEDS-ACTION", sortOrder = 0)
            )
        )
        // Master itself has zero attendees.
        assertEquals(0, database.attendeesDao().countForEvent(master.id))
        database.pendingOperationsDao().deleteForEvent(master.id)

        var threw = false
        try {
            eventWriter.moveEventToCalendar(master.id, otherAccountCalendarId)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("cross-account move must be rejected when an exception carries attendees", threw)
        assertEquals(testCalendarId, database.eventsDao().getById(master.id)?.calendarId)
    }

    @Test
    fun `moveEventToCalendar same-account with attendees is allowed`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id, "https://caldav.icloud.com/test/e.ics", "etag", System.currentTimeMillis()
        )
        database.attendeesDao().replaceForEvent(
            event.id,
            listOf(Attendee(eventId = event.id, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0))
        )
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Same-account move (two iCloud calendars) is safe — attendees ride along
        // on the same eventId, no re-invite (proven by MoveReInviteProbeTest).
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        assertEquals(iCloudCalendar2Id, database.eventsDao().getById(event.id)?.calendarId)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
    }

    @Test
    fun `moveEventToCalendar local to iCloud queues CREATE only`() = runTest {
        // Create local event (never synced)
        val event = eventWriter.createEvent(createBaseEvent().copy(
            calendarId = localCalendarId
        ), isLocal = true)

        // Move to iCloud calendar (auto-detects local→synced)
        eventWriter.moveEventToCalendar(event.id, testCalendarId)

        // Verify CREATE operation queued (no MOVE since no old URL)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_CREATE, ops[0].operation)
        assertNull(ops[0].targetUrl)  // No old URL for local events
    }

    @Test
    fun `moveEventToCalendar iCloud to local queues DELETE`() = runTest {
        // Create synced event with caldavUrl
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to local calendar (auto-detects synced→local, queues DELETE)
        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        // Verify DELETE operation queued with sourceCalendarId
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_DELETE, ops[0].operation)
        assertEquals(testCalendarId, ops[0].sourceCalendarId)
    }

    @Test
    fun `moveEventToCalendar cancels existing pending operations`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        // Simulate pending UPDATE
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = event.id,
                operation = PendingOperation.OPERATION_UPDATE
            )
        )

        // Move to different calendar
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // Verify old UPDATE is gone, replaced with CREATE or MOVE
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertTrue(ops[0].operation == PendingOperation.OPERATION_CREATE ||
                   ops[0].operation == PendingOperation.OPERATION_MOVE)
    }

    @Test
    fun `moveEventToCalendar clears caldavUrl and etag`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )

        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        val moved = database.eventsDao().getById(event.id)
        assertNull(moved?.caldavUrl)
        assertNull(moved?.etag)
        assertEquals(SyncStatus.PENDING_CREATE, moved?.syncStatus)
    }

    @Test
    fun `moveEventToCalendar stores targetUrl in MOVE operation`() = runTest {
        val oldUrl = "https://caldav.icloud.com/test/specific-event.ics"
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(event.id, oldUrl, "etag", System.currentTimeMillis())
        database.pendingOperationsDao().deleteForEvent(event.id)

        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // Verify the old URL is stored in the operation for DELETE
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals(oldUrl, ops[0].targetUrl)  // Critical: URL captured before cleared
    }

    // ========== replyRsvp ==========

    @Test
    fun `replyRsvp captures caldavUrl as targetUrl on the queued PendingOperation`() = runTest {
        val syncedUrl = "https://caldav.icloud.com/test/rsvp-event.ics"
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(event.id, syncedUrl, "etag-1", System.currentTimeMillis())
        database.pendingOperationsDao().deleteForEvent(event.id)
        database.attendeesDao().replaceForEvent(
            event.id,
            listOf(
                Attendee(
                    eventId = event.id,
                    address = "mailto:test@icloud.com",
                    partstat = "NEEDS-ACTION"
                )
            )
        )
        val account = database.accountsDao().getById(database.calendarsDao().getById(testCalendarId)!!.accountId)!!

        val ok = eventWriter.replyRsvp(event.id, account, "ACCEPTED")

        assertTrue(ok)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        val op = ops[0]
        assertEquals(PendingOperation.OPERATION_UPDATE, op.operation)
        assertTrue(op.partstatOnly)
        assertEquals("ACCEPTED", op.partstatTarget)
        // Critical: caldavUrl captured at queue time so a future code path that
        // clears Event.caldavUrl can't silently turn the queued RSVP into a no-op.
        assertEquals(syncedUrl, op.targetUrl)
    }

    @Test
    fun `replyRsvp on never-synced event queues with null targetUrl`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.pendingOperationsDao().deleteForEvent(event.id)
        // No markCreatedOnServer — event.caldavUrl stays null.
        database.attendeesDao().replaceForEvent(
            event.id,
            listOf(
                Attendee(
                    eventId = event.id,
                    address = "mailto:test@icloud.com",
                    partstat = "NEEDS-ACTION"
                )
            )
        )
        val account = database.accountsDao().getById(database.calendarsDao().getById(testCalendarId)!!.accountId)!!

        val ok = eventWriter.replyRsvp(event.id, account, "DECLINED")

        assertTrue("queue insert should be permissive — local PARTSTAT was already updated", ok)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        val op = ops[0]
        assertTrue(op.partstatOnly)
        assertEquals("DECLINED", op.partstatTarget)
        assertNull("targetUrl is null when event was never synced", op.targetUrl)
    }

    // ========== Bug repro: edit-this-and-future with prior exception ==========

    @Test
    fun `splitSeries with past exception preserves single occurrence at exception time`() = runTest {
        // Repro for "Jun 01 shows two events after edit-this-and-future":
        //   1. Master DAILY;COUNT=10
        //   2. Edit occurrence 3 (creates exception at master-time + offset)
        //   3. Split at occurrence 4 (the exception is BEFORE the split, must survive)
        //   4. Master gets COUNT=4. The exception's occurrence on the
        //      Day-of-Exception must be the only row — not master's
        //      RRULE-generated row plus the exception's row (the visible
        //      "two events for Jun 01" symptom).

        // Pin start time at noon UTC so edit-time minus 9 hours stays on
        // the same calendar day in any test runner timezone (3am UTC =
        // previous day in many western zones).
        val anchorStartUtc = 1780308000000L // 2026-06-02 06:00:00 UTC — noon CDT
        val master = eventWriter.createEvent(
            createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = anchorStartUtc,
                endTs = anchorStartUtc + 30 * 60_000L,
            ),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        // Pick occurrence index 3 as the day to edit (the "Jun 01" analog).
        val editOccTs = occurrences[3].startTs
        // User shifts the time-of-day by -3 hours on this single occurrence.
        // Small enough to stay on the same calendar day in any reasonable TZ.
        val exceptionStartTs = editOccTs - 3 * 3600_000L
        val exceptionEndTs = exceptionStartTs + 30 * 60_000L

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = editOccTs,
            modifiedEvent = createBaseEvent().copy(
                title = "edited occ 3",
                startTs = exceptionStartTs,
                endTs = exceptionEndTs,
            ),
            isLocal = true
        )

        // Sanity: after the edit, occurrence index 3 should be at the
        // exception's modified startTs and linked to the exception row.
        val occsAfterEdit = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        val occOnEditedDay = occsAfterEdit.first { it.exceptionEventId == exception.id }
        assertEquals(
            "linked occurrence should sit at the exception's modified time",
            exceptionStartTs,
            occOnEditedDay.startTs,
        )

        // Now split-this-and-future at occurrence 4 (the day AFTER the
        // edited occurrence). The exception is BEFORE the split point, so
        // it must survive.
        val splitOccTs = occurrences[4].startTs
        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitOccTs,
            modifiedEvent = createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = splitOccTs,
                endTs = splitOccTs + 3600_000L,
            ),
            isLocal = true
        )

        // Exception event row should still exist.
        assertNotNull(
            "past exception (before split) must survive the truncate",
            database.eventsDao().getById(exception.id),
        )

        // Master should be COUNT=4.
        val updatedMaster = database.eventsDao().getById(master.id)
        assertEquals("FREQ=DAILY;COUNT=4", updatedMaster?.rrule)

        // Master's occurrence rows for the edited day should be EXACTLY ONE
        // — the exception-linked row at exception's modified time. No
        // separate row at master's RRULE-generated time should be present.
        // Tolerance on the day boundary: the edit moves the time by -8h, so
        // both the master-time and exception-time fall on the same calendar
        // day; we filter by day code.
        val editedDayCode = occsAfterEdit.first { it.exceptionEventId == exception.id }.startDay
        val rowsOnEditedDay = database.occurrencesDao().getForEvent(master.id)
            .filter { it.startDay == editedDayCode }
        assertEquals(
            "edited day should have exactly ONE occurrence row, not two; got: ${rowsOnEditedDay.map { "(start=${it.startTs}, exc=${it.exceptionEventId})" }}",
            1,
            rowsOnEditedDay.size,
        )
        assertEquals(
            "the surviving row must point at the exception",
            exception.id,
            rowsOnEditedDay.single().exceptionEventId,
        )
        assertEquals(
            "the surviving row's start_ts must be the exception's modified time",
            exceptionStartTs,
            rowsOnEditedDay.single().startTs,
        )
    }

    @Test
    fun `regenerateOccurrences after splitSeries with past exception keeps single linked row`() = runTest {
        // Same shape as the prior test, but adds the pull-side
        // regeneration that runs whenever the master is re-fetched from
        // the server (PullStrategy.generateOccurrences). The bug surfaces
        // here if any: master regen wipes the linked occurrence and the
        // restoreExceptionLink path fails to find a match within the 60s
        // tolerance, falling back to inserting a fresh row alongside the
        // already-inserted master-time one.
        val anchorStartUtc = 1780308000000L // 2026-06-02 06:00:00 UTC
        val master = eventWriter.createEvent(
            createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = anchorStartUtc,
                endTs = anchorStartUtc + 30 * 60_000L,
            ),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        val editOccTs = occurrences[3].startTs
        val exceptionStartTs = editOccTs - 3 * 3600_000L
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = editOccTs,
            modifiedEvent = createBaseEvent().copy(
                title = "edited occ 3",
                startTs = exceptionStartTs,
                endTs = exceptionStartTs + 3600_000L,
            ),
            isLocal = true
        )
        val splitOccTs = occurrences[4].startTs
        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitOccTs,
            modifiedEvent = createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = splitOccTs,
                endTs = splitOccTs + 3600_000L,
            ),
            isLocal = true
        )

        // Simulate pull-side regeneration of master (PullStrategy does this
        // every time a master event is re-fetched from the server).
        val truncatedMaster = database.eventsDao().getById(master.id)!!
        occurrenceGenerator.regenerateOccurrences(truncatedMaster)

        // After regen, the day with the exception must still have ONE row.
        val occsAfterRegen = database.occurrencesDao().getForEvent(master.id)
        val editedDayCode = org.onekash.kashcal.data.db.entity.Occurrence
            .toDayFormat(exceptionStartTs, false)
        val rowsOnEditedDay = occsAfterRegen.filter { it.startDay == editedDayCode }
        assertEquals(
            "edited day must still have exactly ONE row after regen; got: ${rowsOnEditedDay.map { "(start=${it.startTs}, exc=${it.exceptionEventId})" }}",
            1,
            rowsOnEditedDay.size,
        )
        assertEquals(
            "the row must point at the exception",
            exception.id,
            rowsOnEditedDay.single().exceptionEventId,
        )
        assertEquals(
            "the row's start_ts must be the exception's modified time",
            exceptionStartTs,
            rowsOnEditedDay.single().startTs,
        )
    }

    @Test
    fun `splitSeries new event uses modified startTs verbatim`() = runTest {
        // Repro for the math bug: when the form lambda passes the user's
        // chosen first-occurrence time as modifiedEvent.startTs, the new
        // series row must start at that exact time. The previous formula
        //   splitTimeMs + (modifiedEvent.startTs - masterEvent.startTs)
        // shifted the new series by (splitTime - masterStart), placing it
        // days later than the user intended.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        val splitOccTs = occurrences[4].startTs

        // User shifts the time-of-day by -8 hours starting at the split.
        val userIntendedStart = splitOccTs - 8 * 3600_000L
        val userIntendedEnd = userIntendedStart + 3600_000L

        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitOccTs,
            modifiedEvent = createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = userIntendedStart,
                endTs = userIntendedEnd,
            ),
            isLocal = true
        )

        assertEquals(
            "new series must start at the user's intended time, not splitTime + delta",
            userIntendedStart,
            newSeries.startTs,
        )
        assertEquals(
            "new series end must follow the user's intended start",
            userIntendedEnd,
            newSeries.endTs,
        )
    }

    @Test
    fun `splitSeries new event has endTs strictly after startTs`() = runTest {
        // Regression for the iCloud 403 root cause: the previous startTs
        // formula
        //   splitTimeMs + (modifiedEvent.startTs - masterEvent.startTs)
        // shifted ONLY startTs by the master-to-split-day delta and left
        // endTs (inherited from modifiedEvent) at the form's chosen end
        // time on the split day. For an edit-this-and-future where the
        // user changes time-of-day on a later occurrence, the result was
        // endTs < startTs by the same delta — RFC 5545 §3.6.1 violation.
        // iCloud rejected such bodies with 403; other servers may rewrite
        // or accept silently. This test asserts the invariant directly.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        // Split 4 days into the series with a user-chosen time-of-day
        // shift, to maximize the gap the buggy formula would create.
        val splitOccTs = occurrences[4].startTs
        val userIntendedStart = splitOccTs - 11 * 3600_000L
        val userIntendedEnd = userIntendedStart + 30 * 60_000L

        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitOccTs,
            modifiedEvent = createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = userIntendedStart,
                endTs = userIntendedEnd,
            ),
            isLocal = true
        )

        assertTrue(
            "RFC 5545 §3.6.1: DTEND MUST be later than DTSTART. " +
                "Got startTs=${newSeries.startTs}, endTs=${newSeries.endTs} " +
                "(delta=${newSeries.endTs - newSeries.startTs}ms)",
            newSeries.endTs > newSeries.startTs,
        )
    }

    @Test
    fun `splitSeries via drag-style lambda preserves dragged occurrence duration`() = runTest {
        // Repro for the drag-vs-form endTs divergence: when the drag
        // lambda emits `endTs = master.endTs + delta` instead of
        // `endTs = newStartTs + (occurrence.endTs - occurrence.startTs)`,
        // the new series ends at master-time + occurrence-delta, which
        // sits days before the new startTs — same RFC 5545 §3.6.1
        // violation as the form-side math bug, different code path.
        //
        // Setup: master with deterministic anchor (noon UTC) so a
        // -11h drag stays on the same calendar day in any TZ. Drag
        // the 5th occurrence to -11h.
        val anchorStartUtc = 1780308000000L // 2026-06-02 06:00:00 UTC
        val masterDurationMs = 30 * 60_000L
        val master = eventWriter.createEvent(
            createBaseEvent().copy(
                rrule = "FREQ=DAILY;COUNT=10",
                startTs = anchorStartUtc,
                endTs = anchorStartUtc + masterDurationMs,
            ),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        val draggedOccStartTs = occurrences[4].startTs
        val draggedOccEndTs = draggedOccStartTs + masterDurationMs

        // The drag gesture shifts the occurrence start by -11h.
        val newStartTs = draggedOccStartTs - 11 * 3600_000L
        // The fix's emitted modifiedEvent: anchor endTs on the dragged
        // occurrence's duration, NOT master's startTs.
        val draggedDuration = draggedOccEndTs - draggedOccStartTs
        val draggedNewEndTs = newStartTs + draggedDuration

        val newSeries = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = draggedOccStartTs,
            modifiedEvent = master.copy(
                startTs = newStartTs,
                endTs = draggedNewEndTs,
            ),
            isLocal = true
        )

        // Invariant 1: endTs > startTs (no RFC 5545 §3.6.1 violation).
        assertTrue(
            "endTs must be after startTs: got start=${newSeries.startTs}, end=${newSeries.endTs}",
            newSeries.endTs > newSeries.startTs,
        )
        // Invariant 2: duration matches the dragged occurrence (i.e. the
        // user's existing event length is preserved, NOT inflated to
        // (master.endTs - master.startTs) + delta).
        assertEquals(
            "new series duration must equal dragged occurrence duration",
            draggedDuration,
            newSeries.endTs - newSeries.startTs,
        )
    }

    // ========== Local attendee write path ==========

    private fun attendee(address: String, partstat: String = "NEEDS-ACTION", sortOrder: Int = 0) =
        Attendee(eventId = 0, address = address, displayName = address.substringBefore('@'),
            role = "REQ-PARTICIPANT", partstat = partstat, cutype = "INDIVIDUAL", rsvp = true, sortOrder = sortOrder)

    @Test
    fun `createEvent persists attendees to the table`() = runTest {
        val created = eventWriter.createEvent(
            createBaseEvent(),
            isLocal = true,
            attendees = listOf(attendee("alice@example.test", "ACCEPTED", 0), attendee("bob@example.test", "NEEDS-ACTION", 1))
        )
        val rows = database.attendeesDao().getForEventOnce(created.id)
        assertEquals(2, rows.size)
        assertEquals(setOf("alice@example.test", "bob@example.test"), rows.map { it.address }.toSet())
    }

    @Test
    fun `updateEvent with null attendees leaves existing rows untouched`() = runTest {
        // Mimics a drag-reschedule / non-attendee edit: caller passes no attendees arg.
        val created = eventWriter.createEvent(
            createBaseEvent(), isLocal = true,
            attendees = listOf(attendee("carol@example.test", "ACCEPTED", 0))
        )
        // Edit a non-attendee field with NO attendees arg (param defaults null).
        eventWriter.updateEvent(created.copy(title = "Rescheduled"), isLocal = true)
        val rows = database.attendeesDao().getForEventOnce(created.id)
        assertEquals("null attendees must preserve existing rows", 1, rows.size)
        assertEquals("carol@example.test", rows.first().address)
    }

    @Test
    fun `updateEvent with empty attendees clears all rows`() = runTest {
        val created = eventWriter.createEvent(
            createBaseEvent(), isLocal = true,
            attendees = listOf(attendee("dave@example.test", "ACCEPTED", 0))
        )
        eventWriter.updateEvent(created.copy(title = "Cleared"), isLocal = true, attendees = emptyList())
        assertEquals("empty list explicitly clears", 0, database.attendeesDao().getForEventOnce(created.id).size)
    }

    // ========== Helper Functions ==========

    private fun createBaseEvent(): Event {
        val now = System.currentTimeMillis()
        return Event(
            uid = "",
            calendarId = testCalendarId,
            title = "Test Event",
            startTs = now + 86400000, // Tomorrow
            endTs = now + 86400000 + 3600000, // Tomorrow + 1 hour
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
    }
}
