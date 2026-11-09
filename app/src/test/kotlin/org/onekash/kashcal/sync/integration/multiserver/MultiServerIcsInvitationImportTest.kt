package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.encodedPathSegment
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The #364 invitation file imported into a CalDAV calendar on every server, then answered,
 * re-imported and cancelled through the app, as a user does from a mail attachment.
 *
 * The file is the one from #364 with a new UID per run, the attendee set to the account's own
 * calendar-user address and the organizer at an `@example.test` address, so no mail can be
 * delivered. Each step goes through the import parse, [EventCoordinator.importIcsFile] or
 * [EventCoordinator.replyRsvp], and [PushStrategy]; the resource is then read back by its own
 * href. The server copy must keep the file's UID and ORGANIZER with the user at NEEDS-ACTION and
 * no METHOD; an answer must change only the user's PARTSTAT, also on a second answer after the
 * server stamped the copy (Cyrus); an identical re-import must queue nothing; the CANCEL must
 * leave the copy cancelled, or, where the server refuses an attendee's cancel, its own copy with a
 * warning. A
 * rescheduled version of the file (higher SEQUENCE, new time and title) must reach the server
 * copy, with the account among the guests and without it, and a second import of it must read up
 * to date.
 *
 * Writes only to a calendar the sharing check (UnsharedCalendarPicker) accepts, except that the
 * reschedule tests fall back to any writable calendar (the accounts hold only test data). Nothing
 * is pulled: only this run's resource enters the database, every push first checks that only this
 * run's operations are queued, and the only resource updated or deleted is the URL this run's own
 * create returned, which carries its full UID. Skips a server without credentials, a calendar-user
 * address or an accepted calendar (Zoho reports no sharing properties, so only the reschedule tests
 * reach it); Fastmail isn't covered.
 *
 * A create the server refuses because it already holds the UID runs on the local servers only.
 * The UID cases use plain files (no METHOD, organizer or guests), so they run on every server:
 * a UID another calendar of the account holds, a delete then re-import of the same UID, and
 * UIDs with characters a resource name has to encode. Where servers differ (a re-create while
 * the server's trash holds the UID, a slash or plus sign in the name) the outcome is printed
 * rather than asserted.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerIcsInvitationImportTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerIcsInvitationImportTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val ORGANIZER = "ics364-organizer@example.test"
        private const val ISSUE_UID = "test-invitation-123@example.com"
    }

    private lateinit var client: CalDavClient
    private lateinit var creds: ServerCredentials
    private lateinit var db: KashCalDatabase
    private val uid = "kc-ics364-${UUID.randomUUID()}"
    private val cleanup = mutableListOf<String>()

    /** Calendars this run wrote to, searched at teardown for anything filed under another name. */
    private val usedCalendars = mutableSetOf<String>()

    /** UIDs this run created: [uid], the shapes built from it, and fresh UIDs its imports made. */
    private val ownUids = mutableSetOf(uid)

    @Before
    fun setUp() {
        assumeTrue("Fastmail is not covered by this suite", config.name != CalDavServerConfig.FASTMAIL.name)
        val pair = CalDavTestServerLoader.createClient(config)
        assumeTrue("${config.name} credentials not available", pair != null)
        client = pair!!.first
        creds = pair.second
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds.davEndpoint))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = runBlocking {
        // Only URLs this run's own create returned.
        for (url in cleanup) {
            runCatching { client.deleteEvent(url, client.fetchEtag(url).getOrNull()) }
            runCatching { client.deleteEvent(url, null) }
            // The path carries this run's UID only, never event content.
            if (client.fetchEvent(url).getOrNull() != null) println("ICS364|${config.name}|LEFT BEHIND ${java.net.URI(url).rawPath}")
        }
        // A server may file a resource under a name other than the one sent (an encoded slash or
        // percent decoded). Only listed hrefs carrying this run's unique UID, whose data holds one
        // of this run's UIDs, are deleted.
        val from = Instant.now().plus(19, ChronoUnit.DAYS).toEpochMilli()
        val to = Instant.now().plus(26, ChronoUnit.DAYS).toEpochMilli()
        for (calendarUrl in usedCalendars) {
            val listed = client.fetchEventsInRange(calendarUrl, from, to).getOrNull().orEmpty()
            for (event in listed) {
                val href = if (event.url.startsWith("http")) event.url else java.net.URI(calendarUrl).resolve(event.url).toString()
                val decoded = java.net.URLDecoder.decode(href, "UTF-8")
                val ours = (href.contains(uid) || decoded.contains(uid)) && ownUids.any { event.icalData.contains("UID:$it") }
                if (!ours) continue
                runCatching { client.deleteEvent(href, null) }
                println("ICS364|${config.name}|removed a copy filed as ${java.net.URI(href).rawPath.substringAfterLast('/')}")
            }
        }
        if (::db.isInitialized) db.close()
        unmockkAll()
    }

    @Test
    fun `an imported invitation is created, answered, re-imported and cancelled as an invitation`() = runBlocking<Unit> {
        val (calendarUrl, self) = target()
        val calendar = localCalendar(calendarUrl, self)
        val coordinator = coordinator()
        val start = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val sent = Instant.now()

        val created = import(coordinator, request(self, start, sequence = 0, stamp = sent), calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: $created"), 1, created.created)
        pushOrFail(calendar, "create")
        val event = ownEvent()
        val url = event.caldavUrl
        // This run's own create returned it; it carries the run's full UID.
        if (url != null && url.contains(uid)) cleanup += url
        assertEquals(
            "${config.name}: the create must land at this run's own href",
            "${calendarUrl.trimEnd('/')}/$uid.ics", url?.substringBefore('?'),
        )

        val stored = fetch(url!!)
        assertTrue(FixtureRedactor.redact("${config.name}: no METHOD in a stored resource: $stored"), stored.lines().none { it.startsWith("METHOD") })
        assertEquals("${config.name}: UID", uid, line(stored, "UID:")?.removePrefix("UID:"))
        assertTrue(FixtureRedactor.redact("${config.name}: ORGANIZER kept: $stored"), line(stored, "ORGANIZER")?.lowercase()?.endsWith(":mailto:$ORGANIZER") == true)
        assertEquals(FixtureRedactor.redact("${config.name}: the user is invited: $stored"), "NEEDS-ACTION", partstat(stored, self))

        // Answer, then answer again: the second PUT runs after the server stamped the copy.
        for (answer in listOf("ACCEPTED", "TENTATIVE")) {
            assertTrue("${config.name}: the app finds the user among the guests", coordinator.replyRsvp(ownEvent().id, answer))
            pushOrFail(calendar, "answer $answer")
            val answered = fetch(url)
            assertEquals(FixtureRedactor.redact("${config.name}: $answer reached the server: $answered"), answer, partstat(answered, self))
            assertEquals("${config.name}: UID after $answer", uid, line(answered, "UID:")?.removePrefix("UID:"))
            assertTrue(FixtureRedactor.redact("${config.name}: ORGANIZER after $answer: $answered"), line(answered, "ORGANIZER")?.lowercase()?.endsWith(":mailto:$ORGANIZER") == true)
            val serverStatus = line(answered, "ORGANIZER")?.let { Regex("""SCHEDULE-STATUS="?([0-9.]+)""").find(it)?.groupValues?.get(1) }
            assertEquals("${config.name}: the stored receipt is the server's", serverStatus, ownEvent().organizerScheduleStatus)
            println(FixtureRedactor.redact("ICS364|${config.name}|answer $answer|receipt ${serverStatus ?: "none"}"))
        }

        // The same file again: same SEQUENCE and DTSTAMP, so it is up to date (a later DTSTAMP
        // would be a newer version, RFC 5546 §2.1.5).
        val again = import(coordinator, request(self, start, sequence = 0, stamp = sent), calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: a re-import creates nothing: $again"), 0, again.created)
        assertEquals(FixtureRedactor.redact("${config.name}: an identical re-import is up to date: $again"), 1, again.upToDate)
        assertTrue("${config.name}: an identical re-import queues nothing", db.pendingOperationsDao().getAllOnce().none { it.status == PendingOperation.STATUS_PENDING })
        assertEquals("${config.name}: one copy", 1, db.eventsDao().getByUid(uid).size)

        val cancel = import(coordinator, cancel(self, start, sequence = 1, stamp = Instant.now()), calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: $cancel"), 1, cancel.cancelled)
        val pushed = pushOrFail(calendar, "cancel")
        val after = fetch(url)
        val refused = pushed.pushWarnings.any { it.contains("cancellation wasn't applied") }
        val status = line(after, "STATUS:")?.removePrefix("STATUS:")
        println(FixtureRedactor.redact("ICS364|${config.name}|cancel|server status ${status ?: "none"}|refused $refused"))
        if (refused) {
            assertTrue("${config.name}: a refused cancel puts the rows back", db.eventsDao().getByUid(uid).none { it.status == "CANCELLED" })
        } else {
            assertEquals(FixtureRedactor.redact("${config.name}: the server copy is cancelled: $after"), "CANCELLED", status)
        }
    }

    @Test
    fun `a rescheduled invitation updates the server copy, with the account among the guests`() = runBlocking<Unit> {
        val (calendarUrl, self) = target(anyWritable = true)
        rescheduleRoundTrip(calendarUrl, self, guest = self)
    }

    @Test
    fun `a rescheduled invitation updates the server copy when the account isn't among the guests`() = runBlocking<Unit> {
        val (calendarUrl, self) = target(anyWritable = true)
        rescheduleRoundTrip(calendarUrl, self, guest = "mailto:ics364-other-guest@example.test")
    }

    /**
     * Imports the invitation with [guest] as its attendee, uploads it, imports a rescheduled
     * version (SEQUENCE 1, a day later, new title), uploads that, and reads the server copy.
     */
    private suspend fun rescheduleRoundTrip(calendarUrl: String, self: String, guest: String) {
        val calendar = localCalendar(calendarUrl, self)
        val coordinator = coordinator()
        val start = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val moved = start.plus(1, ChronoUnit.DAYS)

        val created = import(coordinator, request(guest, start, sequence = 0, stamp = Instant.now().minusSeconds(60)), calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: $created"), 1, created.created)
        pushOrFail(calendar, "create")
        val url = ownEvent().caldavUrl
        if (url != null && url.contains(uid)) cleanup += url
        assertTrue("${config.name}: the create landed", url != null)

        val newer = request(guest, moved, sequence = 1, stamp = Instant.now())
            .replace("SUMMARY:Test invitation", "SUMMARY:Test invitation moved")
        val updated = import(coordinator, newer, calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: the newer file updates: $updated"), 1, updated.updated)
        assertEquals("${config.name}: one upload queued", 1, db.pendingOperationsDao().getAllOnce().count { it.status == PendingOperation.STATUS_PENDING })
        val pushed = pushOrFail(calendar, "reschedule")
        val after = fetch(url!!)
        val serverStart = line(after, "DTSTART")
        val serverSummary = line(after, "SUMMARY:")
        val serverSequence = line(after, "SEQUENCE:")
        println(FixtureRedactor.redact("ICS364|${config.name}|reschedule|guest ${if (guest == self) "self" else "other"}|$serverStart|$serverSummary|$serverSequence|warnings ${pushed.pushWarnings}"))
        assertTrue("${config.name}: nothing left queued", db.pendingOperationsDao().getAllOnce().none { it.status == PendingOperation.STATUS_PENDING })
        if (config.name == CalDavServerConfig.ZOHO.name) {
            // Zoho answers a guest's PUT on the event's own URL with 201, no ETag and the etag
            // unchanged, and keeps its copy (live 2026-11-08). The server copy is printed, not
            // asserted; the row keeps the file's version until the event changes on the server.
            assertEquals("${config.name}: the row holds the file's version", "Test invitation moved", ownEvent().title)
            return
        }
        assertEquals(FixtureRedactor.redact("${config.name}: the new time reached the server: $after"), "DTSTART:${UTC.format(moved)}", serverStart)
        assertEquals(FixtureRedactor.redact("${config.name}: the new title reached the server: $after"), "SUMMARY:Test invitation moved", serverSummary)

        val again = import(coordinator, newer, calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: the same file again is up to date: $again"), 1, again.upToDate)
        assertTrue("${config.name}: and queues nothing", db.pendingOperationsDao().getAllOnce().none { it.status == PendingOperation.STATUS_PENDING })
    }

    @Test
    fun `a reschedule after two answers to an imported invitation still updates`() = runBlocking<Unit> {
        val (calendarUrl, self) = target(anyWritable = true)
        answerThenReschedule(calendarUrl, self, fromServer = false, answers = listOf("ACCEPTED", "TENTATIVE"))
    }

    @Test
    fun `a reschedule after answering an invitation the calendar synced still updates`() = runBlocking<Unit> {
        val (calendarUrl, self) = target(anyWritable = true)
        answerThenReschedule(calendarUrl, self, fromServer = true, answers = listOf("ACCEPTED"))
    }

    /**
     * Puts the invitation in the calendar (imported, or created on the server and pulled when
     * [fromServer]), sends each of [answers], pulls, then imports a reschedule at SEQUENCE 1 with
     * a later DTSTAMP. A server that raises SEQUENCE when it processes an answer leaves the row
     * above the file's SEQUENCE after the pull.
     */
    private suspend fun answerThenReschedule(calendarUrl: String, self: String, fromServer: Boolean, answers: List<String>) {
        val calendar = localCalendar(calendarUrl, self)
        val coordinator = coordinator()
        val start = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val moved = start.plus(1, ChronoUnit.DAYS)
        val original = request(self, start, sequence = 0, stamp = Instant.now().minusSeconds(60))

        if (fromServer) {
            val body = original.replace(Regex("METHOD:REQUEST\r?\n"), "")
            val created = client.createEvent(calendarUrl, uid, body).getOrNull()?.first
            assumeTrue("${config.name}: setup create refused", created != null)
            cleanup += created!!
            pull(calendar)
        } else {
            assertEquals(1, import(coordinator, original, calendar).created)
            pushOrFail(calendar, "create")
            ownEvent().caldavUrl?.takeIf { it.contains(uid) }?.let { cleanup += it }
        }
        val url = requireNotNull(ownEvent().caldavUrl)
        for (answer in answers) {
            assertTrue("${config.name}: the app finds the user among the guests", coordinator.replyRsvp(ownEvent().id, answer))
            pushOrFail(calendar, "answer $answer")
        }
        val serverSequence = line(fetch(url), "SEQUENCE:")
        pull(calendar)
        val row = ownEvent()
        println(FixtureRedactor.redact("ICS364|${config.name}|answer then reschedule|from server $fromServer|answers ${answers.size}|server $serverSequence|row sequence ${row.sequence}|row itipDtstamp ${row.itipDtstamp != null}"))

        val newer = request(self, moved, sequence = 1, stamp = Instant.now())
            .replace("SUMMARY:Test invitation", "SUMMARY:Test invitation moved")
        val result = import(coordinator, newer, calendar)
        println(FixtureRedactor.redact("ICS364|${config.name}|answer then reschedule|from server $fromServer|import $result"))
        assertEquals(FixtureRedactor.redact("${config.name}: the reschedule updates after ${answers.size} answer(s): $result"), 1, result.updated)
    }

    private suspend fun pull(calendar: Calendar) {
        val dataStore = mockk<KashCalDataStore>(relaxed = true) {
            // The relaxed default isn't a working Flow, and the pull fails. Int.MAX_VALUE is "All".
            every { syncPastDays } returns flowOf(Int.MAX_VALUE)
        }
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        val pulled = PullStrategy(
            database = db,
            calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
            eventsDao = db.eventsDao(),
            attendeesDao = db.attendeesDao(),
            occurrenceGenerator = generator,
            defaultQuirks = config.quirksFactory(creds.serverUrl),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
        ).pull(db.calendarsDao().getById(calendar.id)!!, forceFullSync = true, client = client)
        println("ICS364|${config.name}|pull ${pulled::class.simpleName}")
    }

    @Test
    fun `a create the server refuses for a UID it holds stops with an error`() = runBlocking<Unit> {
        val (calendarUrl, self) = target()
        assumeTrue("${config.name}: the refused create runs on local servers only", calendarUrl.contains("localhost") || calendarUrl.contains("127.0.0.1"))
        val start = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val body = request(self, start, sequence = 0, stamp = Instant.now()).replace(Regex("METHOD:REQUEST\r?\n"), "")
        val existing = client.createEvent(calendarUrl, "$uid-held", body).getOrNull()?.first
        assumeTrue("${config.name}: setup create refused", existing != null)
        cleanup += existing!!
        val calendar = localCalendar(calendarUrl, self)

        import(coordinator(), request(self, start, sequence = 0, stamp = Instant.now()), calendar)
        assertOnlyOwnWorkQueued()
        val push = PushStrategyFor().pushForCalendar(db.calendarsDao().getById(calendar.id)!!, client)
        val event = ownEvent()
        event.caldavUrl?.let { cleanup += it }

        val ops = db.pendingOperationsDao().getAllOnce()
        println(FixtureRedactor.redact("ICS364|${config.name}|duplicate create|push $push|ops ${ops.map { it.operation to it.status }}|error ${event.lastSyncError}"))
        if (event.caldavUrl == null) {
            assertTrue(FixtureRedactor.redact("${config.name}: a refused create records why: $push"), event.lastSyncError?.contains("refused the imported event") == true)
            assertTrue("${config.name}: and is not retried", ops.none { it.status == PendingOperation.STATUS_PENDING })
        }
    }

    @Test
    fun `a UID another calendar of the account holds skips the invitation and renews a plain event`() = runBlocking<Unit> {
        val calendars = UnsharedCalendarPicker(config, client, creds).findAll()
        assumeTrue("${config.name}: needs two calendars the sharing check accepts (has ${calendars.size})", calendars.size >= 2)
        val self = selfAddress()
        val first = localCalendar(calendars[0], self)
        val second = localCalendar(calendars[1], self, accountId = first.accountId)
        val coordinator = coordinator()
        val start = Instant.now().plus(21, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)

        // An invitation where the account has an address; a plain event elsewhere.
        val firstFile = if (self != null) request(self, start, sequence = 0, stamp = Instant.now()) else plain(start)
        assertEquals(1, import(coordinator, firstFile, first).created)
        pushOrFail(first, "create in the first calendar")
        val url = ownEvent().caldavUrl
        assertEquals("${config.name}: own href", ownHref(calendars[0], uid), url)
        cleanup += url!!

        if (self != null) {
            val again = import(coordinator, firstFile, second)
            assertEquals(FixtureRedactor.redact("${config.name}: $again"), listOf(IgnoreReason.ON_ANOTHER_CALENDAR), again.ignored)
            assertTrue("${config.name}: nothing queued for the second calendar", db.pendingOperationsDao().getAllOnce().isEmpty())
        }

        assertEquals(1, import(coordinator, plain(start), second).created)
        val copy = db.eventsDao().getAllMasterEventsForCalendar(second.id).single()
        assertNotEquals("${config.name}: the plain copy gets a fresh UID", uid, copy.uid)
        ownUids += copy.uid
        pushOrFail(second, "create in the second calendar")
        val copyUrl = ownEvent(copy.uid).caldavUrl
        assertEquals("${config.name}: own href of the copy", ownHref(calendars[1], copy.uid), copyUrl)
        cleanup += copyUrl!!
        assertEquals("${config.name}: the copy's UID on the server", copy.uid, line(fetch(copyUrl), "UID:")?.removePrefix("UID:"))
        assertEquals("${config.name}: the first copy is untouched", uid, line(fetch(url), "UID:")?.removePrefix("UID:"))
        println("ICS364|${config.name}|uid elsewhere in account|invitation ${if (self != null) "skipped" else "not tried"}|plain copy fresh")
    }

    @Test
    fun `deleting an imported event and importing it again brings it back under the same UID`() = runBlocking<Unit> {
        val calendarUrl = UnsharedCalendarPicker(config, client, creds).find()
        assumeTrue("${config.name}: no calendar the sharing check accepts", calendarUrl != null)
        val calendar = localCalendar(calendarUrl!!, selfAddress())
        val coordinator = coordinator()
        val start = Instant.now().plus(22, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)

        assertEquals(1, import(coordinator, plain(start), calendar).created)
        pushOrFail(calendar, "create")
        val url = ownEvent().caldavUrl
        assertEquals("${config.name}: own href", ownHref(calendarUrl, uid), url)
        cleanup += url!!

        coordinator.deleteEvent(ownEvent().id)
        val pending = import(coordinator, plain(start), calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: $pending"), listOf(IgnoreReason.DELETION_PENDING), pending.ignored)
        pushOrFail(calendar, "delete")
        assertTrue("${config.name}: the delete reached the server", client.fetchEvent(url).getOrNull() == null)
        assertTrue("${config.name}: the row is gone", db.eventsDao().getByUid(uid).isEmpty())

        assertEquals(1, import(coordinator, plain(start), calendar).created)
        val recreated = push(calendar) as PushResult.Success
        if (recreated.pushErrors.isNotEmpty()) {
            // Nextcloud keeps a deleted object in its trash bin and allows one per UID, so it
            // answers 500 until the trash is emptied. Recorded, not asserted: the event stays
            // local with a sync error.
            println("ICS364|${config.name}|delete then re-import|re-create refused ${recreated.pushErrors.map { it.code }}")
            return@runBlocking
        }
        assertEquals("${config.name}: back at the same href", url, ownEvent().caldavUrl)
        assertEquals("${config.name}: same UID", uid, line(fetch(url), "UID:")?.removePrefix("UID:"))
        println("ICS364|${config.name}|delete then re-import|same UID back")
    }

    @Test
    fun `UIDs a resource name has to encode round trip`() = runBlocking<Unit> {
        val calendarUrl = UnsharedCalendarPicker(config, client, creds).find()
        assumeTrue("${config.name}: no calendar the sharing check accepts", calendarUrl != null)
        val calendar = localCalendar(calendarUrl!!, selfAddress())
        val coordinator = coordinator()
        val start = Instant.now().plus(23, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val shapes = linkedMapOf(
            "at sign" to "$uid@example.test",
            "space" to "$uid spaced",
            "plus" to "$uid+tag",
            "long" to "$uid-" + "0123456789abcdef".repeat(10),
            "slash" to "$uid/part",
            "percent" to "$uid%41",
        )
        // Recorded, not asserted: most servers refuse an encoded slash in a resource name and one
        // files it under the decoded path; one stores a plus sign under an encoded name.
        val recordedOnly = setOf("slash", "plus")
        ownUids += shapes.values

        val outcomes = linkedMapOf<String, String>()
        for ((shape, shapeUid) in shapes) {
            outcomes[shape] = runCatching {
                check(import(coordinator, plain(start, shapeUid), calendar).created == 1) { "not created locally" }
                val created = push(calendar)
                val url = ownEvent(shapeUid).caldavUrl
                    ?: error("create refused: ${(created as? PushResult.Success)?.pushErrors?.map { it.code }}")
                check(url == ownHref(calendarUrl, shapeUid)) { "landed at an unexpected href" }
                cleanup += url
                check(line(fetch(url), "UID:")?.removePrefix("UID:") == shapeUid) { "the server changed the UID" }
                coordinator.updateEvent(ownEvent(shapeUid).copy(title = "Renamed $shape"))
                val updated = push(calendar)
                check(updated is PushResult.Success && updated.pushErrors.isEmpty()) { "update refused: $updated" }
                check(line(fetch(url), "SUMMARY:") == "SUMMARY:Renamed $shape") { "the update didn't reach the server" }
                "ok"
            }.getOrElse { it.message ?: it.toString() }
        }
        println(FixtureRedactor.redact("ICS364|${config.name}|uid shapes|$outcomes"))
        assertTrue(
            FixtureRedactor.redact("${config.name}: $outcomes"),
            outcomes.filterKeys { it !in recordedOnly }.values.all { it == "ok" },
        )
    }

    // ---- the files ----

    private fun issueFile(): String =
        requireNotNull(javaClass.classLoader?.getResource("ics/itip/issue_364_request.ics")).readText()

    /** The #364 file for this run: its UID, the user as the attendee, times [start] onward. */
    private fun request(self: String, start: Instant, sequence: Int, stamp: Instant): String = issueFile()
        .replace(ISSUE_UID, uid)
        .replace("mailto:attendee@example.com", self)
        .replace("mailto:organizer@example.com", "mailto:$ORGANIZER")
        .replace("DTSTAMP:20260904T170000Z", "DTSTAMP:${UTC.format(stamp)}\nSEQUENCE:$sequence")
        .replace("DTSTART:20260910T170000Z", "DTSTART:${UTC.format(start)}")
        .replace("DTEND:20260910T180000Z", "DTEND:${UTC.format(start.plus(1, ChronoUnit.HOURS))}")

    /** The #364 file as a plain event: no METHOD, organizer or guests, with UID [eventUid]. */
    private fun plain(start: Instant, eventUid: String = uid): String = issueFile()
        .replace(ISSUE_UID, eventUid)
        .replace(Regex("METHOD:REQUEST\r?\n"), "")
        .replace(Regex("(ORGANIZER|ATTENDEE)[^\n]*\n"), "")
        .replace("DTSTART:20260910T170000Z", "DTSTART:${UTC.format(start)}")
        .replace("DTEND:20260910T180000Z", "DTEND:${UTC.format(start.plus(1, ChronoUnit.HOURS))}")

    private fun cancel(self: String, start: Instant, sequence: Int, stamp: Instant): String =
        request(self, start, sequence, stamp).replace("METHOD:REQUEST", "METHOD:CANCEL").replace("STATUS:CONFIRMED", "STATUS:CANCELLED")

    // ---- the server ----

    /**
     * Returns an unshared writable calendar and the account's own address, or skips. With
     * [anyWritable] a calendar the sharing check can't confirm also serves, since every account in
     * this suite holds only test data (Zoho reports no sharing properties, so only that reaches
     * it).
     */
    private suspend fun target(anyWritable: Boolean = false): Pair<String, String> {
        val picker = UnsharedCalendarPicker(config, client, creds)
        val calendarUrl = picker.find() ?: if (anyWritable) picker.findWritable() else null
        assumeTrue("${config.name}: no calendar the sharing check accepts", calendarUrl != null)
        val self = selfAddress()
        assumeTrue("${config.name}: no calendar-user address", self != null)
        return calendarUrl!! to self!!
    }

    /** Returns the account's first mailto calendar-user address, or null. */
    private suspend fun selfAddress(): String? {
        val endpoint = creds.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) client.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        return principal?.let { client.discoverCalendarUserAddresses(it).getOrNull() }.orEmpty()
            .firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
    }

    /** Returns the URL a create of [eventUid] lands at in [calendarUrl], as the client names it. */
    private fun ownHref(calendarUrl: String, eventUid: String) =
        "${calendarUrl.trimEnd('/')}/${encodedPathSegment("$eventUid.ics")}"

    private suspend fun fetch(url: String): String {
        val body = client.fetchEvent(url).getOrNull()?.icalData
        assertTrue("${config.name}: GET of this run's own resource failed", body != null)
        return body!!.replace(Regex("""\r?\n[ \t]"""), "")
    }

    private fun line(ics: String, prefix: String): String? {
        val event = ics.substringAfter("BEGIN:VEVENT").substringBefore("END:VEVENT")
        return event.lines().map { it.trimEnd('\r') }.firstOrNull { it.startsWith(prefix) }
    }

    private fun partstat(ics: String, self: String): String? {
        val key = self.removePrefix("mailto:").lowercase()
        return ics.lines().firstOrNull { it.startsWith("ATTENDEE") && it.lowercase().trimEnd('\r').endsWith(key) }
            ?.let { Regex("""PARTSTAT=([A-Z-]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase() }
            ?: "absent"
    }

    // ---- the app ----

    private suspend fun import(coordinator: EventCoordinator, text: String, calendar: Calendar): IcsImportResult =
        coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(text)), calendar.id)

    private suspend fun ownEvent(eventUid: String = uid): Event =
        db.eventsDao().getByUid(eventUid).single { it.originalEventId == null }

    private suspend fun assertOnlyOwnWorkQueued() {
        val foreign = db.pendingOperationsDao().getAllOnce().filter { db.eventsDao().getById(it.eventId)?.uid !in ownUids }
        assertTrue("${config.name}: refusing to push work for other events: ${foreign.map { it.id }}", foreign.isEmpty())
    }

    private suspend fun push(calendar: Calendar): PushResult {
        assertOnlyOwnWorkQueued()
        return PushStrategyFor().pushForCalendar(db.calendarsDao().getById(calendar.id)!!, client)
    }

    private suspend fun pushOrFail(calendar: Calendar, step: String): PushResult.Success {
        val push = push(calendar)
        assertTrue(
            FixtureRedactor.redact("${config.name}: push after $step: $push"),
            push is PushResult.Success && push.pushErrors.isEmpty(),
        )
        return push as PushResult.Success
    }

    private suspend fun localCalendar(url: String, self: String?, accountId: Long? = null): Calendar {
        val account = accountId ?: db.accountsDao().insert(
            Account(
                provider = AccountProvider.CALDAV,
                email = self?.removePrefix("mailto:") ?: "ics364-${config.name.lowercase()}@example.test",
                calendarUserAddresses = listOfNotNull(self),
            ),
        )
        usedCalendars += url
        val id = db.calendarsDao().insert(Calendar(accountId = account, caldavUrl = url, displayName = "ICS import", color = 0))
        return db.calendarsDao().getById(id)!!
    }

    private fun coordinator(): EventCoordinator {
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        return EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accountRepository(),
            // Side-effect collaborators only: the test pushes itself.
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = mockk<KashCalDataStore>(relaxed = true) {
                every { defaultReminderMinutes } returns flowOf(15)
                every { defaultAllDayReminder } returns flowOf(1440)
            },
        )
    }

    @Suppress("FunctionName")
    private fun PushStrategyFor() = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        accountRepository = accountRepository(),
        attendeesDao = db.attendeesDao(),
        pendingCancelsDao = db.pendingCancelsDao(),
    )

    private fun accountRepository(): AccountRepository = AccountRepositoryImpl(
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
}
