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
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that a changed occurrence of an imported series keeps what is its own when a newer file
 * changes only the series, or when the user re-edits it: its unknown lines on export and its
 * import id. Uses only the import, edit and export entry points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsImportOccurrenceLinesTest {

    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private lateinit var localCalendars: LocalCalendarInitializer

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
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
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
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
    fun `a newer file that changes only the series keeps a changed occurrence's own lines`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        // Longer than the row's own map keeps (ICalEventMapper.MAX_EXTRA_PROPERTY_VALUE_CHARS),
        // so only the stored file text holds it.
        val long = "x".repeat(30_000)
        val occurrence = vevent("20350602T100000Z", "A", extra = "X-OCCURRENCE-ONLY:$long\r\n")
        import(file(vevent(null, "S"), occurrence), calendarId)

        import(file(vevent(null, "S renamed", sequence = 1)), calendarId)

        val series = rows(calendarId).single { it.originalEventId == null }
        val text = IcsExporter().buildCalendarText(listOf(requireNotNull(coordinator.getEventForExport(series.id))), "Local")
        val changed = text.replace(Regex("\r?\n[ \t]"), "").split("BEGIN:VEVENT").single { it.contains("RECURRENCE-ID") }
        assertTrue("the changed occurrence keeps its own line", changed.contains("X-OCCURRENCE-ONLY:$long"))
        assertTrue("the series takes the newer file", text.contains("SUMMARY:S renamed"))
    }

    @Test
    fun `re-editing an imported changed occurrence keeps its own import id`() = runBlocking {
        val calendarId = localCalendars.ensureLocalCalendarExists()
        import(file(vevent(null, "S"), vevent("20350602T100000Z", "A")), calendarId)
        val series = rows(calendarId).single { it.originalEventId == null }
        val occurrence = rows(calendarId).single { it.originalEventId != null }

        coordinator.editSingleOccurrence(series.id, occurrence.originalInstanceTime!!) { it.copy(title = "A edited") }

        assertEquals(occurrence.importId, rows(calendarId).single { it.originalEventId != null }.importId)
    }
}
