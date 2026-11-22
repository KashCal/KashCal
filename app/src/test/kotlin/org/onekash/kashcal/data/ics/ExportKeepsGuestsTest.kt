package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * An exported invitation keeps its guests and the user's own answer, on the series and on each
 * changed occurrence, through single-event and whole-calendar export, and they come back on a
 * re-import.
 *
 * Covers a series stored the way the pull stores it (each VEVENT mapped, the resource's body
 * stored once on the series row) and invitation files imported into an offline calendar, over
 * real Room, the real [EventCoordinator] export reads and the real [IcsExporter]. The rows hold
 * the guests; the stored copy of an imported file contributes none ([Event.holdsFileCopy]), so
 * only the rows can supply them. Run the class alone: Robolectric runs of more than one class
 * hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExportKeepsGuestsTest {

    private lateinit var context: Context
    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private val openDbs = mutableListOf<KashCalDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val (database, built) = newCoordinator()
        db = database
        coordinator = built
    }

    @After
    fun tearDown() = openDbs.forEach { it.close() }

    private fun newCoordinator(): Pair<KashCalDatabase, EventCoordinator> {
        val database = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
            .also { openDbs += it }
        val generator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        val accounts = AccountRepositoryImpl(
            accountsDao = database.accountsDao(),
            addressBookDao = database.addressBookDao(),
            calendarsDao = database.calendarsDao(),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            credentialManager = org.onekash.kashcal.data.credential.FakeCredentialManager(),
            reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository(),
        )
        val dataStore = mockk<KashCalDataStore> {
            every { defaultReminderMinutes } returns flowOf(15)
            every { defaultAllDayReminder } returns flowOf(1440)
        }
        return database to EventCoordinator(
            eventWriter = EventWriter(database, generator),
            eventReader = EventReader(database),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(database),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accounts,
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = dataStore,
        )
    }

    // ---- synced series ----

    @Test
    fun `a synced invitation series exports every guest of the series and the changed occurrence`() = runBlocking {
        val calendarId = storeSynced(SERIES_VEVENTS)

        for ((path, text) in bothExports(calendarId)) {
            val (series, changed) = seriesAndChanged(text, path)
            assertEquals("$path: series guests", SERIES_GUESTS, attendees(series).keys)
            assertEquals("$path: changed occurrence guests", CHANGED_GUESTS, attendees(changed).keys)
        }
    }

    @Test
    fun `a synced invitation exports the user's answer per occurrence`() = runBlocking {
        val calendarId = storeSynced(SERIES_VEVENTS)

        for ((path, text) in bothExports(calendarId)) {
            val (series, changed) = seriesAndChanged(text, path)
            assertEquals("$path: series answer", "ACCEPTED", attendees(series)[ME])
            assertEquals("$path: changed occurrence answer", "DECLINED", attendees(changed)[ME])
        }
    }

    @Test
    fun `an answer given in the app is the one exported`() = runBlocking {
        val calendarId = storeSynced(SERIES_VEVENTS)
        val changedId = db.eventsDao().getExceptionsForMaster(series(calendarId).id).single().id

        assertTrue("the RSVP is saved", coordinator.replyRsvp(changedId, "TENTATIVE"))

        for ((path, text) in bothExports(calendarId)) {
            val (series, changed) = seriesAndChanged(text, path)
            assertEquals("$path: series answer", "ACCEPTED", attendees(series)[ME])
            assertEquals("$path: changed occurrence answer", "TENTATIVE", attendees(changed)[ME])
        }
    }

    @Test
    fun `a synced changed occurrence exported on its own keeps its guests`() = runBlocking {
        val calendarId = storeSynced(SERIES_VEVENTS)
        val changedId = db.eventsDao().getExceptionsForMaster(series(calendarId).id).single().id

        val text = exportEvent(changedId)
        val block = veventBlocks(text).single()
        assertEquals(CHANGED_GUESTS, attendees(block).keys)
        assertEquals("DECLINED", attendees(block)[ME])
    }

    @Test
    fun `a synced series whose attendee rows are missing keeps its stored copy's guests`() = runBlocking {
        // Rows synced before the attendees table existed hold their guests only in the copy.
        val calendarId = storeSynced(SERIES_VEVENTS, withAttendeeRows = false)

        for ((path, text) in bothExports(calendarId)) {
            val (series, _) = seriesAndChanged(text, path)
            assertEquals("$path: series guests", SERIES_GUESTS, attendees(series).keys)
        }
    }

    @Test
    fun `an imported invitation without attendee rows exports none from its file copy`() = runBlocking {
        // A file's ATTENDEE block is the file's, not the user's current guests.
        val calendarId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        import(inviteFile(SERIES_VEVENTS), calendarId)
        val master = series(calendarId)
        (listOf(master) + db.eventsDao().getExceptionsForMaster(master.id)).forEach {
            db.attendeesDao().replaceForEvent(it.id, emptyList())
        }

        for ((path, text) in bothExports(calendarId)) {
            val (series, changed) = seriesAndChanged(text, path)
            assertEquals("$path: series guests", emptyMap<String, String?>(), attendees(series))
            assertEquals("$path: changed occurrence guests", emptyMap<String, String?>(), attendees(changed))
        }
    }

    // ---- imported invitations ----

    @Test
    fun `an imported invitation series exports its guests and the user's answers`() = runBlocking {
        val calendarId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        import(inviteFile(SERIES_VEVENTS), calendarId)

        for ((path, text) in bothExports(calendarId)) {
            val (series, changed) = seriesAndChanged(text, path)
            assertTrue("$path: organizer kept", series.any { it.startsWith("ORGANIZER") })
            assertEquals("$path: series guests", SERIES_GUESTS, attendees(series).keys)
            assertEquals("$path: changed occurrence guests", CHANGED_GUESTS, attendees(changed).keys)
            assertEquals("$path: series answer", "ACCEPTED", attendees(series)[ME])
            assertEquals("$path: changed occurrence answer", "DECLINED", attendees(changed)[ME])
        }
    }

    @Test
    fun `an imported one-off invitation exports its guests`() = runBlocking {
        val calendarId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        import(inviteFile(ONE_OFF_VEVENT), calendarId)

        for ((path, text) in bothExports(calendarId)) {
            val block = veventBlocks(text).single()
            assertTrue("$path: organizer kept", block.any { it.startsWith("ORGANIZER") })
            assertEquals("$path: guests", setOf(ME, "ann@example.test"), attendees(block).keys)
            assertEquals("$path: answer", "ACCEPTED", attendees(block)[ME])
        }
    }

    @Test
    fun `a whole-calendar export of a series whose stored rule can't be parsed keeps its guests`() = runBlocking {
        val calendarId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        import(inviteFile(SERIES_VEVENTS), calendarId)
        db.eventsDao().update(series(calendarId).copy(rrule = "FREQ=SOMETIMES;;"))

        val text = exportCalendar(calendarId)
        val (series, changed) = seriesAndChanged(text, "calendar")
        assertEquals("series guests", SERIES_GUESTS, attendees(series).keys)
        assertEquals("changed occurrence guests", CHANGED_GUESTS, attendees(changed).keys)
    }

    @Test
    fun `an exported invitation re-imported elsewhere keeps its guests and answers`() = runBlocking {
        val calendarId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        import(inviteFile(SERIES_VEVENTS), calendarId)
        val exported = exportEvent(series(calendarId).id)
        assertNotNull("the exported file parses", ICalParser().parseAllEvents(exported).getOrNull())

        // A fresh install, so the UID is new there and the file imports as itself.
        val (otherDb, otherCoordinator) = newCoordinator()
        val otherCalendar = LocalCalendarInitializer(otherDb).ensureLocalCalendarExists()
        otherCoordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(exported)), otherCalendar)

        val master = otherDb.eventsDao().getAllMasterEventsForCalendar(otherCalendar).single()
        val changed = otherDb.eventsDao().getExceptionsForMaster(master.id).single()
        val seriesRows = otherDb.attendeesDao().getForEventOnce(master.id).associate { bare(it.address) to it.partstat }
        val changedRows = otherDb.attendeesDao().getForEventOnce(changed.id).associate { bare(it.address) to it.partstat }
        assertEquals(SERIES_GUESTS, seriesRows.keys)
        assertEquals(CHANGED_GUESTS, changedRows.keys)
        assertEquals("ACCEPTED", seriesRows[ME])
        assertEquals("DECLINED", changedRows[ME])
    }

    // ---- control ----

    @Test
    fun `an offline event without guests exports none`() = runBlocking {
        val calendarId = LocalCalendarInitializer(db).ensureLocalCalendarExists()
        db.eventsDao().insert(
            Event(
                uid = "plain@example.test", calendarId = calendarId, title = "Dentist",
                startTs = 1_793_000_000_000L, endTs = 1_793_003_600_000L, dtstamp = 1_793_000_000_000L,
            )
        )

        for ((path, text) in bothExports(calendarId)) {
            assertEquals("$path: guests", emptyMap<String, String?>(), attendees(veventBlocks(text).single()))
        }
    }

    // ---- helpers ----

    /** Stores [vevents] as the pull stores a server resource, with or without attendee rows. */
    private suspend fun storeSynced(vevents: String, withAttendeeRows: Boolean = true): Long {
        val accountId = db.accountsDao().insert(
            Account(
                provider = AccountProvider.CALDAV, email = ME,
                calendarUserAddresses = listOf("mailto:$ME"),
            )
        )
        val calendarId = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://dav.example.test/cal/p/", displayName = "P", color = 0)
        )
        val body = calendar(vevents)
        val url = "https://dav.example.test/cal/p/guests.ics"
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!
        val masterIcal = parsed.first { it.recurrenceId == null }
        val master = ICalEventMapper.toEntity(masterIcal, body, calendarId, url, "\"e1\"")
        val masterId = db.eventsDao().insert(master.event)
        // The pull keeps the resource's body once, on the series row.
        org.onekash.kashcal.data.db.EventIcsStore(db).write(masterId, master.ics)
        if (withAttendeeRows) db.attendeesDao().replaceForEvent(masterId, master.attendees.map { it.copy(eventId = masterId) })
        parsed.filter { it.recurrenceId != null }.forEach { occurrence ->
            val mapped = ICalEventMapper.toEntity(occurrence, body, calendarId, url, "\"e1\"", masterIcal.dtStart)
            val id = db.eventsDao().insert(mapped.event.copy(originalEventId = masterId))
            if (withAttendeeRows) db.attendeesDao().replaceForEvent(id, mapped.attendees.map { it.copy(eventId = id) })
        }
        return calendarId
    }

    private suspend fun import(text: String, calendarId: Long) =
        coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(text)), calendarId)

    private suspend fun series(calendarId: Long): Event =
        db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()

    private suspend fun bothExports(calendarId: Long): List<Pair<String, String>> = listOf(
        "event" to exportEvent(db.eventsDao().getAllMasterEventsForCalendar(calendarId).single().id),
        "calendar" to exportCalendar(calendarId),
    )

    private suspend fun exportEvent(eventId: Long): String {
        val shared = File(context.cacheDir, "shared").apply { deleteRecursively() }
        // The file is written before the FileProvider lookup, which has no provider here.
        IcsExporter().exportEvent(context, requireNotNull(coordinator.getEventForExport(eventId)))
        return shared.listFiles()!!.single().readText()
    }

    private suspend fun exportCalendar(calendarId: Long): String {
        val shared = File(context.cacheDir, "shared").apply { deleteRecursively() }
        IcsExporter().exportCalendar(context, coordinator.getCalendarEventsForExport(calendarId), "Calendar")
        return shared.listFiles()!!.single().readText()
    }

    private fun unfold(text: String) = text.replace(Regex("\r?\n[ \t]"), "").replace("\r\n", "\n")

    private fun veventBlocks(text: String): List<List<String>> =
        Regex("BEGIN:VEVENT\n(.*?)\nEND:VEVENT", RegexOption.DOT_MATCHES_ALL)
            .findAll(unfold(text)).map { it.groupValues[1].lines() }.toList()

    private fun seriesAndChanged(text: String, path: String): Pair<List<String>, List<String>> {
        val blocks = veventBlocks(text)
        assertEquals("$path export holds the series and its changed occurrence", 2, blocks.size)
        return blocks.single { b -> b.none { it.startsWith("RECURRENCE-ID") } } to
            blocks.single { b -> b.any { it.startsWith("RECURRENCE-ID") } }
    }

    /** Each ATTENDEE line of [block] as bare address to PARTSTAT. */
    private fun attendees(block: List<String>): Map<String, String?> =
        block.filter { it.startsWith("ATTENDEE") }.associate { line ->
            bare(line.substringAfterLast(':')) to Regex(";PARTSTAT=([A-Z-]+)").find(line)?.groupValues?.get(1)
        }

    private fun bare(address: String) = address.removePrefix("mailto:").removePrefix("MAILTO:").lowercase()

    private fun calendar(vevents: String, method: String? = null) = listOfNotNull(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Guests//EN", method?.let { "METHOD:$it" },
        vevents, "END:VCALENDAR", "",
    ).joinToString("\r\n")

    private fun inviteFile(vevents: String) = calendar(vevents, method = "REQUEST")

    private companion object {
        const val ME = "testuser1@example.test"
        val SERIES_GUESTS = setOf(ME, "ann@example.test", "bob@example.test")
        val CHANGED_GUESTS = SERIES_GUESTS + "cara@example.test"

        fun lines(vararg l: String) = l.joinToString("\r\n")

        val SERIES_VEVENTS = lines(
            "BEGIN:VEVENT", "UID:guests-series@example.test", "DTSTAMP:20261001T120000Z", "SEQUENCE:1",
            "DTSTART:20261102T150000Z", "DTEND:20261102T160000Z", "RRULE:FREQ=WEEKLY;COUNT=6",
            "SUMMARY:Weekly sync", "ORGANIZER;CN=Org:mailto:org@example.test",
            "ATTENDEE;CN=Me;PARTSTAT=ACCEPTED:mailto:$ME",
            "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED:mailto:ann@example.test",
            "ATTENDEE;CN=Bob;PARTSTAT=TENTATIVE:mailto:bob@example.test",
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:guests-series@example.test", "DTSTAMP:20261001T120000Z", "SEQUENCE:1",
            "RECURRENCE-ID:20261109T150000Z", "DTSTART:20261109T170000Z", "DTEND:20261109T180000Z",
            "SUMMARY:Weekly sync (moved)", "ORGANIZER;CN=Org:mailto:org@example.test",
            "ATTENDEE;CN=Me;PARTSTAT=DECLINED:mailto:$ME",
            "ATTENDEE;CN=Ann;PARTSTAT=ACCEPTED:mailto:ann@example.test",
            "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.test",
            "ATTENDEE;CN=Cara;PARTSTAT=NEEDS-ACTION:mailto:cara@example.test",
            "END:VEVENT",
        )

        val ONE_OFF_VEVENT = lines(
            "BEGIN:VEVENT", "UID:guests-single@example.test", "DTSTAMP:20261001T120000Z", "SEQUENCE:0",
            "DTSTART:20261104T150000Z", "DTEND:20261104T160000Z", "SUMMARY:Lunch",
            "ORGANIZER;CN=Org:mailto:org@example.test",
            "ATTENDEE;CN=Me;PARTSTAT=ACCEPTED:mailto:$ME",
            "ATTENDEE;CN=Ann;PARTSTAT=NEEDS-ACTION:mailto:ann@example.test",
            "END:VEVENT",
        )
    }
}
