package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.shouldShowRespondSection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.onekash.kashcal.testutil.insertOfflineCalendar
import org.onekash.kashcal.sync.strategy.synthesizeMasterForOrphanException

/**
 * Imports invitation files (iTIP messages, RFC 5546) the way the app's file import does:
 * [IcsParserService.parseForImport] then [EventCoordinator.importIcsFile] over real Room.
 *
 * The `nextcloud_*` fixtures are REQUEST, REPLY and CANCEL bodies a local Nextcloud server sent
 * for one meeting, the same text/calendar part an iMIP mail carries (RFC 6047 section 2.4).
 * `issue_364_request.ics` is the file from #364 byte for byte (LF line endings as posted). The
 * report says to replace attendee@example.com with the user's address, so those tests sign in
 * as attendee@example.com instead of editing the file.
 *
 * Each assertion states the RFC behavior: the UID is the event's identity (RFC 5545
 * section 3.8.4.7) and iTIP matches every message by it, so a file for a known UID updates,
 * cancels or leaves that event instead of adding a copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsInvitationImportTest {

    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator

    private val selfAddress = "testuser1@example.test"
    private val issueAttendee = "attendee@example.com"
    private val nextcloudUid = "sweep-appwide-itip-dddd700a-3af2-4dd5-9edc-a7474a6461dd"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val generator = OccurrenceGenerator(
            db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()
        )
        val accounts = AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            credentialManager = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true),
        )
        dataStore = mockk<KashCalDataStore> {
            every { defaultReminderMinutes } returns flowOf(15)
            every { defaultAllDayReminder } returns flowOf(1440)
        }
        this.generator = generator
        this.accounts = accounts
        reminders = mockk(relaxed = true)
        coordinator = newCoordinator(reminders)
    }

    private lateinit var generator: OccurrenceGenerator
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var dataStore: KashCalDataStore
    private lateinit var reminders: ReminderScheduler

    private fun newCoordinator(reminderScheduler: ReminderScheduler) = EventCoordinator(
        eventWriter = EventWriter(db, generator),
        eventReader = EventReader(db),
        occurrenceGenerator = generator,
        localCalendarInitializer = LocalCalendarInitializer(db),
        icsSubscriptionRepository = mockk(),
        contactBirthdayRepository = mockk(),
        contactAnniversaryRepository = mockk(),
        accountRepository = accounts,
        syncScheduler = mockk(relaxed = true),
        reminderScheduler = reminderScheduler,
        widgetUpdateManager = mockk(relaxed = true),
        inviteNotifier = mockk(relaxed = true),
        icsRefreshScheduleReconciler = mockk(relaxed = true),
        dataStore = dataStore,
    )

    /** A real scheduler over the in-memory database; AlarmManager is Robolectric's shadow. */
    private fun realReminderScheduler() = ReminderScheduler(
        context = ApplicationProvider.getApplicationContext(),
        scheduledRemindersDao = db.scheduledRemindersDao(),
        eventReader = EventReader(db),
        channels = mockk(relaxed = true),
        attendeesDao = db.attendeesDao(),
        accountsDao = db.accountsDao(),
        calendarsDao = db.calendarsDao(),
        occurrencesDao = db.occurrencesDao(),
    )

    @After
    fun tearDown() = db.close()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("ics/itip/$name")) { "missing $name" }
            .readText()

    private suspend fun caldavCalendarId(address: String = selfAddress): Long {
        val accountId = db.accountsDao().insert(
            Account(
                provider = AccountProvider.CALDAV,
                email = address,
                calendarUserAddresses = listOf("mailto:$address")
            )
        )
        return secondCalendarId(accountId, "personal")
    }

    private suspend fun secondCalendarId(accountId: Long, name: String): Long = db.calendarsDao().insert(
        Calendar(
            accountId = accountId,
            caldavUrl = "https://dav.example.test/cal/$name/",
            displayName = name,
            color = 0
        )
    )

    /** Stores the attendee's server copy of the meeting the way a pull does. */
    private suspend fun seedSyncedCopy(calendarId: Long, ics: String): Long {
        val body = ics.replace(Regex("METHOD:\\w+\r?\n"), "")
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!.single()
        val mapped = ICalEventMapper.toEntity(
            parsed, body, calendarId,
            "https://dav.example.test/cal/personal/${parsed.uid}.ics", "\"e1\""
        )
        val id = db.eventsDao().insert(mapped.event.copy(syncStatus = SyncStatus.SYNCED))
        db.attendeesDao().replaceForEvent(id, mapped.attendees.map { it.copy(eventId = id) })
        generator.regenerateOccurrences(db.eventsDao().getById(id)!!)
        return id
    }

    private suspend fun importFile(name: String, calendarId: Long): IcsImportResult =
        importText(fixture(name), calendarId)

    private suspend fun importText(ics: String, calendarId: Long): IcsImportResult =
        coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(ics)), calendarId)

    // ---- invitations built around now, so reminders fall inside the scheduling window ----

    private val start: Long = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS).toEpochMilli()
    private val day = 86_400_000L
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private fun at(ms: Long) = utc.format(Instant.ofEpochMilli(ms))
    private val meetingUid = "weekly-sync@example.test"

    private fun vevent(
        sequence: Int,
        stamp: String,
        recurrenceId: Long? = null,
        rrule: String? = "FREQ=DAILY;COUNT=5",
        status: String? = null,
        startAt: Long = recurrenceId ?: start,
        summary: String = "Weekly sync",
    ) = listOfNotNull(
        "BEGIN:VEVENT", "UID:$meetingUid", "DTSTAMP:$stamp",
        recurrenceId?.let { "RECURRENCE-ID:${at(it)}" },
        "DTSTART:${at(startAt)}", "DTEND:${at(startAt + 3_600_000)}",
        rrule?.takeIf { recurrenceId == null }?.let { "RRULE:$it" },
        "SUMMARY:$summary", "SEQUENCE:$sequence", status?.let { "STATUS:$it" },
        "ORGANIZER:mailto:admin@example.test",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:admin@example.test",
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$selfAddress",
        "END:VEVENT",
    )

    private fun message(method: String, vararg vevents: List<String>) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client//EN", "METHOD:$method") +
            vevents.flatMap { it } + listOf("END:VCALENDAR", "")
        ).joinToString("\r\n")

    private suspend fun rows(calendarId: Long, uid: String = meetingUid) =
        db.eventsDao().findAllByUid(uid, calendarId)

    private suspend fun masters(calendarId: Long): List<Event> =
        db.eventsDao().getAllMasterEventsForCalendar(calendarId)

    @Test
    fun `issue 364 invitation keeps the attendee list with the user at NEEDS-ACTION`() = runBlocking {
        val calendarId = caldavCalendarId(issueAttendee)

        importFile("issue_364_request.ics", calendarId)

        val event = masters(calendarId).single()
        assertEquals("organizer@example.com", event.organizerEmail)
        val rows = db.attendeesDao().getForEventOnce(event.id)
        val self = rows.firstOrNull { it.address.removePrefix("mailto:").equals(issueAttendee, ignoreCase = true) }
        assertNotNull("the user's ATTENDEE row is dropped on import; rows=$rows", self)
        assertEquals("NEEDS-ACTION", self!!.partstat)
    }

    @Test
    fun `issue 364 invitation shows the Respond section`() = runBlocking {
        val calendarId = caldavCalendarId(issueAttendee)

        importFile("issue_364_request.ics", calendarId)

        val event = masters(calendarId).single()
        val account = db.accountsDao().getById(db.calendarsDao().getById(calendarId)!!.accountId)!!
        val selfRow = db.attendeesDao().getForEventOnce(event.id)
            .firstOrNull { account.matchesAttendee(it.address) }
        val partstat = selfRow?.let { AttendeeStatus.fromPartstat(it.partstat) }
        assertTrue(
            "no Respond section: the user is not on the imported event's attendee list",
            shouldShowRespondSection(partstat, isOrganizer = false)
        )
    }

    /**
     * The #364 expected result: accepting sends the organizer the user's ACCEPTED status. On a
     * CalDAV calendar the client PUTs the event with its own PARTSTAT changed and the server
     * sends the iTIP REPLY (RFC 6638 section 3.2.2.3), so the body carries no METHOD (RFC 4791
     * section 4.1). [IcsPatcher.patchAttendeeReply] builds that body for the RSVP push.
     */
    @Test
    fun `the issue 364 invitation's create body names the organizer and the user at NEEDS-ACTION`() =
        runBlocking {
            val calendarId = caldavCalendarId(issueAttendee)
            importFile("issue_364_request.ics", calendarId)
            val event = masters(calendarId).single()

            val body = IcsPatcher.serialize(event, db.attendeesDao().getForEventOnce(event.id))

            val lines = body.replace(Regex("\r?\n[ \t]"), "").lines()
            assertTrue(lines.contains("UID:test-invitation-123@example.com"))
            assertTrue(lines.any { it.startsWith("ORGANIZER") && it.endsWith(":mailto:organizer@example.com") })
            assertTrue(
                lines.any {
                    it.startsWith("ATTENDEE") && it.contains("PARTSTAT=NEEDS-ACTION") &&
                        it.endsWith(":mailto:$issueAttendee")
                }
            )
            assertTrue(lines.none { it.startsWith("METHOD") })
        }

    @Test
    fun `import keeps the file's UID in a CalDAV calendar`() = runBlocking {
        val calendarId = caldavCalendarId(issueAttendee)

        importFile("issue_364_request.ics", calendarId)

        assertEquals("test-invitation-123@example.com", masters(calendarId).single().uid)
    }

    @Test
    fun `import keeps the file's UID in the local calendar`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()

        importFile("issue_364_request.ics", localId)

        assertEquals("test-invitation-123@example.com", masters(localId).single().uid)
    }

    @Test
    fun `importing the same invitation twice leaves one event`() = runBlocking {
        val calendarId = caldavCalendarId()

        importFile("nextcloud_request.ics", calendarId)
        importFile("nextcloud_request.ics", calendarId)

        assertEquals(1, masters(calendarId).size)
    }

    @Test
    fun `a REQUEST for a meeting already synced adds no copy`() = runBlocking {
        val calendarId = caldavCalendarId()
        seedSyncedCopy(calendarId, fixture("nextcloud_request.ics"))

        val result = importFile("nextcloud_request.ics", calendarId)

        val all = masters(calendarId)
        assertEquals("rows=${all.map { it.uid to it.syncStatus }}", 1, all.size)
        assertEquals(nextcloudUid, all.single().uid)
        assertEquals(1, result.upToDate)
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
        val self = db.attendeesDao().getForEventOnce(all.single().id).single { it.address == "mailto:$selfAddress" }
        assertTrue(shouldShowRespondSection(AttendeeStatus.fromPartstat(self.partstat), isOrganizer = false))
    }

    @Test
    fun `a REPLY file adds no event`() = runBlocking {
        val calendarId = caldavCalendarId()
        seedSyncedCopy(calendarId, fixture("nextcloud_request.ics"))

        val result = importFile("nextcloud_reply.ics", calendarId)

        assertEquals(1, masters(calendarId).size)
        assertEquals(listOf(IgnoreReason.REPLY_FILE), result.ignored)
    }

    @Test
    fun `a CANCEL file for an unknown meeting creates nothing`() = runBlocking {
        val calendarId = caldavCalendarId()

        importFile("nextcloud_cancel.ics", calendarId)

        assertEquals(
            "a cancellation became events: ${masters(calendarId).map { it.title to it.status }}",
            0, masters(calendarId).size
        )
    }

    @Test
    fun `a CANCEL file cancels the matching meeting instead of adding one`() = runBlocking {
        val calendarId = caldavCalendarId()
        seedSyncedCopy(calendarId, fixture("nextcloud_request.ics"))

        importFile("nextcloud_cancel.ics", calendarId)

        val all = masters(calendarId)
        assertEquals("rows=${all.map { it.uid to it.status }}", 1, all.size)
        assertEquals("CANCELLED", all.single().status)
    }

    @Test
    fun `a recurring invitation with a changed occurrence keeps its UID, guests and SEQUENCE`() = runBlocking {
        val calendarId = caldavCalendarId()

        val result = importText(
            message("REQUEST", vevent(2, "20261001T100000Z"),
                vevent(2, "20261001T100000Z", recurrenceId = start + day, startAt = start + day + 7_200_000, summary = "Moved")),
            calendarId,
        )

        assertEquals("$result", 2, result.created)
        val all = rows(calendarId)
        assertEquals(2, all.size)
        val master = all.single { it.originalEventId == null }
        val exception = all.single { it.originalEventId != null }
        assertEquals(master.id, exception.originalEventId)
        assertTrue(all.all { it.sequence == 2 && it.importSource == Event.IMPORT_SOURCE_ICS_FILE })
        assertTrue(all.all { it.itipDtstamp == Instant.parse("2026-10-01T10:00:00Z").toEpochMilli() })
        assertEquals(2, db.attendeesDao().getForEventOnce(exception.id).size)
        assertTrue("the user's own row is marked notified",
            db.attendeesDao().getForEventOnce(master.id).single { it.address == "mailto:$selfAddress" }.notifiedAt != null)
        assertEquals("an invitation takes the default reminder", listOf("-PT15M"), master.reminders)
        assertEquals(1, master.alarmCount)
    }

    @Test
    fun `an occurrence CANCEL on the local calendar cancels that occurrence`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        importText(message("REQUEST", vevent(0, "20261001T100000Z")), localId)
        val masterId = rows(localId).single().id

        val result = importText(
            message("CANCEL", vevent(1, "20261002T100000Z", recurrenceId = start + day, status = "CANCELLED")),
            localId,
        )

        assertEquals(1, result.cancelled)
        val exception = rows(localId).single { it.originalEventId == masterId }
        assertEquals("CANCELLED", exception.status)
        assertEquals("CONFIRMED", rows(localId).single { it.id == masterId }.status)
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
        coVerify { reminders.cancelReminderForOccurrence(masterId, start + day) }
    }

    @Test
    fun `an occurrence CANCEL on a CalDAV calendar queues the attendee's cancel`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")))

        val result = importText(
            message("CANCEL", vevent(1, "20261002T100000Z", recurrenceId = start + day, status = "CANCELLED")), calendarId
        )

        assertEquals("$result", 1, result.cancelled)
        val exception = rows(calendarId).single { it.originalEventId == masterId }
        assertEquals("CANCELLED", exception.status)
        assertEquals(Event.IMPORT_SOURCE_ICS_CANCEL, exception.importSource)
        assertEquals(
            "the cancel is queued for the occurrence's row",
            listOf(PendingOperation.OPERATION_ATTENDEE_CANCEL),
            db.pendingOperationsDao().getForEvent(exception.id).map { it.operation }
        )
    }

    @Test
    fun `a CANCEL without STATUS from the meeting's organizer cancels it on the local calendar`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        importFile("nextcloud_request.ics", localId)

        val result = importFile("nextcloud_cancel.ics", localId)

        assertEquals(1, result.cancelled)
        assertEquals("CANCELLED", masters(localId).single().status)
    }

    @Test
    fun `a REQUEST for a meeting awaiting deletion creates nothing`() = runBlocking {
        val calendarId = caldavCalendarId()
        val id = seedSyncedCopy(calendarId, fixture("nextcloud_request.ics"))
        db.openHelper.writableDatabase.execSQL("UPDATE events SET sync_status = 'PENDING_DELETE' WHERE id = $id")

        val result = importFile("nextcloud_request.ics", calendarId)

        assertEquals(listOf(IgnoreReason.DELETION_PENDING), result.ignored)
        assertEquals(1, rows(calendarId, nextcloudUid).size)
    }

    @Test
    fun `a UID on another calendar of the account gives a plain event a fresh UID and skips an invitation`() =
        runBlocking {
            val calendarId = caldavCalendarId()
            val other = secondCalendarId(db.calendarsDao().getById(calendarId)!!.accountId, "work")
            seedSyncedCopy(other, fixture("nextcloud_request.ics"))
            val plain = fixture("nextcloud_request.ics").replace("METHOD:REQUEST\r\n", "").replace("METHOD:REQUEST\n", "")

            val invitation = importFile("nextcloud_request.ics", calendarId)
            assertEquals(listOf(IgnoreReason.ON_ANOTHER_CALENDAR), invitation.ignored)
            assertTrue(masters(calendarId).isEmpty())

            importText(plain, calendarId)
            val created = masters(calendarId).single()
            assertTrue("fresh UID, got ${created.uid}", created.uid != nextcloudUid)
        }

    @Test
    fun `a REQUEST after an occurrence CANCEL on the local calendar brings it back and re-arms it`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        importText(message("REQUEST", vevent(1, "20261001T100000Z")), localId)
        val masterId = rows(localId).single().id
        importText(message("CANCEL", vevent(1, "20261002T100000Z", recurrenceId = start + day, status = "CANCELLED")), localId)

        val result = importText(message("REQUEST", vevent(1, "20261003T100000Z", recurrenceId = start + day)), localId)

        assertEquals(1, result.updated)
        val exception = rows(localId).single { it.originalEventId == masterId }
        assertEquals("CONFIRMED", exception.status)
        coVerify { reminders.reconcileRemindersForEvent(match { it.id == exception.id }) }
        coVerify { reminders.scheduleRemindersForEvent(match { it.id == exception.id }, any(), any()) }
    }

    @Test
    fun `an older REQUEST after a CANCEL on the local calendar leaves it cancelled`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        importText(message("REQUEST", vevent(1, "20261001T100000Z")), localId)
        importText(message("CANCEL", vevent(2, "20261002T100000Z", status = "CANCELLED")), localId)

        val result = importText(message("REQUEST", vevent(1, "20261003T100000Z")), localId)

        assertEquals(1, result.upToDate)
        assertTrue(rows(localId).all { it.status == "CANCELLED" })
    }

    @Test
    fun `an imported CANCEL keeps every reminder off, for an imported and for a pulled copy`() = runBlocking {
        val scheduler = realReminderScheduler()
        coordinator = newCoordinator(scheduler)
        val cancel = message("CANCEL", vevent(1, "20261002T100000Z", status = "CANCELLED"))

        for (pulled in listOf(false, true)) {
            db.clearAllTables()
            val calendarId = caldavCalendarId()
            val request = message("REQUEST", vevent(0, "20261001T100000Z"))
            val masterId = if (pulled) {
                seedSyncedCopy(calendarId, request).also {
                    db.openHelper.writableDatabase.execSQL("UPDATE events SET reminders = '[\"-PT15M\"]' WHERE id = $it")
                    scheduler.scheduleUpcomingReminders()
                }
            } else {
                importText(request, calendarId)
                rows(calendarId).single().id
            }
            assertTrue("pulled=$pulled: reminders armed", db.scheduledRemindersDao().getPendingForEvent(masterId).isNotEmpty())
            val armed = db.scheduledRemindersDao().getPendingForEvent(masterId)

            importText(cancel, calendarId)

            assertTrue("pulled=$pulled", armed.all { !scheduler.shouldFireReminder(it.eventId) || !scheduler.hasLiveOccurrenceForReminder(it) })
            scheduler.scheduleUpcomingReminders()
            assertTrue("pulled=$pulled: nothing re-armed", db.scheduledRemindersDao().getPendingForEvent(masterId).isEmpty())
        }
    }

    @Test
    fun `a cancelled event no import touched keeps its reminders`() = runBlocking {
        val scheduler = realReminderScheduler()
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z", status = "CANCELLED")))
        db.openHelper.writableDatabase.execSQL("UPDATE events SET reminders = '[\"-PT15M\"]' WHERE id = $masterId")

        scheduler.scheduleUpcomingReminders()

        val armed = db.scheduledRemindersDao().getPendingForEvent(masterId)
        assertFalse(armed.isEmpty())
        assertTrue(armed.all { scheduler.shouldFireReminder(it.eventId) })
    }

    @Test
    fun `a newer REQUEST on the local calendar rewrites the series as the file has it`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        importText(message("REQUEST", vevent(1, "20261001T100000Z")), localId)
        val before = rows(localId).single()
        val newer = message("REQUEST", vevent(2, "20261005T100000Z", summary = "Weekly sync moved"))
            .replace("ATTENDEE;PARTSTAT=ACCEPTED:mailto:admin@example.test\r\n", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:new-guest@example.test\r\n")

        val result = importText(newer, localId)

        assertEquals("$result", 1, result.updated)
        val after = rows(localId).single()
        assertEquals(before.id, after.id)
        assertEquals("Weekly sync moved", after.title)
        assertEquals("the file's SEQUENCE, not a bumped one", 2, after.sequence)
        assertEquals(Instant.parse("2026-10-05T10:00:00Z").toEpochMilli(), after.itipDtstamp)
        assertEquals(
            setOf("mailto:new-guest@example.test", "mailto:$selfAddress"),
            db.attendeesDao().getForEventOnce(after.id).map { it.address }.toSet(),
        )
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
        assertEquals("occurrences regenerated", 5, db.occurrencesDao().getForEvent(after.id).size)
    }

    @Test
    fun `an occurrence REQUEST on the local calendar adds that changed occurrence as the file has it`() = runBlocking {
        val localId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        importText(message("REQUEST", vevent(1, "20261001T100000Z")), localId)
        val masterId = rows(localId).single().id

        val result = importText(
            message("REQUEST", vevent(2, "20261006T100000Z", recurrenceId = start + day, startAt = start + day + 3_600_000, summary = "Later")),
            localId,
        )

        assertEquals("$result", 1, result.updated)
        val exception = rows(localId).single { it.originalEventId == masterId }
        assertEquals("Later", exception.title)
        assertEquals(2, exception.sequence)
        assertEquals(Instant.parse("2026-10-06T10:00:00Z").toEpochMilli(), exception.itipDtstamp)
        assertEquals("the series' reminders, not the file's", rows(localId).single { it.id == masterId }.reminders, exception.reminders)
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
    }

    // ---- a second offline calendar, under the same LOCAL account as the built-in ----

    @Test
    fun `a CANCEL without STATUS from the meeting's organizer cancels it on a second offline calendar`() = runBlocking {
        val offlineId = db.insertOfflineCalendar()
        importFile("nextcloud_request.ics", offlineId)

        val result = importFile("nextcloud_cancel.ics", offlineId)

        assertEquals("$result", 1, result.cancelled)
        assertEquals("CANCELLED", masters(offlineId).single().status)
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
    }

    @Test
    fun `a newer REQUEST on a second offline calendar rewrites the series as the file has it`() = runBlocking {
        val offlineId = db.insertOfflineCalendar()
        importText(message("REQUEST", vevent(1, "20261001T100000Z")), offlineId)
        val before = rows(offlineId).single()

        val result = importText(message("REQUEST", vevent(2, "20261005T100000Z", summary = "Weekly sync moved")), offlineId)

        assertEquals("$result", 1, result.updated)
        val after = rows(offlineId).single()
        assertEquals(before.id, after.id)
        assertEquals("Weekly sync moved", after.title)
        assertEquals("the file's SEQUENCE, not a bumped one", 2, after.sequence)
        assertEquals(SyncStatus.SYNCED, after.syncStatus)
        assertTrue(db.pendingOperationsDao().getAllOnce().isEmpty())
    }

    @Test
    fun `a plain file whose UID is in another offline calendar is imported as a copy with a fresh UID`() = runBlocking {
        val offlineId = db.insertOfflineCalendar()
        val builtInId = LocalCalendarInitializer(db).getLocalCalendarId()
        importFile("nextcloud_request.ics", builtInId)
        val original = masters(builtInId).single()
        val plain = fixture("nextcloud_request.ics").replace(Regex("METHOD:REQUEST\r?\n"), "")

        importText(plain, offlineId)

        val copy = masters(offlineId).single()
        assertTrue("fresh UID, got ${copy.uid}", copy.uid != nextcloudUid)
        assertEquals(original, masters(builtInId).single())
    }

    @Test
    fun `an invitation whose UID is in another offline calendar is skipped as on another calendar`() = runBlocking {
        val offlineId = db.insertOfflineCalendar()
        val builtInId = LocalCalendarInitializer(db).getLocalCalendarId()
        importFile("nextcloud_request.ics", builtInId)

        val result = importFile("nextcloud_request.ics", offlineId)

        assertEquals(listOf(IgnoreReason.ON_ANOTHER_CALENDAR), result.ignored)
        assertTrue(masters(offlineId).isEmpty())
    }

    // ---- a newer invitation for a meeting the CalDAV calendar holds ----

    private val guestAddress = "attendee@example.test"
    private val rescheduleUid = "0example0reschedule0uid00@example.test"
    private val rescheduleUrl = "https://dav.example.test/cal/personal/$rescheduleUid.ics"

    /** The user's synced copy of the meeting, seeded from the KashCal export of it. */
    private suspend fun seedOriginal(calendarId: Long, edit: (String) -> String = { it }): Event {
        val id = seedSyncedCopy(calendarId, edit(unfolded("reschedule_original_export.ics")))
        return db.eventsDao().getById(id)!!
    }

    private fun rescheduled(edit: (String) -> String = { it }) =
        requireNotNull(IcsParserService.parseForImport(edit(unfolded("reschedule_request.ics"))))

    /** The fixture with RFC 5545 §3.1 folding undone, so a test can edit whole lines. */
    private fun unfolded(name: String) = fixture(name).replace(Regex("\r?\n[ \t]"), "")

    private suspend fun ownPartstat(eventId: Long, address: String = guestAddress) =
        db.attendeesDao().getForEventOnce(eventId).single { it.address.endsWith(address) }.partstat

    private suspend fun pendingOps() = db.pendingOperationsDao().getAllOnce()
        .filter { it.status == PendingOperation.STATUS_PENDING || it.status == PendingOperation.STATUS_IN_PROGRESS }

    @Test
    fun `rescheduled invitation replaces the synced original on a CalDAV calendar`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        val original = seedOriginal(calendarId)
        val newer = rescheduled()
        val newerEvent = newer.groups.single().masters.single().event

        val result = coordinator.importIcsFile(newer, calendarId)

        val stored = db.eventsDao().getById(original.id)!!
        assertEquals("$result", 1, result.updated)
        assertEquals(newerEvent.startTs, stored.startTs)
        assertEquals(newerEvent.endTs, stored.endTs)
        assertEquals(1, stored.sequence)
        assertEquals(newerEvent.itipDtstamp, stored.itipDtstamp)
        assertEquals(SyncStatus.PENDING_UPDATE, stored.syncStatus)
        assertEquals(original.caldavUrl, stored.caldavUrl)
        assertEquals(original.etag, stored.etag)
        assertEquals("the server copy stays the row's copy", original.rawIcal, stored.rawIcal)
        val op = pendingOps().single()
        assertEquals(original.id, op.eventId)
        assertEquals(PendingOperation.OPERATION_UPDATE, op.operation)
        assertEquals(rescheduleUrl, op.targetUrl)
        assertFalse(op.partstatOnly)
    }

    @Test
    fun `a rescheduled invitation with a new title and place applies both`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        val original = seedOriginal(calendarId)

        coordinator.importIcsFile(rescheduled {
            it.replace("SUMMARY:Project review call", "SUMMARY:Project review call (moved)")
                .replace("LOCATION:https://meet.example.test/abc-defg-hij", "LOCATION:Room 4B")
        }, calendarId)

        val stored = db.eventsDao().getById(original.id)!!
        assertEquals("Project review call (moved)", stored.title)
        assertEquals("Room 4B", stored.location)
    }

    @Test
    fun `a reschedule resets the user's answer, a details-only change keeps it`() = runBlocking {
        // The export and the request write the user's ATTENDEE parameters in different orders.
        val accepted = { ics: String -> ics.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$guestAddress", "PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:$guestAddress") }
        val fileSaysAccepted = { ics: String ->
            ics.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=$guestAddress", "PARTSTAT=ACCEPTED;RSVP=TRUE;CN=$guestAddress")
        }

        val moved = caldavCalendarId(guestAddress)
        val movedRow = seedOriginal(moved, accepted)
        assertEquals("ACCEPTED", ownPartstat(movedRow.id))
        val movedFile = rescheduled(fileSaysAccepted)
        assertEquals("the file itself answers ACCEPTED", "ACCEPTED",
            movedFile.groups.single().masters.single().attendees.single { it.address.endsWith(guestAddress) }.partstat)
        coordinator.importIcsFile(movedFile, moved)
        assertEquals("the time moved, so the answer resets whatever the file says", "NEEDS-ACTION", ownPartstat(movedRow.id))

        db.clearAllTables()
        val renamed = caldavCalendarId(guestAddress)
        val renamedRow = seedOriginal(renamed, accepted)
        coordinator.importIcsFile(rescheduled {
            it.replace("20261109T130000", "20261106T140000").replace("20261109T133000", "20261106T143000")
                .replace("SUMMARY:Project review call", "SUMMARY:Renamed")
        }, renamed)
        assertEquals("Renamed", db.eventsDao().getById(renamedRow.id)!!.title)
        assertEquals("only details changed", "ACCEPTED", ownPartstat(renamedRow.id))
    }

    @Test
    fun `an invitation that doesn't list the account still updates, with the file's guests`() = runBlocking {
        val calendarId = caldavCalendarId("someone-else@example.test")
        val original = seedOriginal(calendarId)

        val result = coordinator.importIcsFile(rescheduled(), calendarId)

        assertEquals("$result", 1, result.updated)
        assertEquals(4, db.attendeesDao().getForEventOnce(original.id).size)
        assertEquals(1, pendingOps().size)
    }

    @Test
    fun `an invitation for the user's own meeting writes and queues nothing`() = runBlocking {
        val calendarId = caldavCalendarId("organizer@example.test")
        val original = seedOriginal(calendarId)

        val result = coordinator.importIcsFile(rescheduled(), calendarId)

        assertEquals(listOf(IgnoreReason.OWN_UPDATE), result.ignored)
        assertEquals(original.startTs, db.eventsDao().getById(original.id)!!.startTs)
        assertTrue(pendingOps().isEmpty())
    }

    @Test
    fun `a change waiting to upload blocks the update and an abandoned one does not`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        val original = seedOriginal(calendarId)
        val waiting = db.pendingOperationsDao().insert(PendingOperation(eventId = original.id, operation = PendingOperation.OPERATION_UPDATE, partstatOnly = true, partstatTarget = "ACCEPTED"))

        val blocked = coordinator.importIcsFile(rescheduled(), calendarId)
        assertEquals(listOf(IgnoreReason.CHANGE_WAITING), blocked.ignored)
        assertEquals(original.startTs, db.eventsDao().getById(original.id)!!.startTs)
        assertEquals(1, pendingOps().size)

        db.pendingOperationsDao().update(db.pendingOperationsDao().getById(waiting)!!.copy(status = PendingOperation.STATUS_ABANDONED))
        val applied = coordinator.importIcsFile(rescheduled(), calendarId)
        assertEquals("$applied", 1, applied.updated)
        assertFalse("the new op is a full upload", pendingOps().single().partstatOnly)
    }

    @Test
    fun `the pull's placeholder series keeps the calendar's copy`() = runBlocking {
        val calendarId = caldavCalendarId()
        val placeholderId = db.eventsDao().insert(synthesizeMasterForOrphanException(meetingUid, calendarId, start + day, "Weekly sync"))
        db.eventsDao().insert(
            db.eventsDao().getById(placeholderId)!!.copy(
                id = 0, originalEventId = placeholderId, originalInstanceTime = start + day, status = "CONFIRMED",
                extraProperties = null, caldavUrl = "https://dav.example.test/cal/personal/x.ics", etag = "\"e1\"",
            )
        )

        val result = importText(message("REQUEST", vevent(1, "20261001T100000Z"), vevent(1, "20261001T100000Z", recurrenceId = start + day)), calendarId)

        assertEquals("$result", List(2) { IgnoreReason.CALENDAR_KEEPS_COPY }, result.ignored)
        assertTrue(pendingOps().isEmpty())
    }

    @Test
    fun `a newer series with a changed occurrence updates both and queues one upload`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")))

        val result = importText(
            message("REQUEST", vevent(1, "20261002T100000Z", summary = "Weekly sync v2"),
                vevent(1, "20261002T100000Z", recurrenceId = start + day, startAt = start + day + 7_200_000, summary = "Moved")),
            calendarId,
        )

        assertEquals("$result", 2, result.updated)
        assertEquals("Weekly sync v2", db.eventsDao().getById(masterId)!!.title)
        assertEquals("Moved", db.eventsDao().getExceptionsForMaster(masterId).single().title)
        val op = pendingOps().single()
        assertEquals(masterId, op.eventId)
        assertEquals(PendingOperation.OPERATION_UPDATE, op.operation)
    }

    @Test
    fun `a series update with a cancelled occurrence uploads once, without a separate cancel`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")))

        val result = importText(
            message("REQUEST", vevent(1, "20261002T100000Z", summary = "Weekly sync v2"),
                vevent(1, "20261002T100000Z", recurrenceId = start + day, status = "CANCELLED")),
            calendarId,
        )

        assertEquals("$result", 1, result.updated)
        assertEquals("$result", 1, result.cancelled)
        assertEquals("CANCELLED", db.eventsDao().getExceptionsForMaster(masterId).single().status)
        val op = pendingOps().single()
        assertEquals(masterId, op.eventId)
        assertEquals(PendingOperation.OPERATION_UPDATE, op.operation)
    }

    @Test
    fun `a changed occurrence of a stored series queues the upload on the series`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")))

        val result = importText(message("REQUEST", vevent(1, "20261002T100000Z", recurrenceId = start + day, startAt = start + day + 3_600_000)), calendarId)

        assertEquals("$result", 1, result.updated)
        assertEquals(masterId, pendingOps().single().eventId)
    }

    @Test
    fun `a reordered repeat rule is not a reschedule`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")).replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$selfAddress", "PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:$selfAddress"))

        importText(message("REQUEST", vevent(1, "20261002T100000Z", rrule = "COUNT=5;FREQ=DAILY", summary = "Renamed")), calendarId)

        assertEquals("Renamed", db.eventsDao().getById(masterId)!!.title)
        assertEquals("ACCEPTED", ownPartstat(masterId, selfAddress))
    }

    @Test
    fun `a new repeat rule resets the user's answer`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")).replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$selfAddress", "PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:$selfAddress"))

        importText(message("REQUEST", vevent(1, "20261002T100000Z", rrule = "FREQ=DAILY;COUNT=3")), calendarId)

        assertEquals("FREQ=DAILY;COUNT=3", db.eventsDao().getById(masterId)!!.rrule)
        assertEquals("NEEDS-ACTION", ownPartstat(masterId, selfAddress))
    }

    @Test
    fun `a renamed occurrence keeps the answer the user gave the series`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")).replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$selfAddress", "PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:$selfAddress"))

        importText(message("REQUEST", vevent(1, "20261002T100000Z", recurrenceId = start + day, summary = "Renamed occurrence")), calendarId)

        val exception = db.eventsDao().getExceptionsForMaster(masterId).single()
        assertEquals("Renamed occurrence", exception.title)
        assertEquals("ACCEPTED", ownPartstat(exception.id, selfAddress))
    }

    @Test
    fun `a change waiting on an occurrence blocks an update of the series`() = runBlocking {
        val calendarId = caldavCalendarId()
        val masterId = seedSyncedCopy(calendarId, message("REQUEST", vevent(0, "20261001T100000Z")))
        importText(message("REQUEST", vevent(1, "20261002T100000Z", recurrenceId = start + day, summary = "Moved")), calendarId)
        val exception = db.eventsDao().getExceptionsForMaster(masterId).single()
        db.pendingOperationsDao().getAllOnce().forEach { db.pendingOperationsDao().deleteById(it.id) }
        db.pendingOperationsDao().insert(PendingOperation(eventId = exception.id, operation = PendingOperation.OPERATION_UPDATE, partstatOnly = true, partstatTarget = "ACCEPTED"))

        val preview = coordinator.previewIcsImport(requireNotNull(IcsParserService.parseForImport(message("REQUEST", vevent(2, "20261003T100000Z", summary = "v2")))), calendarId)
        val result = importText(message("REQUEST", vevent(2, "20261003T100000Z", summary = "v2")), calendarId)

        assertEquals("the sheet shows it before the tap", IgnoreReason.CHANGE_WAITING, (preview.single().actions.single() as org.onekash.kashcal.domain.ics.ImportAction.Ignored).reason)
        assertEquals(listOf(IgnoreReason.CHANGE_WAITING), result.ignored)
        assertEquals("Weekly sync", db.eventsDao().getById(masterId)!!.title)
    }

    @Test
    fun `an event never uploaded takes the change without a second upload`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        val original = seedOriginal(calendarId)
        db.eventsDao().update(original.copy(syncStatus = SyncStatus.PENDING_CREATE, caldavUrl = null, etag = null))
        db.pendingOperationsDao().insert(PendingOperation(eventId = original.id, operation = PendingOperation.OPERATION_CREATE, status = PendingOperation.STATUS_FAILED))

        val result = coordinator.importIcsFile(rescheduled(), calendarId)

        assertEquals("$result", 1, result.updated)
        assertEquals(SyncStatus.PENDING_CREATE, db.eventsDao().getById(original.id)!!.syncStatus)
        assertEquals(listOf(PendingOperation.OPERATION_CREATE), db.pendingOperationsDao().getAllOnce().map { it.operation })
    }

    @Test
    fun `an update keeps the user's tags, free-busy and color`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        val original = seedOriginal(calendarId)
        db.eventsDao().update(original.copy(categories = listOf("Work"), transp = "TRANSPARENT", color = 0xFF123456.toInt()))

        coordinator.importIcsFile(rescheduled(), calendarId)

        val stored = db.eventsDao().getById(original.id)!!
        assertEquals(listOf("Work"), stored.categories)
        assertEquals("TRANSPARENT", stored.transp)
        assertEquals(0xFF123456.toInt(), stored.color)
    }

    @Test
    fun `importing the same rescheduled file twice updates once`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        seedOriginal(calendarId)

        coordinator.importIcsFile(rescheduled(), calendarId)
        db.pendingOperationsDao().getAllOnce().forEach { db.pendingOperationsDao().deleteById(it.id) }
        val again = coordinator.importIcsFile(rescheduled(), calendarId)

        assertEquals("$again", 1, again.upToDate)
        assertTrue(pendingOps().isEmpty())
    }

    @Test
    fun `a plain export of the meeting doesn't update a CalDAV calendar`() = runBlocking {
        val calendarId = caldavCalendarId(guestAddress)
        val original = seedOriginal(calendarId)

        val result = importText(fixture("reschedule_request.ics").replace("METHOD:REQUEST\r\n", ""), calendarId)

        assertEquals(listOf(IgnoreReason.CALENDAR_KEEPS_COPY), result.ignored)
        assertEquals(original.startTs, db.eventsDao().getById(original.id)!!.startTs)
    }
}
