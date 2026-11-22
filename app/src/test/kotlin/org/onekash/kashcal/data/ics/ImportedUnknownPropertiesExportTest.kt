package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.EventExport
import org.onekash.kashcal.domain.reader.AttendeeBackfill
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * An .ics file imported into an offline calendar keeps the properties the app doesn't model
 * exactly as the file wrote them when the event, or the whole calendar, is exported, and the
 * exported file imports again.
 *
 * The lines are shapes found on real events: a structured location with quoted parameters and an
 * escaped newline, a mail suggestion key with escapes, meeting-location JSON with an escaped
 * comma, a multi-line COMMENT and a repeated property. Rebuilt from decoded values they turn into
 * broken lines and the file doesn't parse (RFC 5545 §3.2.20 asks for the value data to be
 * kept as is).
 *
 * Drives the real import ([IcsParserService.parseForImport], [EventCoordinator.importIcsFile])
 * over real Room and the real [IcsExporter].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ImportedUnknownPropertiesExportTest {

    private lateinit var context: Context
    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private lateinit var localCalendars: LocalCalendarInitializer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        this.generator = generator
        val accounts = AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
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
        localCalendars = LocalCalendarInitializer(db)
        newCoordinator = { reminderScheduler ->
            EventCoordinator(
                eventWriter = EventWriter(db, generator),
                eventReader = EventReader(db),
                occurrenceGenerator = generator,
                localCalendarInitializer = localCalendars,
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
        }
        coordinator = newCoordinator(mockk(relaxed = true))
    }

    private lateinit var newCoordinator: (ReminderScheduler) -> EventCoordinator
    private lateinit var generator: OccurrenceGenerator

    private fun realScheduler() = ReminderScheduler(
        context = context,
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

    // ---- single event ----

    @Test
    fun `an imported event exports its unknown properties as written`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        assertEquals(1, import(singleFile, calendarId).created)
        val stored = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()

        for ((path, text) in listOf("event" to exportEvent(stored), "calendar" to exportCalendar(calendarId))) {
            val vevents = veventBlocks(text)
            assertEquals("$path export holds one VEVENT", 1, vevents.size)
            assertEquals("$path export keeps every unknown line as written", SINGLE_LINES, unknownLines(vevents.single()))
            assertNoBrokenLines(path, text)
            val reparsed = ICalParser().parseAllEvents(text).getOrNull()
            assertTrue("$path export parses", reparsed != null)
            assertEquals("$path export reparses to the same lines", SINGLE_LINES, reparsed!!.single().unknownPropertyLines)
        }
    }

    @Test
    fun `an exported file imports again into another offline calendar with its lines`() = runBlocking {
        val first = localCalendars.ensureLocalCalendarExists()
        import(singleFile, first)
        val exported = exportEvent(db.eventsDao().getAllMasterEventsForCalendar(first).single())

        val second = localCalendars.createCalendar("Second", 0, "Local")
        val again = import(exported, second)
        assertEquals("created", 1, again.created)
        assertEquals("failed", 0, again.failed)
        val copy = db.eventsDao().getAllMasterEventsForCalendar(second).single()
        val text = exportEvent(copy)
        assertEquals(SINGLE_LINES, unknownLines(veventBlocks(text).single()))
        assertNoBrokenLines("re-import", text)
    }

    // ---- series with a changed occurrence ----

    @Test
    fun `an imported series exports each VEVENT with only its own lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        assertEquals(2, import(seriesFile(SERIES_UID), calendarId).created)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        val exceptions = db.eventsDao().getExceptionsForMaster(series.id)

        for ((path, text) in listOf(
            "event" to exportEvent(series, exceptions),
            "calendar" to exportCalendar(calendarId),
        )) {
            val (seriesBlock, occurrenceBlock) = seriesAndOccurrence(text, path)
            assertEquals("$path: the series keeps its lines", SERIES_LINES, unknownLines(seriesBlock))
            assertEquals("$path: the changed occurrence keeps its own", OCCURRENCE_LINES, unknownLines(occurrenceBlock))
            assertNoBrokenLines(path, text)
        }
    }

    @Test
    fun `an imported series re-imported where its UID exists keeps every VEVENT's lines and other UIDs`() =
        runBlocking {
            val first = localCalendars.ensureLocalCalendarExists()
            import(seriesFile(SERIES_UID), first)
            val series = db.eventsDao().getAllMasterEventsForCalendar(first).single()
            val exported = exportEvent(series, db.eventsDao().getExceptionsForMaster(series.id))

            val second = localCalendars.createCalendar("Second", 0, "Local")
            assertEquals(2, import(exported, second).created)
            val copy = db.eventsDao().getAllMasterEventsForCalendar(second).single()
            assertTrue("the copy gets a fresh UID", copy.uid != SERIES_UID)
            val text = exportEvent(copy, db.eventsDao().getExceptionsForMaster(copy.id))
            val (seriesBlock, occurrenceBlock) = seriesAndOccurrence(text, "re-import")
            assertEquals(SERIES_LINES, unknownLines(seriesBlock))
            assertEquals(OCCURRENCE_LINES, unknownLines(occurrenceBlock))
            assertEquals("RELATED-TO keeps the file's UID", "RELATED-TO:$SERIES_UID-parent", seriesBlock.single { it.startsWith("RELATED-TO") })
            assertTrue("a VALARM UID is kept", seriesBlock.contains("UID:$SERIES_UID-alarm"))
        }

    @Test
    fun `a changed occurrence exported on its own carries its own lines and RECURRENCE-ID`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        val occurrence = db.eventsDao().getExceptionsForMaster(series.id).single()

        val text = exportEvent(occurrence)
        val block = veventBlocks(text).single()
        assertEquals(OCCURRENCE_LINES, unknownLines(block))
        assertEquals("RECURRENCE-ID:20261109T160000Z", block.single { it.startsWith("RECURRENCE-ID") })
        assertNoBrokenLines("occurrence alone", text)
    }

    @Test
    fun `the quick view's export reads the stored copy the occurrence query leaves out`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()

        val shown = db.occurrencesDao().getOccurrencesWithEventsInRange(0, Long.MAX_VALUE).first()
            .first { it.eventId == series.id }.event

        val export = requireNotNull(coordinator.getEventForExport(shown.id))
        val stored = requireNotNull(EventIcsStore(db).read(series)) { "the import stored the file's text" }
        assertEquals("the export carries the series' stored ICS", stored, export.icsByEventId[series.id])
        assertEquals(1, export.exceptions.size)
        val (seriesBlock, occurrenceBlock) = seriesAndOccurrence(exportEvent(export), "quick view")
        assertEquals(SERIES_LINES, unknownLines(seriesBlock))
        assertEquals(OCCURRENCE_LINES, unknownLines(occurrenceBlock))
    }

    // ---- edits, splits and moves ----

    @Test
    fun `editing an imported series keeps every VEVENT's lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        coordinator.updateEvent(series.copy(title = "Weekly review (renamed)"))

        val (seriesBlock, occurrenceBlock) = seriesAndOccurrence(exportSeries(calendarId), "edit")
        assertTrue(seriesBlock.contains("SUMMARY:Weekly review (renamed)"))
        assertEquals(SERIES_LINES, unknownLines(seriesBlock))
        assertEquals(OCCURRENCE_LINES, unknownLines(occurrenceBlock))
    }

    @Test
    fun `re-editing the imported changed occurrence keeps its own lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        editOccurrence(series.id, NOV_9, "Weekly review (moved and renamed)")

        val (_, occurrenceBlock) = seriesAndOccurrence(exportSeries(calendarId), "re-edit")
        assertTrue(occurrenceBlock.contains("SUMMARY:Weekly review (moved and renamed)"))
        assertEquals(OCCURRENCE_LINES, unknownLines(occurrenceBlock))
    }

    @Test
    fun `an occurrence changed for the first time starts from the series' lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        editOccurrence(series.id, NOV_16, "Weekly review (one-off)")

        val blocks = veventBlocks(exportSeries(calendarId))
        val changed = blocks.single { b -> b.contains("SUMMARY:Weekly review (one-off)") }
        assertEquals(SERIES_LINES, unknownLines(changed))
        assertNoBrokenLines("first-time edit", exportSeries(calendarId))
    }

    @Test
    fun `an occurrence changed in the form and exported on its own carries the series' lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        val changed = editOccurrence(series.id, NOV_16, "Weekly review (one-off)")

        val text = exportEvent(db.eventsDao().getById(changed.id)!!)
        val block = veventBlocks(text).single()
        assertEquals(SERIES_LINES, unknownLines(block))
        assertTrue(block.contains("RECURRENCE-ID:20261116T160000Z"))
        assertNoBrokenLines("form-changed occurrence alone", text)
        assertTrue("parses", ICalParser().parseAllEvents(text).getOrNull() != null)
    }

    @Test
    fun `splitting an imported series exports the new series without the old changed occurrence`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        val later = coordinator.editThisAndFuture(series.id, NOV_16) { it.copy(title = "Weekly review (later)") }

        val text = exportEvent(later, db.eventsDao().getExceptionsForMaster(later.id))
        val blocks = veventBlocks(text)
        assertEquals("only the new series", 1, blocks.size)
        assertEquals(SERIES_LINES, unknownLines(blocks.single()))
        assertNoBrokenLines("split", text)
    }

    @Test
    fun `an occurrence of a split series exported on its own doesn't take the old series' occurrence`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        val later = coordinator.editThisAndFuture(series.id, NOV_2_PLUS_DAY) { it.copy(title = "Weekly review (later)") }
        val changed = editOccurrence(later.id, NOV_9, "Weekly review (later, one-off)".replace(",", ""))

        val text = exportEvent(db.eventsDao().getById(changed.id)!!)
        val block = veventBlocks(text).single()
        assertEquals("the new series' lines, not the old changed occurrence's", SERIES_LINES, unknownLines(block))
        assertTrue(block.any { it.startsWith("UID:${later.uid}") })
    }

    @Test
    fun `an imported event moved to another offline calendar keeps its lines`() = runBlocking {
        val first = localCalendars.ensureLocalCalendarExists()
        import(singleFile, first)
        val second = localCalendars.createCalendar("Second", 0, "Local")
        coordinator.moveEventToCalendar(db.eventsDao().getAllMasterEventsForCalendar(first).single().id, second)

        val moved = db.eventsDao().getAllMasterEventsForCalendar(second).single()
        assertEquals(SINGLE_LINES, unknownLines(veventBlocks(exportEvent(moved)).single()))
    }

    @Test
    fun `an uploaded import moved to an offline calendar exports its lines`() = runBlocking {
        // A move to another account refuses an event with guests, so this one has none.
        val caldav = caldavCalendarId()
        import(singleFile, caldav)
        val row = db.eventsDao().getAllMasterEventsForCalendar(caldav).single()
        // The upload stores the body it sent with the resource URL, as the push does.
        val store = EventIcsStore(db)
        val sent = org.onekash.kashcal.sync.parser.icaldav.IcsPatcher.serialize(row, store.read(row))
        db.eventsDao().markCreatedOnServer(
            row.id, "https://dav.example.test/cal/personal/${row.uid}.ics", "\"e1\"", System.currentTimeMillis(),
        )
        store.write(row.id, sent)
        val uploaded = db.eventsDao().getById(row.id)!!
        assertFalse("on its server the copy is the server's", uploaded.holdsFileCopy(store.read(uploaded)))
        val offline = localCalendars.ensureLocalCalendarExists()
        coordinator.moveEventToCalendar(row.id, offline)

        val moved = db.eventsDao().getById(row.id)!!
        assertTrue("off its server nobody refreshes the copy", moved.holdsFileCopy(store.read(moved)))
        assertEquals(SINGLE_LINES, unknownLines(veventBlocks(exportEvent(moved)).single()))
    }

    @Test
    fun `a synced event moved into an offline calendar keeps its server copy when a newer file updates it`() =
        runBlocking {
            val caldav = caldavCalendarId()
            val parsed = ICalParser().parseAllEvents(singleFile).getOrNull()!!.single()
            val synced = org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper.toEntity(
                parsed, singleFile, caldav, "https://dav.example.test/cal/personal/${parsed.uid}.ics", "\"e1\"",
            )
            val id = db.eventsDao().insert(synced.event)
            val store = EventIcsStore(db)
            store.write(id, synced.ics)
            val offline = localCalendars.ensureLocalCalendarExists()
            coordinator.moveEventToCalendar(id, offline)
            val before = db.eventsDao().getById(id)!!
            val beforeIcs = store.read(before)
            assertTrue("the moved row keeps the server's copy", beforeIcs != null)

            val newer = singleFile.replace("SUMMARY:Visitor tour", "SUMMARY:Visitor tour (new)\r\nSEQUENCE:1")
                .replace("RESOURCES:Whiteboard", "RESOURCES:Easel")
            assertEquals(1, import(newer, offline).updated)
            val after = db.eventsDao().getById(id)!!
            assertEquals("Visitor tour (new)", after.title)
            assertEquals("the server copy stays", beforeIcs, store.read(after))
            assertFalse("not a file copy", after.holdsFileCopy(store.read(after)))
        }

    // ---- re-importing ----

    @Test
    fun `re-importing the same file changes nothing`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val before = db.eventsDao().getByUid(SERIES_UID).sortedBy { it.id }
        val again = import(seriesFile(SERIES_UID), calendarId)
        assertEquals(2, again.upToDate)
        assertEquals(before, db.eventsDao().getByUid(SERIES_UID).sortedBy { it.id })
    }

    @Test
    fun `a newer file updates the lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val newer = seriesFile(SERIES_UID, sequence = 1, seriesLines = NEWER_SERIES_LINES)
        assertEquals(2, import(newer, calendarId).updated)

        val (seriesBlock, occurrenceBlock) = seriesAndOccurrence(exportSeries(calendarId), "newer file")
        assertEquals(NEWER_SERIES_LINES, unknownLines(seriesBlock))
        assertEquals(OCCURRENCE_LINES, unknownLines(occurrenceBlock))
    }

    @Test
    fun `a newer file with one changed occurrence adds it with its lines, and an edit keeps them`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val onlyNew = file(
            vevent(
                "UID:$SERIES_UID", "DTSTAMP:20261001T120000Z", "SEQUENCE:1", "RECURRENCE-ID:20261116T160000Z",
                "DTSTART:20261116T200000Z", "DTEND:20261116T210000Z", "SUMMARY:Weekly review (evening)",
                *NEW_OCCURRENCE_LINES.toTypedArray(),
            ),
        )
        assertEquals(1, import(onlyNew, calendarId).updated)

        fun evening(text: String) = veventBlocks(text).single { b -> b.any { it.startsWith("SUMMARY:Weekly review (evening") } }
        assertEquals(NEW_OCCURRENCE_LINES, unknownLines(evening(exportSeries(calendarId))))
        assertEquals("the earlier changed occurrence keeps its lines", OCCURRENCE_LINES,
            unknownLines(veventBlocks(exportSeries(calendarId)).single { b -> b.contains("SUMMARY:Weekly review (moved)") }))

        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        editOccurrence(series.id, NOV_16, "Weekly review (evening and renamed)")
        assertEquals(NEW_OCCURRENCE_LINES, unknownLines(evening(exportSeries(calendarId))))
    }

    @Test
    fun `a newer file updating the imported changed occurrence, then an edit, keeps the newer lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val newerOccurrence = file(
            vevent(
                "UID:$SERIES_UID", "DTSTAMP:20261001T120000Z", "SEQUENCE:1", "RECURRENCE-ID:20261109T160000Z",
                "DTSTART:20261109T190000Z", "DTEND:20261109T200000Z", "SUMMARY:Weekly review (moved again)",
                *NEW_OCCURRENCE_LINES.toTypedArray(),
            ),
        )
        assertEquals(1, import(newerOccurrence, calendarId).updated)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        editOccurrence(series.id, NOV_9, "Weekly review (moved again and renamed)")

        val (_, occurrenceBlock) = seriesAndOccurrence(exportSeries(calendarId), "updated then edited")
        assertEquals(NEW_OCCURRENCE_LINES, unknownLines(occurrenceBlock))
    }

    @Test
    fun `a newer file whose copy couldn't be built keeps the stored copy`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        val store = EventIcsStore(db)
        val storedBefore = requireNotNull(store.readOwn(series.id))
        EventWriter(db, generator).applyImportedUpdate(
            series.id, series.copy(title = "Weekly review (newer)", sequence = 1), emptyList(), ics = null,
        )
        val after = db.eventsDao().getById(series.id)!!
        assertEquals("Weekly review (newer)", after.title)
        assertEquals(storedBefore, store.readOwn(series.id))
    }

    @Test
    fun `a cancelling file cancels the event and keeps its lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(singleFile, calendarId)
        val cancelling = singleFile.replace("SUMMARY:Visitor tour", "SUMMARY:Visitor tour\r\nSEQUENCE:1\r\nSTATUS:CANCELLED")
        assertEquals(1, import(cancelling, calendarId).cancelled)
        val row = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        assertEquals("CANCELLED", row.status)
        assertEquals(SINGLE_LINES, unknownLines(veventBlocks(exportEvent(row)).single()))
    }

    // ---- what a file's copy never supplies ----

    @Test
    fun `reminders the user saves win over the file's alarms`() = runBlocking {
        coordinator = newCoordinator(realScheduler())
        val calendarId = localCalendars.ensureLocalCalendarExists()
        val start = (System.currentTimeMillis() / HOUR + 30) * HOUR
        val utc = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
        fun alarm(trigger: String) = arrayOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:File", "TRIGGER:$trigger", "END:VALARM")
        val withAlarms = file(
            vevent(
                "UID:alarms@example.test", "DTSTART:${utc.format(java.time.Instant.ofEpochMilli(start))}",
                "DTEND:${utc.format(java.time.Instant.ofEpochMilli(start + HOUR))}", "SUMMARY:Alarmed",
                *alarm("-PT5M"), *alarm("-PT10M"), *alarm("-PT15M"), *alarm("-PT20M"),
            ),
        )
        import(withAlarms, calendarId)
        val row = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        suspend fun armedOffsets() = db.scheduledRemindersDao().getPendingForEvent(row.id).map { (start - it.triggerTime) / 60_000 }.sorted()
        assertEquals("the file's alarms before any edit", listOf(5L, 10L, 15L, 20L), armedOffsets())

        val saved = listOf("-PT1H", "-PT2H", "-PT3H", "-PT4H")
        coordinator.updateEvent(db.eventsDao().getById(row.id)!!.copy(reminders = saved, alarmCount = saved.size))
        assertEquals("the saved reminders", listOf(60L, 120L, 180L, 240L), armedOffsets())
    }

    @Test
    fun `a synced event with over 3 alarms still arms every alarm in its server copy`() = runBlocking {
        val scheduler = realScheduler()
        val accountId = db.accountsDao().insert(
            org.onekash.kashcal.data.db.entity.Account(
                provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV, email = "testuser1@example.test",
            )
        )
        val calendarId = db.calendarsDao().insert(
            org.onekash.kashcal.data.db.entity.Calendar(
                accountId = accountId, caldavUrl = "https://dav.example.test/cal/personal/", displayName = "P", color = 0,
            )
        )
        val start = (System.currentTimeMillis() / HOUR + 30) * HOUR
        val utc = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
        fun alarm(trigger: String) = arrayOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Server", "TRIGGER:$trigger", "END:VALARM")
        val body = file(
            vevent(
                "UID:synced-alarms@example.test", "DTSTART:${utc.format(java.time.Instant.ofEpochMilli(start))}",
                "DTEND:${utc.format(java.time.Instant.ofEpochMilli(start + HOUR))}", "SUMMARY:Synced",
                *alarm("-PT5M"), *alarm("-PT10M"), *alarm("-PT15M"), *alarm("-PT20M"), *alarm("-PT25M"), *alarm("-PT30M"),
            ),
        )
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!.single()
        val entity = org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper.toEntity(
            parsed, body, calendarId, "https://dav.example.test/cal/personal/synced-alarms.ics", "\"e1\"",
        )
        val mapped = entity.event
        assertTrue("the row holds at most 5 reminders", mapped.reminders!!.size <= 5)
        val id = db.eventsDao().insert(mapped)
        // The pull keeps the server's body on the row, as it does for every pulled series.
        org.onekash.kashcal.data.db.EventIcsStore(db).write(id, entity.ics)
        generator.generateOccurrences(mapped.copy(id = id), start - HOUR, start + 2 * HOUR)
        scheduler.scheduleUpcomingReminders()

        val armed = db.scheduledRemindersDao().getPendingForEvent(id).map { (start - it.triggerTime) / 60_000 }.sorted()
        assertEquals("every alarm in the server copy", listOf(5L, 10L, 15L, 20L, 25L, 30L), armed)
    }

    @Test
    fun `guests the user removed don't come back from the file`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        val withGuests = file(
            vevent(
                "UID:guests-removed@example.test", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z",
                "SUMMARY:Planning", "ORGANIZER:mailto:org@example.test",
                "ATTENDEE;PARTSTAT=ACCEPTED:mailto:guest@example.test",
                "ATTENDEE;PARTSTAT=ACCEPTED:mailto:other@example.test",
            ),
        )
        import(withGuests, calendarId)
        val row = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        coordinator.updateEvent(row, attendees = emptyList())

        assertEquals(0, AttendeeBackfill(db.attendeesDao(), db.eventsDao(), EventIcsStore(db)).backfillIfEmpty(row.id))
        assertTrue(db.attendeesDao().getForEventOnce(row.id).isEmpty())
        assertTrue(unfold(exportEvent(db.eventsDao().getById(row.id)!!)).lines().none { it.startsWith("ATTENDEE") })
    }

    // ---- shapes the import keeps without a copy ----

    @Test
    fun `malformed groups import as standalone events without a stored copy`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        val twoSeries = file(
            vevent("UID:dup@example.test", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:One", "X-ONE:1"),
            vevent("UID:dup@example.test", "DTSTART:20261021T160000Z", "DTEND:20261021T170000Z", "SUMMARY:Two", "X-TWO:2"),
        )
        val orphan = file(
            vevent(
                "UID:orphan@example.test", "RECURRENCE-ID:20261109T160000Z",
                "DTSTART:20261109T180000Z", "DTEND:20261109T190000Z", "SUMMARY:Orphan", "X-ORPHAN:1",
            ),
        )
        assertEquals(2, import(twoSeries, calendarId).created)
        assertEquals(1, import(orphan, calendarId).created)
        val rows = db.eventsDao().getAllMasterEventsForCalendar(calendarId)
        assertEquals(3, rows.size)
        rows.forEach { assertNull("${it.title} keeps no copy", EventIcsStore(db).read(it)) }
    }

    @Test
    fun `an imported event's export lists no guests when none are passed`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        val withGuests = file(
            vevent(
                "UID:guests@example.test", "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z",
                "SUMMARY:Planning", "ORGANIZER:mailto:org@example.test",
                "ATTENDEE;PARTSTAT=ACCEPTED:mailto:guest@example.test", "X-KEPT:yes",
            ),
        )
        import(withGuests, calendarId)
        val text = exportEvent(db.eventsDao().getAllMasterEventsForCalendar(calendarId).single())
        assertTrue("no ATTENDEE on export", unfold(text).lines().none { it.startsWith("ATTENDEE") })
        assertTrue(unfold(text).lines().contains("X-KEPT:yes"))
    }

    @Test
    fun `whole-calendar export survives a series whose stored rule can't be parsed`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(seriesFile(SERIES_UID), calendarId)
        import(singleFile, calendarId)
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single { it.uid == SERIES_UID }
        db.eventsDao().update(series.copy(rrule = "FREQ=SOMETIMES;;"))

        val text = exportCalendar(calendarId)
        val summaries = veventBlocks(text).mapNotNull { block -> block.firstOrNull { it.startsWith("SUMMARY:") } }
        assertTrue("the single event is exported: $summaries", "SUMMARY:Visitor tour" in summaries)
        assertTrue("the series is exported: $summaries", "SUMMARY:Weekly review" in summaries)
    }

    // ---- fixtures ----

    private val singleFile = file(
        vevent(
            "UID:probe-unknown-1@example.test", "DTSTAMP:20260901T120000Z",
            "DTSTART:20261020T160000Z", "DTEND:20261020T170000Z", "SUMMARY:Visitor tour",
            "LOCATION:Apple Park",
            *SINGLE_LINES.toTypedArray(),
        ),
    )

    private fun seriesFile(uid: String, sequence: Int = 0, seriesLines: List<String> = SERIES_LINES) = file(
        vevent(
            "UID:$uid", "DTSTAMP:20260901T120000Z", "SEQUENCE:$sequence", "DTSTART:20261102T160000Z",
            "DTEND:20261102T170000Z", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Weekly review",
            "RELATED-TO:$uid-parent",
            *seriesLines.toTypedArray(),
            "BEGIN:VALARM", "UID:$uid-alarm", "ACTION:DISPLAY", "DESCRIPTION:Soon", "TRIGGER:-PT10M", "END:VALARM",
        ),
        vevent(
            "UID:$uid", "DTSTAMP:20260901T120000Z", "SEQUENCE:$sequence", "RECURRENCE-ID:20261109T160000Z",
            "DTSTART:20261109T180000Z", "DTEND:20261109T190000Z", "SUMMARY:Weekly review (moved)",
            *OCCURRENCE_LINES.toTypedArray(),
        ),
    )

    private fun file(vararg vevents: List<String>) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple Inc.//macOS 15.0//EN", "CALSCALE:GREGORIAN") +
            vevents.flatMap { it } + "END:VCALENDAR"
    ).joinToString("\r\n", postfix = "\r\n")

    private fun vevent(vararg lines: String) = listOf("BEGIN:VEVENT") + lines + "END:VEVENT"

    private suspend fun caldavCalendarId(): Long {
        val accountId = db.accountsDao().insert(
            org.onekash.kashcal.data.db.entity.Account(
                provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
                email = "testuser1@example.test",
                calendarUserAddresses = listOf("mailto:testuser1@example.test"),
            )
        )
        return db.calendarsDao().insert(
            org.onekash.kashcal.data.db.entity.Calendar(
                accountId = accountId, caldavUrl = "https://dav.example.test/cal/personal/",
                displayName = "Personal", color = 0,
            )
        )
    }

    /** Edits one occurrence the way the event form does: from the series row's projection. */
    private suspend fun editOccurrence(seriesId: Long, occurrence: Long, title: String): Event {
        val existing = db.eventsDao().getExceptionsForMaster(seriesId).firstOrNull { it.originalInstanceTime == occurrence }
        return coordinator.editSingleOccurrence(seriesId, occurrence) { projected ->
            projected.copy(
                title = title,
                startTs = existing?.startTs ?: projected.startTs,
                endTs = existing?.endTs ?: projected.endTs,
                rrule = null,
                extraProperties = projected.extraProperties,
            )
        }
    }

    private suspend fun exportSeries(calendarId: Long): String {
        val series = db.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        return exportEvent(series, db.eventsDao().getExceptionsForMaster(series.id))
    }

    private suspend fun import(text: String, calendarId: Long) =
        coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(text)), calendarId)

    /** Exports [event] and [exceptions] with their stored ICS, no attendee rows given. */
    private suspend fun exportEvent(event: Event, exceptions: List<Event> = emptyList()): String =
        exportEvent(EventExport(event, exceptions, emptyMap(), EventReader(db).getIcsForExport(event, exceptions)))

    private fun exportEvent(export: EventExport): String {
        val shared = File(context.cacheDir, "shared").apply { deleteRecursively() }
        // The file is written before the FileProvider lookup, which has no provider here.
        IcsExporter().exportEvent(context, export)
        return shared.listFiles()!!.single().readText()
    }

    private suspend fun exportCalendar(calendarId: Long): String {
        val shared = File(context.cacheDir, "shared").apply { deleteRecursively() }
        IcsExporter().exportCalendar(context, coordinator.getCalendarEventsForExport(calendarId), "Local")
        return shared.listFiles()!!.single().readText()
    }

    private fun unfold(text: String) = text.replace(Regex("\r?\n[ \t]"), "").replace("\r\n", "\n")

    /** Each VEVENT's unfolded lines, sub-components included. */
    private fun veventBlocks(text: String): List<List<String>> =
        Regex("BEGIN:VEVENT\n(.*?)\nEND:VEVENT", RegexOption.DOT_MATCHES_ALL)
            .findAll(unfold(text)).map { it.groupValues[1].lines() }.toList()

    private fun seriesAndOccurrence(text: String, path: String): Pair<List<String>, List<String>> {
        val blocks = veventBlocks(text)
        assertEquals("$path export holds the series and its changed occurrence", 2, blocks.size)
        return blocks.single { b -> b.none { it.startsWith("RECURRENCE-ID") } } to
            blocks.single { b -> b.any { it.startsWith("RECURRENCE-ID") } }
    }

    private fun unknownLines(block: List<String>): List<String> =
        block.filter { line -> UNKNOWN_NAMES.any { line.startsWith("$it:") || line.startsWith("$it;") } }

    /** Every unfolded line is a property line; a broken escape leaves a bare line behind. */
    private fun assertNoBrokenLines(path: String, text: String) {
        val bare = unfold(text).lines().filter { it.isNotBlank() && !Regex("^[A-Za-z0-9-]+[;:]").containsMatchIn(it) }
        assertTrue("$path export has bare lines: $bare", bare.isEmpty())
    }

    private companion object {
        const val SERIES_UID = "series-unknown-1@example.test"

        val SINGLE_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"10600 N Tantau Ave\\nCupertino, CA 95014\";X-APPLE-RADIUS=70;X-TITLE=\"Apple Park: Visitor Center\":geo:37.332,-122.005",
            "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY:mail\\,msg-1234\\;part\\=2",
            "X-MICROSOFT-LOCATIONS:[{\"DisplayName\":\"Room 1\\, Floor 2\"}]",
            "COMMENT:line one\\nline two\\, with comma",
            "RESOURCES:Projector",
            "RESOURCES:Whiteboard",
        )
        val SERIES_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Room, 1\":geo:1.0,2.0",
            "COMMENT:every week\\nbring notes",
        )
        val NEWER_SERIES_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Room, 2\":geo:5.0,6.0",
            "COMMENT:every week\\nbring the new notes",
        )
        val NEW_OCCURRENCE_LINES = listOf(
            "X-MICROSOFT-LOCATIONS:[{\"DisplayName\":\"Hall\\, East\"}]",
            "COMMENT:evening\\nthis week",
        )
        val OCCURRENCE_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:3.0,4.0",
            "COMMENT:moved\\nfor this week only",
        )
        const val HOUR = 3_600_000L
        val NOV_9 = java.time.Instant.parse("2026-11-09T16:00:00Z").toEpochMilli()
        val NOV_2_PLUS_DAY = java.time.Instant.parse("2026-11-03T16:00:00Z").toEpochMilli()
        val NOV_16 = java.time.Instant.parse("2026-11-16T16:00:00Z").toEpochMilli()
        val UNKNOWN_NAMES = (SINGLE_LINES + SERIES_LINES + OCCURRENCE_LINES + NEWER_SERIES_LINES + NEW_OCCURRENCE_LINES)
            .map { it.split(':', ';').first() }.toSet()
    }
}
