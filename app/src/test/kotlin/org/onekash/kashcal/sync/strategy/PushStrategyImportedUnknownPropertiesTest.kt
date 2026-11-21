package org.onekash.kashcal.sync.strategy

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * A file imported into a CalDAV calendar uploads the properties the app doesn't model exactly as
 * the file wrote them, through the real import, writer, push and client against a server that
 * stores what it gets. The same holds for the next upload after an edit and for an event imported
 * into an offline calendar and then moved to the CalDAV calendar. A guest removed before the first
 * upload is not invited from the file's copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyImportedUnknownPropertiesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var database: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private lateinit var pushStrategy: PushStrategy
    private var calendarId = 0L

    private val self = "testuser1@example.test"
    private val resources = mutableMapOf<String, String>()
    private val etags = mutableMapOf<String, Int>()
    private val puts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            return when (request.method) {
                "PUT" -> {
                    val sent = request.body.readUtf8()
                    puts += path to sent
                    resources[path] = sent
                    etags[path] = (etags[path] ?: 0) + 1
                    MockResponse().setResponseCode(if (etags[path] == 1) 201 else 204).setHeader("ETag", "\"e${etags[path]}\"")
                }
                "GET" -> resources[path]?.let {
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"e${etags[path]}\"").setBody(it)
                } ?: MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    @Before
    fun setup() {
        server = MockWebServer().apply { dispatcher = this@PushStrategyImportedUnknownPropertiesTest.dispatcher; start() }
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base)
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback()).allowMainThreadQueries().build()
        val generator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        val accountId = kotlinx.coroutines.runBlocking {
            database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = self, calendarUserAddresses = listOf("mailto:$self"))
            )
        }
        calendarId = kotlinx.coroutines.runBlocking {
            database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = server.url("/cal/").toString(), displayName = "Cal", color = -1)
            )
        }
        val accountRepository: AccountRepository = org.onekash.kashcal.data.repository.AccountRepositoryImpl(
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
        coordinator = EventCoordinator(
            eventWriter = EventWriter(database, generator),
            eventReader = EventReader(database),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(database),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accountRepository,
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = dataStore,
        )
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao(),
            icsSource = EventIcsStore(database),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `the first upload and the upload after an edit carry the file's lines as written`() = runTest {
        coordinator.importIcsFile(IcsParserService.parseForImport(singleFile)!!, calendarId)
        pushStrategy.pushAll(client)
        assertEquals("one create: ${puts.map { it.first }}", 1, puts.size)
        assertEquals(SINGLE_LINES, unknownLines(veventBlocks(puts.last().second).single()))
        assertTrue("the body parses", ICalParser().parseAllEvents(puts.last().second).getOrNull() != null)

        val row = database.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        coordinator.updateEvent(row.copy(title = "Visitor tour (edited)"))
        pushStrategy.pushAll(client)
        assertEquals(2, puts.size)
        val edited = veventBlocks(puts.last().second).single()
        assertTrue(edited.contains("SUMMARY:Visitor tour (edited)"))
        assertEquals(SINGLE_LINES, unknownLines(edited))
    }

    @Test
    fun `a series uploads each VEVENT with only its own lines`() = runTest {
        coordinator.importIcsFile(IcsParserService.parseForImport(seriesFile)!!, calendarId)
        pushStrategy.pushAll(client)
        val blocks = veventBlocks(puts.single().second)
        assertEquals(2, blocks.size)
        assertEquals(SERIES_LINES, unknownLines(blocks.single { b -> b.none { it.startsWith("RECURRENCE-ID") } }))
        assertEquals(OCCURRENCE_LINES, unknownLines(blocks.single { b -> b.any { it.startsWith("RECURRENCE-ID") } }))
    }

    @Test
    fun `an event imported offline and moved to the CalDAV calendar uploads its lines`() = runTest {
        val offline = LocalCalendarInitializer(database).ensureLocalCalendarExists()
        coordinator.importIcsFile(IcsParserService.parseForImport(singleFile)!!, offline)
        val row = database.eventsDao().getAllMasterEventsForCalendar(offline).single()
        coordinator.moveEventToCalendar(row.id, calendarId)

        pushStrategy.pushAll(client)
        assertEquals(SINGLE_LINES, unknownLines(veventBlocks(puts.single().second).single()))
    }

    @Test
    fun `guests removed before the first upload aren't invited from the file`() = runTest {
        val organized = file(
            "UID:organized@example.test", "DTSTAMP:20260901T120000Z", "DTSTART:20261120T160000Z",
            "DTEND:20261120T170000Z", "SUMMARY:Planning", "ORGANIZER:mailto:$self",
            "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:guest@example.test",
            "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:other@example.test", "X-KEPT:yes",
        )
        coordinator.importIcsFile(IcsParserService.parseForImport(organized)!!, calendarId)
        val row = database.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        coordinator.updateEvent(row, attendees = emptyList())

        pushStrategy.pushAll(client)
        val sent = unfold(puts.single().second).lines()
        assertTrue("no ATTENDEE: $sent", sent.none { it.startsWith("ATTENDEE") })
        assertTrue(sent.contains("X-KEPT:yes"))
    }

    // ---- fixtures ----

    private val singleFile = file(
        "UID:push-unknown-1@example.test", "DTSTAMP:20260901T120000Z", "DTSTART:20261120T160000Z",
        "DTEND:20261120T170000Z", "SUMMARY:Visitor tour", *SINGLE_LINES.toTypedArray(),
    )

    private val seriesFile = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Test//EN") +
            vevent(
                "UID:push-series@example.test", "DTSTAMP:20260901T120000Z", "DTSTART:20261102T160000Z",
                "DTEND:20261102T170000Z", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Weekly review",
                *SERIES_LINES.toTypedArray(),
            ) +
            vevent(
                "UID:push-series@example.test", "DTSTAMP:20260901T120000Z", "RECURRENCE-ID:20261109T160000Z",
                "DTSTART:20261109T180000Z", "DTEND:20261109T190000Z", "SUMMARY:Weekly review (moved)",
                *OCCURRENCE_LINES.toTypedArray(),
            ) + "END:VCALENDAR"
        ).joinToString("\r\n", postfix = "\r\n")

    private fun file(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Test//EN") + vevent(*lines) + "END:VCALENDAR")
            .joinToString("\r\n", postfix = "\r\n")

    private fun vevent(vararg lines: String) = listOf("BEGIN:VEVENT") + lines + "END:VEVENT"

    private fun unfold(text: String) = text.replace(Regex("\r?\n[ \t]"), "").replace("\r\n", "\n")

    private fun veventBlocks(text: String): List<List<String>> =
        Regex("BEGIN:VEVENT\n(.*?)\nEND:VEVENT", RegexOption.DOT_MATCHES_ALL)
            .findAll(unfold(text)).map { it.groupValues[1].lines() }.toList()

    private fun unknownLines(block: List<String>) =
        block.filter { line -> UNKNOWN_NAMES.any { line.startsWith("$it:") || line.startsWith("$it;") } }

    private companion object {
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
        val OCCURRENCE_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:3.0,4.0",
            "COMMENT:moved\\nfor this week only",
        )
        val UNKNOWN_NAMES = (SINGLE_LINES + SERIES_LINES + OCCURRENCE_LINES).map { it.split(':', ';').first() }.toSet()
    }
}
