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
 * Tests [EventWriter] over an in-memory Room database:
 * - tag usage recorded on save, keeping a tag's color
 * - create (UID, sync status, occurrences, queued CREATE) and ICS-imported series (master plus
 *   linked exceptions)
 * - update: SEQUENCE bumps per field, occurrence regeneration, sync status
 * - delete: hard for local and never-synced events, soft with a queued DELETE otherwise
 * - single-occurrence edits: exception creation and linking, shared UID, master-queued UPDATE,
 *   attendee sets, SEQUENCE
 * - single-occurrence delete (EXDATE and the cancelled occurrence row)
 * - split series and delete this and future: truncation by COUNT or UNTIL, the in-place
 *   fallbacks, future exceptions, attendees, the new series' times
 * - all-events attendee changes cascading to exceptions, and CANCELs queued for removed guests
 * - calendar moves: occurrences, queued operations per source and target, the cross-account
 *   check for events with attendees
 * - RSVP queueing and the attendee rows written by create and update
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

        // Two calendars on one iCloud account, a local calendar, and a calendar on a second
        // synced account.
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

            // A second synced account, distinct from test@icloud.com, for the cross-account
            // move tests.
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
        // A device-event save records its tags here (EventCoordinator.recordTagUsage), not
        // through a Room createEvent. A never-seen name must join the registry so it becomes
        // selectable and colorable, with no color yet.
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
     * Builds a master and one exception as they reach [EventWriter.createImportedSeries] from
     * an ICS file import: both share [sharedUid], the exception is told apart by
     * originalInstanceTime, and originalEventId is unset (the writer sets it after inserting
     * the master). In production EventCoordinator has already set a fresh shared UID.
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
        // The master's occurrence at the overridden instance time carries the exception
        // link; there is no second standalone occurrence row.
        val linked = database.occurrencesDao().getForEvent(saved.master.id)
            .filter { it.exceptionEventId == saved.exceptions[0].id }
        assertEquals("exactly one linked occurrence", 1, linked.size)
        // No standalone occurrence rows under the exception's own event id.
        val standaloneUnderException = database.occurrencesDao().getForEvent(saved.exceptions[0].id)
        assertTrue("no standalone occurrence for exception", standaloneUnderException.isEmpty())
        // Total occurrence count for the series stays at the RRULE count.
        assertEquals(5, database.occurrencesDao().getForEvent(saved.master.id).size)
        // The linked occurrence moves to the exception's modified start time, off the
        // original RRULE instant.
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
        // A rescheduled occurrence of a recurring meeting must carry the series' attendees
        // so the bundled exception VEVENT pushes them (as splitSeries does). Otherwise the
        // exception VEVENT has no ATTENDEEs and that occurrence loses its guest list on the
        // wire.
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
        // The user adds a guest to this occurrence only. The edited set lands on the
        // exception's own rows; the master is unchanged.
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
        // Re-editing an occurrence that already has an exception: the new edited set
        // replaces the exception's attendee rows.
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
        // A re-edit that leaves attendees alone (null) must not clobber the exception's
        // existing rows.
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

        // linkException moves the occurrence to the exception's times, so find it by
        // exceptionEventId.
        val updatedOccurrence = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull(updatedOccurrence)
        assertEquals(exception.id, updatedOccurrence?.exceptionEventId)
        // The occurrence has the exception's times.
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
        // RFC 5545: an exception has its master's UID and is told apart by RECURRENCE-ID.
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

        // The exception's UID equals the master's, with no timestamp suffix.
        assertEquals(master.uid, exception.uid)
        assertFalse(exception.uid.contains("-${targetOccurrence.startTs}"))
    }

    @Test
    fun `editSingleOccurrence queues UPDATE on master not CREATE on exception`() = runTest {
        // A master on the server.
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
        // Clear the pending CREATE from the create.
        database.pendingOperationsDao().deleteForEvent(master.id)

        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified"),
            isLocal = false
        )

        // The pending operation is an UPDATE on the master, not a CREATE on the exception.
        val masterOps = database.pendingOperationsDao().getForEvent(master.id)
        assertEquals(1, masterOps.size)
        assertEquals(PendingOperation.OPERATION_UPDATE, masterOps[0].operation)

        // The exception has no pending operations.
        val exceptionOps = database.pendingOperationsDao().getForEvent(exception.id)
        assertEquals(0, exceptionOps.size)
    }

    @Test
    fun `editSingleOccurrence exception has SYNCED status`() = runTest {
        // The exception is pushed inside its master's resource, so it's SYNCED locally.
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

        assertEquals(SyncStatus.SYNCED, exception.syncStatus)
    }

    @Test
    fun `editSingleOccurrence re-editing exception preserves UID`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val targetOccurrence = database.occurrencesDao().getForEvent(master.id)[2]

        val exception1 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified Once"),
            isLocal = true
        )

        // A second edit of the same occurrence.
        val exception2 = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = targetOccurrence.startTs,
            modifiedEvent = createBaseEvent().copy(title = "Modified Twice"),
            isLocal = true
        )

        // The same row, with its UID kept.
        assertEquals(exception1.id, exception2.id)
        assertEquals(exception1.uid, exception2.uid)
        assertEquals(master.uid, exception2.uid)
        assertEquals("Modified Twice", exception2.title)
    }

    // Rescheduling one occurrence is an organizer timing change, so the exception advances
    // SEQUENCE (RFC 5546 §2.1.4), as on the master-edit and this-and-future paths. The
    // baseline is the unedited occurrence (the master projected onto this occurrence's
    // time), so the master-to-exception difference in shape doesn't read as a change.
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
            // As the coordinator builds it: derived from the master, time shifted.
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

    // A notes-only occurrence edit must not bump SEQUENCE, or attendees get re-notified for
    // nothing.
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

    // A retitled occurrence is attendee-facing, so it bumps SEQUENCE.
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

    // Re-editing an existing exception with a new timing change bumps from the exception's
    // own SEQUENCE, not the master's.
    @Test
    fun `editSingleOccurrence bumps SEQUENCE when re-editing exception with new timing`() = runTest {
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false
        )
        val target = database.occurrencesDao().getForEvent(master.id)[2]

        // First edit: notes only, creating the exception at the occurrence time without a
        // bump (SEQUENCE stays 0).
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

        // Third edit, another time shift: the exception's own counter keeps climbing
        // (1 -> 2), not re-derived from the master's sequence.
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

    // Guards the unedited-occurrence projection for all-day masters: a notes-only edit must
    // not bump SEQUENCE even though the projection recomputes endTs from the master's span
    // and carries isAllDay. If the projection drifted from the modified event on these
    // fields, the bump would fire and re-notify attendees.
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
        // Editing an occurrence moves its master-side occurrence row to the exception's
        // modified start_ts. Deleting it later from the form calls
        // deleteSingleOccurrence(masterId, originalInstanceTime), and a time match within
        // 60 seconds of originalInstanceTime (cancelOccurrence) would miss the row, so the
        // writer cancels it by exception_event_id. If the match failed, is_cancelled would
        // stay 0, exception_event_id would point at a deleted row, and the day card would
        // show the master's title at the modified time, as if the edit had been undone.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val originalSlot = database.occurrencesDao().getForEvent(master.id)[2]
        val originalInstanceTime = originalSlot.startTs

        // Step 1: edit the occurrence, which creates the exception and moves the master's
        // occurrence row to the exception's modified time.
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

        // The linked row is at the exception's modified time, not the original instance
        // time.
        val linkedRow = database.occurrencesDao().getByExceptionEventId(exception.id)
        assertNotNull(linkedRow)
        assertEquals(exception.startTs, linkedRow!!.startTs)

        // Step 2: delete the edited occurrence, the path the form's Delete button takes
        // through handleRoomEventFormDelete.
        eventWriter.deleteSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = originalInstanceTime,
            isLocal = true
        )

        // The exception event row is gone.
        assertNull(database.eventsDao().getById(exception.id))

        // The master's occurrence row at the exception's modified time is cancelled or
        // removed.
        val survivors = database.occurrencesDao().getForEvent(master.id)
        val staleRow = survivors.firstOrNull { it.startTs == exception.startTs }
        assertTrue(
            "After delete, the row at the exception's modified time must " +
                "be cancelled or absent (got: $staleRow)",
            staleRow == null || staleRow.isCancelled
        )

        // No row still points at the deleted exception.
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

        // All 5 still exist; one is cancelled.
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

        // A COUNT rule: the master keeps COUNT=pastCount and never gets UNTIL, so the total
        // occurrence count is kept.
        val updatedMaster = database.eventsDao().getById(master.id)
        assertEquals("FREQ=DAILY;COUNT=5", updatedMaster?.rrule)

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

        // The master keeps only occurrences before the split.
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

        // The master gets an UNTIL.
        val updated = database.eventsDao().getById(master.id)
        assertTrue(updated?.rrule?.contains("UNTIL=") == true)

        // Only occurrences before deleteFrom remain.
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
        // Date-only form: 8 digits, no 'T' (e.g. UNTIL=20260115).
        val untilMatch = Regex("UNTIL=([^;]+)").find(rrule)
        assertNotNull("UNTIL should be in RRULE: $rrule", untilMatch)
        val untilValue = untilMatch!!.groupValues[1]
        assertFalse("UNTIL should be date-only (no T) for all-day: $untilValue",
            untilValue.contains("T"))
        assertEquals("UNTIL should be 8-digit date", 8, untilValue.length)
    }

    @Test
    fun `splitSeries uses date-only UNTIL for all-day unbounded events`() = runTest {
        // An unbounded RRULE takes the UNTIL branch (the COUNT branch keeps COUNT and never
        // emits UNTIL).
        val master = eventWriter.createEvent(
            createBaseEvent().copy(
                rrule = "FREQ=DAILY",
                isAllDay = true
            ),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        // The expansion window gives an unbounded daily series many occurrences; index 5 is
        // well inside it.
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

    // ========== Split Series: total count kept ==========

    @Test
    fun `splitSeries preserves total count for COUNT-based series`() = runTest {
        // The master has COUNT=10. A split at index 2 leaves 2 past occurrences (indices 0
        // and 1), so the master keeps COUNT=2 and the new series gets COUNT=8.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        val splitFrom = occurrences[2].startTs

        val newEvent = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitFrom,
            // The caller passes the master's own RRULE; the writer splits the COUNT.
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

        // No new event row: the split returns the master with the changes applied, as
        // deleteThisAndFuture from the first occurrence acts on the whole event.
        assertEquals(priorMasterId, result.id)
        val updated = database.eventsDao().getById(priorMasterId)
        assertEquals("Renamed via this-and-future on first", updated?.title)
        // The original RRULE survives, not truncated.
        assertEquals("FREQ=DAILY;COUNT=10", updated?.rrule)
    }

    @Test
    fun `splitSeries with pastCount zero falls back to ALL_EVENTS update`() = runTest {
        // A splitTime 1 ms after masterStart gives pastCount 0: the count's range ends,
        // exclusive, at splitTime - 1, which leaves out the first occurrence.
        // RruleUtils.splitRruleAtTime would give the master an invalid COUNT=0, so
        // splitSeries detects it (RruleUtils.isDegenerateCountSplit) and updates the master
        // in place, as an all-events edit.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val priorMasterId = master.id

        val result = eventWriter.splitSeries(
            masterEventId = master.id,
            // 1 ms after the master's start: past the first-occurrence guard, with no
            // occurrence counted before the split (pastCount=0).
            splitTimeMs = master.startTs + 1L,
            modifiedEvent = master.copy(title = "pastCount zero edge"),
            isLocal = true
        )

        assertEquals(priorMasterId, result.id)
        val updated = database.eventsDao().getById(priorMasterId)
        assertEquals("pastCount zero edge", updated?.title)
        // The original RRULE: no COUNT=0 and no UNTIL.
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

        // An exception at occurrence 5, after a split at occurrence 3.
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

        // Split at occurrence 3: the exception at 5 is in the truncated range and is
        // deleted, as deleteThisAndFuture does.
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
        // Attendees live in their own Room table; eventsDao.insert(Event) doesn't touch it,
        // so writer paths populate it through replaceForEvent.
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
        // A this-and-future attendee edit: the user added a guest, so the new series carries
        // the edited set, not a copy of the master's attendees.
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
        // The truncated master keeps its original single attendee.
        val onMaster = database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        assertEquals(
            "past master retains its original attendee set",
            setOf("mailto:alice@example.test"),
            onMaster
        )
    }

    @Test
    fun `splitSeries at first occurrence collapses to in-place update and applies supplied attendees`() = runTest {
        // splitTimeMs <= masterStart takes updateMasterInPlace. The edited attendee set must
        // still land on the master, not be dropped by that branch.
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
        // An all-events attendee edit goes through updateEvent. A time-only exception was
        // seeded with the master's old attendee list, so the new set cascades onto it and the
        // series stays consistent. An exception with its own guest set is skipped (next test).
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        // A time-only exception, seeded with the master's single attendee.
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

        // All-events edit: add a guest to the master.
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
        // After a guest is added to one occurrence, that exception holds its own set. A
        // later all-events edit must leave it alone and not overwrite the user's
        // per-occurrence change.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", sortOrder = 0)
            )
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
        // Add a guest to one occurrence only.
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

        // All-events edit: add a different guest to the whole series.
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
        // A seeded exception matches the series addresses but may carry a server-set
        // PARTSTAT. Only the address set is compared, so the change still cascades.
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
        // A server-set PARTSTAT on the seeded exception (same address, different status),
        // which must not read as a per-occurrence edit.
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
        // Removing an invited guest queues a CANCEL for them in pending_cancels, with the
        // captured delivery context, instead of only dropping the row.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "ACCEPTED", scheduleAgent = "CLIENT", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        // Mark it pushed, so the removed guest was on the wire.
        database.eventsDao().markCreatedOnServer(master.id, "https://caldav.icloud.com/test/${master.uid}.ics", "etag-1", System.currentTimeMillis())

        // Save with bob removed (survivors: alice only).
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
        // The survivors are saved; the removed guest's row is gone.
        assertEquals(
            setOf("mailto:alice@example.test"),
            database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        )
    }

    @Test
    fun `updateEvent does not tombstone a removed never-synced guest`() = runTest {
        // A guest added and removed before the event ever synced was never on the wire, so
        // no CANCEL is owed.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = false,
            attendees = listOf(
                Attendee(eventId = 0, address = "mailto:alice@example.test", partstat = "NEEDS-ACTION", sortOrder = 0),
                Attendee(eventId = 0, address = "mailto:bob@example.test", partstat = "NEEDS-ACTION", sortOrder = 1)
            )
        )
        // Not marked created on the server: caldavUrl stays null.

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
        // Removing a guest from one occurrence cancels them for that occurrence only
        // (recurrence_id set); the master keeps them.
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

        // Edit this occurrence only, dropping bob (survivors: alice only).
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
        // The master still has both guests.
        assertEquals(
            setOf("mailto:alice@example.test", "mailto:bob@example.test"),
            database.attendeesDao().getForEventOnce(master.id).map { it.address }.toSet()
        )
    }

    @Test
    fun `editThisAndFuture removal leaves the guest off the new series and enqueues no cancel`() = runTest {
        // This and future splits the series: the new series has a fresh UID the guest was
        // never on, so they are absent from then on with no CANCEL; the truncated master
        // keeps them.
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

        // The new series lacks bob, the master keeps both, and no cancel is pending.
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
        // The exception cascade runs only for a recurring master, so a non-recurring
        // attendee edit writes no exception rows. Checked through an unrelated recurring
        // series' exception, untouched by an update to a separate non-recurring event.
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
        // RFC 5545 §3.3.10: COUNT counts rule recurrences, not what survives EXDATE.
        // Splitting a COUNT=10 series at index 5 with one EXDATE in [start, splitTime) must
        // give the master COUNT=5. COUNT=4 would drop a visible past occurrence on
        // re-expansion, because EXDATE applies after the COUNT cap.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitFrom = occurrences[5].startTs
        // EXDATE on the 3rd occurrence, before splitFrom, in the stored epoch-millis form.
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
        // 5 rule recurrences before splitFrom (occurrences 0-4), one excluded, so 4 visible.
        // COUNT=5 re-expands to 5 candidates and the EXDATE leaves 4; COUNT=4 would leave 3.
        assertEquals("FREQ=DAILY;COUNT=5", updatedMaster.rrule)
    }

    @Test
    fun `splitSeries with pastCount equal total falls back to ALL_EVENTS`() = runTest {
        // splitTimeMs after the last occurrence, so pastCount == total. Without the
        // degenerate-split check, RruleUtils.splitRruleAtTime would give the new series
        // COUNT=0, which ical4j 4.3.0 expands as if the rule had no COUNT.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=5"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        // 1 day past the final occurrence's start: pastCount=5 == total.
        val splitFrom = occurrences.last().startTs + 86_400_000L
        val priorMasterId = master.id

        val result = eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = splitFrom,
            modifiedEvent = master.copy(title = "Edited past end"),
            isLocal = true
        )

        // No new event row: the split updated the master in place, as an all-events edit.
        assertEquals(priorMasterId, result.id)
        val updated = database.eventsDao().getById(priorMasterId)!!
        assertEquals("Edited past end", updated.title)
        // The original RRULE, not truncated.
        assertEquals("FREQ=DAILY;COUNT=5", updated.rrule)
    }

    @Test
    fun `splitSeries bumps SEQUENCE on first-occurrence in-place fallback`() = runTest {
        // updateMasterInPlace bumps SEQUENCE (RFC 5545 §3.8.7.4) when a field that matters to
        // attendees changes, through SequenceBumper as updateEvent does.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = false
        )
        val priorSequence = master.sequence

        eventWriter.splitSeries(
            masterEventId = master.id,
            splitTimeMs = master.startTs, // first-occurrence guard
            // A timing change, which triggers the bump.
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
        // A modifiedEvent.rrule that differs from the master's changes the recurrence for
        // "this and future", so the new series gets the caller's rule, not a COUNT- or
        // UNTIL-rewritten copy of the master's.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id).sortedBy { it.startTs }
        val splitFrom = occurrences[2].startTs

        // A different pattern (DAILY), which must land on the new series unchanged.
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
        // An event on the server.
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )
        // Clear the pending CREATE from the create.
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to another calendar on the same iCloud account.
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // One MOVE, with the old URL and the target calendar.
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals("https://caldav.icloud.com/test/event123.ics", ops[0].targetUrl)
        assertEquals(iCloudCalendar2Id, ops[0].targetCalendarId)
    }

    // ---- cross-account move is blocked for events with attendees ----
    // Moving an event with attendees to another account would send the source account's
    // address as ORGANIZER, which scheduling servers reject or rewrite, re-inviting or
    // stripping guests. The writer refuses it and the user duplicates the event into the
    // other account instead (fresh UID, correct organizer).

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

        // A cross-account move (test@icloud.com -> other@nextcloud.test) throws.
        var threw = false
        try {
            eventWriter.moveEventToCalendar(event.id, otherAccountCalendarId)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("cross-account move of an attendee event must be rejected", threw)

        // The event stays put: calendar unchanged, no op queued.
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

        // No attendees, so the cross-account move proceeds (a linked CREATE and DELETE; the
        // assert checks only that an op is queued).
        eventWriter.moveEventToCalendar(event.id, otherAccountCalendarId)

        assertEquals(otherAccountCalendarId, database.eventsDao().getById(event.id)?.calendarId)
        assertTrue(database.pendingOperationsDao().getForEvent(event.id).isNotEmpty())
    }

    @Test
    fun `moveEventToCalendar cross-account rejected when only an EXCEPTION has attendees`() = runTest {
        // A recurring master with no attendees, and a per-occurrence edit that adds a guest to
        // one occurrence (an exception with its own attendee rows). The cross-account check
        // must count exception attendees too, or the move goes through and mis-schedules the
        // exception's guest.
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
        // The master itself has no attendees.
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

        // A same-account move (two iCloud calendars) keeps the attendees on the same eventId.
        // Whether iCloud re-invites on it is probed live by MoveReInviteProbeTest, which
        // prints its observation and asserts nothing.
        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        assertEquals(iCloudCalendar2Id, database.eventsDao().getById(event.id)?.calendarId)
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
    }

    @Test
    fun `moveEventToCalendar local to iCloud queues CREATE only`() = runTest {
        // A local event, never synced.
        val event = eventWriter.createEvent(createBaseEvent().copy(
            calendarId = localCalendarId
        ), isLocal = true)

        // Move to an iCloud calendar.
        eventWriter.moveEventToCalendar(event.id, testCalendarId)

        // One CREATE, not a MOVE: there is no old URL.
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_CREATE, ops[0].operation)
        assertNull(ops[0].targetUrl)  // No old URL for a local event
    }

    @Test
    fun `moveEventToCalendar iCloud to local queues DELETE`() = runTest {
        // An event on the server.
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.eventsDao().markCreatedOnServer(
            event.id,
            "https://caldav.icloud.com/test/event123.ics",
            "etag123",
            System.currentTimeMillis()
        )
        database.pendingOperationsDao().deleteForEvent(event.id)

        // Move to the local calendar, which queues a DELETE.
        eventWriter.moveEventToCalendar(event.id, localCalendarId)

        // One DELETE, carrying the source calendar id.
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(1, ops.size)
        assertEquals(PendingOperation.OPERATION_DELETE, ops[0].operation)
        assertEquals(testCalendarId, ops[0].sourceCalendarId)
    }

    @Test
    fun `moveEventToCalendar cancels existing pending operations`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        // A pending UPDATE.
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = event.id,
                operation = PendingOperation.OPERATION_UPDATE
            )
        )

        eventWriter.moveEventToCalendar(event.id, iCloudCalendar2Id)

        // The UPDATE is replaced by one CREATE or MOVE.
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

        // The MOVE stores the old URL, the source resource it moves from.
        val ops = database.pendingOperationsDao().getForEvent(event.id)
        assertEquals(PendingOperation.OPERATION_MOVE, ops[0].operation)
        assertEquals(oldUrl, ops[0].targetUrl)  // Captured before the move clears it
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
        // caldavUrl is captured at queue time, so a code path that later clears
        // Event.caldavUrl can't silently turn the queued RSVP into a no-op.
        assertEquals(syncedUrl, op.targetUrl)
    }

    @Test
    fun `replyRsvp on never-synced event queues with null targetUrl`() = runTest {
        val event = eventWriter.createEvent(createBaseEvent(), isLocal = false)
        database.pendingOperationsDao().deleteForEvent(event.id)
        // No markCreatedOnServer, so event.caldavUrl stays null.
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

    // ========== Edit This and Future After a Past Exception ==========

    @Test
    fun `splitSeries with past exception preserves single occurrence at exception time`() = runTest {
        // The edited day must not show two events after this and future:
        //   1. Master DAILY;COUNT=10
        //   2. Edit occurrence 3 (an exception at the master's time minus 3 hours)
        //   3. Split at occurrence 4 (the exception is before the split and must survive)
        //   4. The master gets COUNT=4. The exception's occurrence is the only row on its
        //      day, not the master's RRULE row plus the exception's row.

        // A fixed start, so the -3 hour edit stays on the same calendar day in runner
        // timezones from UTC-7 to UTC+13.
        val anchorStartUtc = 1780308000000L // 2026-06-01 10:00:00 UTC
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
        // Edit the occurrence at index 3.
        val editOccTs = occurrences[3].startTs
        // The user moves this occurrence 3 hours earlier.
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

        // After the edit, occurrence index 3 is at the exception's modified startTs and
        // linked to the exception row.
        val occsAfterEdit = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        val occOnEditedDay = occsAfterEdit.first { it.exceptionEventId == exception.id }
        assertEquals(
            "linked occurrence should sit at the exception's modified time",
            exceptionStartTs,
            occOnEditedDay.startTs,
        )

        // Split at occurrence 4, the day after the edited occurrence. The exception is
        // before the split point, so it must survive.
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

        assertNotNull(
            "past exception (before split) must survive the truncate",
            database.eventsDao().getById(exception.id),
        )

        val updatedMaster = database.eventsDao().getById(master.id)
        assertEquals("FREQ=DAILY;COUNT=4", updatedMaster?.rrule)

        // The edited day has exactly one master occurrence row: the exception-linked row at
        // the exception's modified time, with no row at the master's RRULE time. The -3 hour
        // edit keeps both times on one calendar day (see the anchor), so rows are filtered by
        // day code.
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
        // The prior test plus the regeneration the pull runs when it re-fetches a master
        // (PullStrategy calls OccurrenceGenerator.regenerateOccurrences). Regeneration
        // replaces the linked occurrence, and OccurrenceGenerator.restoreExceptionLink must
        // re-link the exception to the master's row within 60 seconds of its original time;
        // a miss would leave two rows on the edited day.
        val anchorStartUtc = 1780308000000L // 2026-06-01 10:00:00 UTC
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

        // The pull's regeneration of the master.
        val truncatedMaster = database.eventsDao().getById(master.id)!!
        occurrenceGenerator.regenerateOccurrences(truncatedMaster)

        // After regeneration the exception's day still has one row.
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
        // The form passes the user's chosen first-occurrence time as modifiedEvent.startTs,
        // and the new series starts at that exact time. A formula of
        //   splitTimeMs + (modifiedEvent.startTs - masterEvent.startTs)
        // would shift the new series by (splitTime - masterStart), days later than intended.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        val splitOccTs = occurrences[4].startTs

        // The user moves the time of day 8 hours earlier from the split on.
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
        // A startTs formula of
        //   splitTimeMs + (modifiedEvent.startTs - masterEvent.startTs)
        // would shift only startTs by the master-to-split-day gap and leave endTs (from
        // modifiedEvent) at the form's end time on the split day. When the user changes the
        // time of day on a later occurrence, endTs < startTs, which RFC 5545 §3.8.2.2 forbids
        // (DTEND must be later than DTSTART). iCloud rejects such bodies with 403; other
        // servers may rewrite or accept them. This test asserts the invariant directly.
        val master = eventWriter.createEvent(
            createBaseEvent().copy(rrule = "FREQ=DAILY;COUNT=10"),
            isLocal = true
        )
        val occurrences = database.occurrencesDao().getForEvent(master.id)
            .sortedBy { it.startTs }
        // Split 4 days into the series with a time-of-day shift, so the shifting formula
        // would open a gap of days.
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
        // A drag that built `endTs = master.endTs + delta` instead of
        // `endTs = newStartTs + (occurrence.endTs - occurrence.startTs)` would end the new
        // series days before its start, the same RFC 5545 §3.8.2.2 breach as the form case on
        // another code path.
        //
        // Setup: a master at a fixed anchor, with the 5th occurrence dragged 11 hours earlier.
        val anchorStartUtc = 1780308000000L // 2026-06-01 10:00:00 UTC
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

        // The drag moves the occurrence start 11 hours earlier.
        val newStartTs = draggedOccStartTs - 11 * 3600_000L
        // endTs comes from the dragged occurrence's duration, not the master's times.
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

        // endTs > startTs (RFC 5545 §3.8.2.2).
        assertTrue(
            "endTs must be after startTs: got start=${newSeries.startTs}, end=${newSeries.endTs}",
            newSeries.endTs > newSeries.startTs,
        )
        // The duration matches the dragged occurrence: the event keeps its length, not
        // (master.endTs - master.startTs) + delta.
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
        // As a drag reschedule or other non-attendee edit calls it: no attendees argument.
        val created = eventWriter.createEvent(
            createBaseEvent(), isLocal = true,
            attendees = listOf(attendee("carol@example.test", "ACCEPTED", 0))
        )
        // Edit a non-attendee field with no attendees argument (it defaults to null).
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
