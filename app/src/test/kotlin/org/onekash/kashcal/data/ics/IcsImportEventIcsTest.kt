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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.EventIcsStore
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests where an ICS file import keeps the file's text ([EventIcsStore]): once per UID group, on
 * the series row; a changed occurrence holds its own only when a newer file changed only it. And
 * that an export or an automatic backup of the imported events patches that text, so properties
 * the app doesn't model (an X- property, an inline attachment) reach the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsImportEventIcsTest {

    private lateinit var context: Context
    private lateinit var db: KashCalDatabase
    private lateinit var store: EventIcsStore
    private lateinit var coordinator: EventCoordinator
    private lateinit var localCalendars: LocalCalendarInitializer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        store = EventIcsStore(db)
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
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
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator, store),
            eventReader = EventReader(db, store),
            occurrenceGenerator = generator,
            localCalendarInitializer = localCalendars,
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

    @After
    fun tearDown() = db.close()

    private fun vevent(recurrenceId: String?, summary: String, sequence: Int = 0, extra: String = "") =
        "BEGIN:VEVENT\r\nUID:uid-import@example.test\r\nDTSTAMP:20260501T120000Z\r\nSEQUENCE:$sequence\r\n" +
            (recurrenceId?.let { "RECURRENCE-ID:$it\r\nDTSTART:20350602T140000Z\r\nDTEND:20350602T150000Z\r\n" }
                ?: "DTSTART:20350601T100000Z\r\nDTEND:20350601T110000Z\r\nRRULE:FREQ=DAILY;COUNT=10\r\n") +
            "SUMMARY:$summary\r\n${extra}X-IMPORTED-ONLY:kept\r\nEND:VEVENT\r\n"

    private fun file(vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" + vevents.joinToString("") + "END:VCALENDAR\r\n"

    private suspend fun import(text: String, calendarId: Long) =
        coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(text)), calendarId)

    private suspend fun rows(calendarId: Long) = db.eventsDao().getByUid("uid-import@example.test")
        .filter { it.calendarId == calendarId }

    @Test
    fun `a series with changed occurrences keeps the file's text once, on the series`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(file(vevent(null, "S"), vevent("20350602T100000Z", "A"), vevent("20350603T100000Z", "B")), calendarId)

        val all = rows(calendarId)
        val series = all.single { it.originalEventId == null }
        assertNotNull(store.readOwn(series.id))
        all.filter { it.originalEventId != null }.forEach {
            assertNull("occurrence ${it.id} keeps no copy", store.readOwn(it.id))
            assertEquals(store.readOwn(series.id), store.read(it))
        }
    }

    @Test
    fun `a newer file that changes only one occurrence gives it its own copy`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(file(vevent(null, "S"), vevent("20350602T100000Z", "A")), calendarId)
        val seriesText = store.readOwn(rows(calendarId).single { it.originalEventId == null }.id)

        val newer = file(vevent("20350602T100000Z", "A renamed", sequence = 1))
        import(newer, calendarId)

        val all = rows(calendarId)
        val series = all.single { it.originalEventId == null }
        val occurrence = all.single { it.originalEventId != null }
        assertEquals("the series keeps the first file's text", seriesText, store.readOwn(series.id))
        assertNotNull("the changed occurrence holds the newer file's text", store.readOwn(occurrence.id))
        assertTrue(store.readOwn(occurrence.id)!!.contains("A renamed"))
    }

    @Test
    fun `export and backup of an imported event keep an X- property and an inline attachment`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        val attach = "ATTACH;ENCODING=BASE64;VALUE=BINARY;FMTTYPE=text/plain:aGVsbG8gd29ybGQ=\r\n"
        import(file(vevent(null, "S", extra = attach)), calendarId)
        val series = rows(calendarId).single()

        val export = requireNotNull(coordinator.getEventForExport(series.id))
        val calendarText = IcsExporter().buildCalendarText(coordinator.getCalendarEventsForExport(calendarId), "Local")
        val eventText = IcsExporter().buildCalendarText(listOf(export), "Local")

        for ((path, text) in listOf("event" to eventText, "calendar backup" to calendarText)) {
            val unfolded = text.replace(Regex("\r?\n[ \t]"), "")
            assertTrue("$path keeps the X- property", unfolded.contains("X-IMPORTED-ONLY:kept"))
            assertTrue("$path keeps the attachment", unfolded.contains("aGVsbG8gd29ybGQ="))
        }
    }

    @Test
    fun `an imported Exchange file keeps TZID=UTC and display-name times`() = withDeviceTimeZone("Asia/Tokyo") {
        runBlocking {
            val calendarId = localCalendars.ensureLocalCalendarExists()
            val beijing = "(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi"
            fun event(uid: String, tzid: String) =
                "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261118T120000Z\r\nDTSTART;TZID=$tzid:20351210T100000\r\n" +
                    "DTEND;TZID=$tzid:20351210T110000\r\nSUMMARY:$uid\r\nEND:VEVENT\r\n"
            import(
                file(
                    "BEGIN:VTIMEZONE\r\nTZID:UTC\r\nBEGIN:STANDARD\r\nDTSTART:16010101T000000\r\n" +
                        "TZOFFSETFROM:+0000\r\nTZOFFSETTO:+0000\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n",
                    event("utc@example.test", "UTC"),
                    event("beijing@example.test", "\"$beijing\""),
                ),
                calendarId
            )

            assertEquals(
                java.time.Instant.parse("2035-12-10T10:00:00Z").toEpochMilli(),
                db.eventsDao().getByUid("utc@example.test").single().startTs
            )
            assertEquals(
                java.time.Instant.parse("2035-12-10T02:00:00Z").toEpochMilli(),
                db.eventsDao().getByUid("beijing@example.test").single().startTs
            )
        }
    }
}
