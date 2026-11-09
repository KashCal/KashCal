package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.icaldav.parser.ICalParser
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The push of an invitation imported from a file (#364), through the real writer, push and client
 * against a server that stores what it gets and refuses a stale If-Match.
 *
 * Measured 2026-10-09 on 10 servers: only Cyrus sends the iTIP REPLY when a create already
 * carries the answer, so the create goes up at NEEDS-ACTION and the answer as the next PUT. A
 * server that already holds the UID refuses the create; for an imported event that ends the
 * operation instead of retrying. Those create and answer rules apply only to imported rows; the
 * same input on an ordinary row takes today's path. A newer invitation imported over a pulled
 * row goes up as one ordinary UPDATE of the series, with its changed occurrences in the same PUT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyImportedInviteTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private lateinit var generator: OccurrenceGenerator
    private lateinit var account: Account
    private var calendarId = 0L

    private val self = "attendee@example.com"
    private val uid = "test-invitation-123@example.com"

    // ---- the server ----

    private val resources = mutableMapOf<String, String>()
    private val etags = mutableMapOf<String, Int>()
    private var createRefusal: Int? = null
    private var stampOnAnswer: String? = null
    private val puts: MutableList<Triple<String, String, String?>> = Collections.synchronizedList(mutableListOf())
    private val gets: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            return when (request.method) {
                "PUT" -> {
                    val sent = request.body.readUtf8()
                    val ifNoneMatch = request.getHeader("If-None-Match")
                    puts += Triple(path, sent, request.getHeader("If-Match") ?: ifNoneMatch?.let { "none-match" })
                    when {
                        ifNoneMatch == "*" && createRefusal != null -> MockResponse().setResponseCode(createRefusal!!)
                        ifNoneMatch == "*" && path in resources -> MockResponse().setResponseCode(412)
                        ifNoneMatch == "*" -> {
                            resources[path] = sent; etags[path] = 1
                            MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\"")
                        }
                        request.getHeader("If-Match") != "\"e${etags[path]}\"" -> MockResponse().setResponseCode(412)
                        else -> {
                            // A Sabre-like server stamps the receipt on the organizer line and so
                            // returns no ETag for the rewritten resource.
                            val stored = stampOnAnswer?.let {
                                sent.replace("ORGANIZER;", "ORGANIZER;SCHEDULE-STATUS=$it;")
                                    .replace("ORGANIZER:", "ORGANIZER;SCHEDULE-STATUS=$it:")
                            } ?: sent
                            resources[path] = stored; etags[path] = etags[path]!! + 1
                            MockResponse().setResponseCode(204).also {
                                if (stampOnAnswer == null) it.setHeader("ETag", "\"e${etags[path]}\"")
                            }
                        }
                    }
                }
                "GET" -> {
                    gets += path
                    resources[path]?.let { MockResponse().setResponseCode(200).setHeader("ETag", "\"e${etags[path]}\"").setBody(it) }
                        ?: MockResponse().setResponseCode(404)
                }
                "PROPFIND" -> resources[path]?.let {
                    MockResponse().setResponseCode(207).setBody(
                        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href>""" +
                            """<d:propstat><d:prop><d:getetag>"e${etags[path]}"</d:getetag></d:prop>""" +
                            """<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                    )
                } ?: MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    @Before
    fun setup() = runTest {
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base)
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .addCallback(PendingOperationTriggers.callback()).allowMainThreadQueries().build()
        generator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, generator)
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = self, calendarUserAddresses = listOf("mailto:$self"))
        )
        account = database.accountsDao().getById(accountId)!!
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = server.url("/cal/").toString(), displayName = "Cal", color = -1)
        )
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(accountId) } returns account
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** Creates the #364 invite as an import would: its UID, organizer and attendee rows kept. */
    private suspend fun createInvite(imported: Boolean = true): Event {
        val content = requireNotNull(javaClass.classLoader?.getResource("ics/itip/issue_364_request.ics")).readText()
        val component = IcsParserService.parseForImport(content)!!.groups.single().masters.single()
        val event = component.event.copy(
            calendarId = calendarId,
            importSource = if (imported) Event.IMPORT_SOURCE_ICS_FILE else null,
        )
        val rows = component.attendees.mapIndexed { i, a ->
            Attendee(eventId = 0, address = a.address, displayName = a.displayName, role = a.role,
                partstat = a.partstat, cutype = a.cutype, rsvp = a.rsvp, sortOrder = i)
        }
        return eventWriter.createEvent(event, isLocal = false, attendees = rows)
    }

    private fun unfold(s: String) = s.replace(Regex("""\r?\n[ \t]"""), "")

    // ---- a newer invitation imported over a pulled copy ----

    private val coordinator by lazy {
        val accounts = org.onekash.kashcal.data.repository.AccountRepositoryImpl(
            accountsDao = database.accountsDao(), addressBookDao = database.addressBookDao(),
            calendarsDao = database.calendarsDao(), eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            credentialManager = org.onekash.kashcal.data.credential.FakeCredentialManager(),
            reminderScheduler = mockk(relaxed = true), workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository(),
        )
        val dataStore = mockk<KashCalDataStore> {
            every { defaultReminderMinutes } returns flowOf(15)
            every { defaultAllDayReminder } returns flowOf(1440)
        }
        EventCoordinator(
            eventWriter = eventWriter, eventReader = EventReader(database), occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(database), icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(), contactAnniversaryRepository = mockk(), accountRepository = accounts,
            syncScheduler = mockk(relaxed = true), reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true), inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true), dataStore = dataStore,
        )
    }

    private fun fixtureText(name: String) =
        unfold(requireNotNull(javaClass.classLoader?.getResource("ics/itip/$name")).readText())
            .replace("attendee@example.test", self)

    /** Puts [body] on the server and stores the row a pull of it would. */
    private suspend fun seedPulled(body: String): Event {
        val stored = body.replace(Regex("METHOD:\\w+\r?\n"), "")
        val parsed = ICalParser().parseAllEvents(stored).getOrNull()!!
        val path = "/cal/${parsed.first().uid}.ics"
        resources[path] = stored; etags[path] = 1
        val master = parsed.first { it.recurrenceId == null }
        // The client stores etags unquoted and quotes them on the wire.
        val mapped = ICalEventMapper.toEntity(master, stored, calendarId, server.url(path).toString(), "e1")
        val id = database.eventsDao().insert(mapped.event.copy(syncStatus = SyncStatus.SYNCED))
        database.attendeesDao().replaceForEvent(id, mapped.attendees.map { it.copy(eventId = id) })
        generator.regenerateOccurrences(database.eventsDao().getById(id)!!)
        return database.eventsDao().getById(id)!!
    }

    private fun series(sequence: Int, vararg extra: String) = (listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client//EN", "METHOD:REQUEST",
        "BEGIN:VEVENT", "UID:weekly@example.test", "DTSTAMP:2026110${sequence + 1}T100000Z",
        "DTSTART:20261201T150000Z", "DTEND:20261201T160000Z", "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Weekly v$sequence", "SEQUENCE:$sequence", "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$self", "END:VEVENT",
    ) + extra.toList() + listOf("END:VCALENDAR", "")).joinToString("\r\n")

    private fun occurrence(sequence: Int, status: String) = listOf(
        "BEGIN:VEVENT", "UID:weekly@example.test", "DTSTAMP:2026110${sequence + 1}T100000Z",
        "RECURRENCE-ID:20261202T150000Z", "DTSTART:20261202T170000Z", "DTEND:20261202T180000Z",
        "SUMMARY:Moved", "SEQUENCE:$sequence", "STATUS:$status", "ORGANIZER;CN=Boss:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$self", "END:VEVENT",
    ).joinToString("\r\n")

    @Test
    fun `a newer invitation imported over a pulled copy goes up as one full PUT`() = runTest {
        val original = seedPulled(fixtureText("reschedule_original_export.ics"))
        database.eventsDao().update(original.copy(categories = listOf("Work")))
        val organizerLine = lines(fixtureText("reschedule_original_export.ics"), "ORGANIZER").single()

        val result = coordinator.importIcsFile(
            IcsParserService.parseForImport(
                fixtureText("reschedule_request.ics").replace("SUMMARY:Project review call", "SUMMARY:Project review call (moved)")
            )!!,
            calendarId,
        )
        pushStrategy.pushAll(client)

        assertEquals("$result", 1, result.updated)
        val (_, body, ifMatch) = puts.single()
        assertEquals("\"e1\"", ifMatch)
        val unfolded = unfold(body)
        assertTrue(unfolded, unfolded.lines().any { it == "DTSTART;TZID=America/Chicago:20261109T130000" })
        assertTrue(unfolded, unfolded.lines().any { it == "DTEND;TZID=America/Chicago:20261109T133000" })
        assertEquals(listOf("SUMMARY:Project review call (moved)"), lines(body, "SUMMARY"))
        assertEquals(listOf("SEQUENCE:1"), lines(body, "SEQUENCE"))
        assertEquals("the stored ORGANIZER line is kept", listOf(organizerLine), lines(body, "ORGANIZER"))
        val attendees = lines(body, "ATTENDEE")
        assertEquals(attendees.toString(), 4, attendees.size)
        assertTrue(attendees.single { it.endsWith(":mailto:$self") }.contains("PARTSTAT=NEEDS-ACTION"))
        assertEquals(listOf("CATEGORIES:Work"), lines(body, "CATEGORIES"))
        assertTrue("no METHOD in a stored resource (RFC 4791 4.1)", lines(body, "METHOD").isEmpty())
        assertTrue(database.pendingOperationsDao().getAllOnce().isEmpty())
    }

    @Test
    fun `a series update with a cancelled occurrence goes up as one PUT`() = runTest {
        seedPulled(series(0))

        val result = coordinator.importIcsFile(IcsParserService.parseForImport(series(1, occurrence(1, "CANCELLED")))!!, calendarId)
        pushStrategy.pushAll(client)

        assertEquals("$result", 1, result.updated)
        assertEquals("$result", 1, result.cancelled)
        assertEquals("one PUT, no stale-etag 412: $puts", 1, puts.size)
        val body = unfold(puts.single().second)
        // A cancel marks the occurrence's status only; the series carries the new title.
        val vevents = body.split("BEGIN:VEVENT").drop(1)
        assertEquals(2, vevents.size)
        val cancelled = vevents.single { it.contains("RECURRENCE-ID") }
        assertTrue(cancelled, cancelled.lines().any { it.startsWith("RECURRENCE-ID") && it.endsWith("20261202T150000Z") })
        assertTrue(cancelled, cancelled.lines().contains("STATUS:CANCELLED"))
        assertFalse("the file's title for the occurrence isn't written", cancelled.contains("SUMMARY:Moved"))
        assertTrue(vevents.single { !it.contains("RECURRENCE-ID") }.lines().contains("SUMMARY:Weekly v1"))
        assertTrue(database.pendingOperationsDao().getAllOnce().isEmpty())
    }

    @Test
    fun `a changed occurrence of a pulled series rides in the series PUT`() = runTest {
        seedPulled(series(0))

        // The file holds only the changed occurrence, as an organizer's update of one date does.
        val onlyOccurrence = series(0).let { it.substring(0, it.indexOf("BEGIN:VEVENT")) } +
            occurrence(1, "CONFIRMED") + "\r\nEND:VCALENDAR\r\n"
        coordinator.importIcsFile(IcsParserService.parseForImport(onlyOccurrence)!!, calendarId)
        pushStrategy.pushAll(client)

        val body = unfold(puts.single().second)
        assertEquals(listOf("SUMMARY:Weekly v0", "SUMMARY:Moved"), lines(body, "SUMMARY"))
        assertTrue(body, lines(body, "DTSTART").any { it.endsWith("20261202T170000Z") })
    }

    private fun lines(body: String, prefix: String) = unfold(body).lines().filter { it.startsWith(prefix) }

    @Test
    fun `an answer before the first upload goes as a modify after a NEEDS-ACTION create`() = runTest {
        val event = createInvite()
        assertTrue(eventWriter.replyRsvp(event.id, account, "ACCEPTED"))

        pushStrategy.pushAll(client)

        assertEquals("a create, then the answer: $puts", 2, puts.size)
        val (createPath, createBody, createGuard) = puts[0]
        val (answerPath, answerBody, _) = puts[1]
        assertEquals("none-match", createGuard)
        assertEquals(createPath, answerPath)
        assertTrue(createBody.contains("UID:$uid"))
        assertTrue(lines(createBody, "ATTENDEE").single().contains("PARTSTAT=NEEDS-ACTION"))
        assertTrue(lines(answerBody, "ATTENDEE").single().contains("PARTSTAT=ACCEPTED"))
        assertEquals(
            "only the user's PARTSTAT changes",
            unfold(createBody).replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED").lines().filterNot { it.startsWith("DTSTAMP") },
            unfold(answerBody).lines().filterNot { it.startsWith("DTSTAMP") },
        )
        assertTrue(lines(answerBody, "ORGANIZER").single().endsWith(":mailto:organizer@example.com"))
        assertTrue(lines(answerBody, "METHOD").isEmpty())
        assertEquals("ACCEPTED", database.attendeesDao().getForEventOnce(event.id).single().partstat)
        assertTrue(database.pendingOperationsDao().getForEvent(event.id).isEmpty())
    }

    @Test
    fun `the reply receipt is read back after the answer`() = runTest {
        stampOnAnswer = "1.1"
        val event = createInvite()
        eventWriter.replyRsvp(event.id, account, "ACCEPTED")

        pushStrategy.pushAll(client)

        assertEquals("1.1", database.eventsDao().getById(event.id)!!.organizerScheduleStatus)
    }

    @Test
    fun `a new answer clears the earlier receipt until it is read back`() = runTest {
        val event = createInvite()
        database.eventsDao().updateOrganizerScheduleStatus(event.id, "1.1")

        eventWriter.replyRsvp(event.id, account, "DECLINED")

        assertNull(database.eventsDao().getById(event.id)!!.organizerScheduleStatus)
    }

    @Test
    fun `an ordinary invite's answer issues no receipt fetch and keeps its receipt`() = runTest {
        val event = createInvite(imported = false)
        pushStrategy.pushAll(client)
        database.eventsDao().updateOrganizerScheduleStatus(event.id, "1.1")
        gets.clear()

        eventWriter.replyRsvp(event.id, account, "ACCEPTED")
        pushStrategy.pushAll(client)

        assertTrue("no read-back GET for an ordinary row: $gets", gets.isEmpty())
        assertEquals("1.1", database.eventsDao().getById(event.id)!!.organizerScheduleStatus)
    }

    @Test
    fun `a refused create of an imported event fails at once with its error recorded`() = runTest {
        for (code in listOf(400, 403, 409, 412)) {
            puts.clear(); createRefusal = code
            val event = createInvite()

            val result = pushStrategy.pushAll(client) as PushResult.Success

            assertEquals("HTTP $code: one attempt, no retry in the sync", 1, puts.size)
            val op = database.pendingOperationsDao().getForEvent(event.id).single()
            assertEquals("HTTP $code", PendingOperation.STATUS_FAILED, op.status)
            assertTrue("HTTP $code", database.eventsDao().getById(event.id)!!.lastSyncError!!.contains("$code"))
            assertTrue(result.pushErrors.any { it.code == code })
            assertTrue("the local copy stays", database.eventsDao().getById(event.id) != null)
            database.eventsDao().deleteById(event.id)
            database.pendingOperationsDao().deleteForEvent(event.id)
        }
    }

    @Test
    fun `an answer on an imported event whose create failed fails at once`() = runTest {
        createRefusal = 400
        val event = createInvite()
        pushStrategy.pushAll(client)
        createRefusal = null
        eventWriter.replyRsvp(event.id, account, "ACCEPTED")

        pushStrategy.pushAll(client)

        val answer = database.pendingOperationsDao().getForEvent(event.id).single { it.partstatOnly }
        assertEquals(PendingOperation.STATUS_FAILED, answer.status)
    }

    @Test
    fun `an ordinary event's refused create keeps today's handling`() = runTest {
        createRefusal = 412
        val event = createInvite(imported = false)

        pushStrategy.pushAll(client)

        val op = database.pendingOperationsDao().getForEvent(event.id).single()
        assertFalse("today a create 412 is a conflict retried later, not failed", op.status == PendingOperation.STATUS_FAILED)
        assertNull(database.eventsDao().getById(event.id)!!.lastSyncError?.takeIf { it.contains("refused the imported") })
    }
}
