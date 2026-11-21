package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * An .ics file with properties the app doesn't model, imported into a CalDAV calendar on every
 * server and uploaded, as a user does from a mail attachment or a downloaded file.
 *
 * The body the app sends must carry the file's lines exactly as written. What the server then
 * stores is compared with a reference copy of the same file this run creates directly on that
 * server, so a server that rewrites what it stores (SOGo re-escapes quoted parameters on every
 * store, [UnknownPropertyLineCompare.serverNormalized]) isn't blamed on the app. The same holds
 * after a title edit and per VEVENT for a series with a changed occurrence. Where the account has
 * a calendar-user address, an imported invitation is sent with its lines as written and, when the
 * server applies the imported CANCEL, the cancelled copy keeps the lines it stored before. Each
 * create also prints whether the first pull of the uploaded resource would report the event as
 * changed.
 *
 * Writes events only to the first writable calendar of each server. Nothing is pulled: the
 * database holds only this run's imports, every push first checks that only this run's
 * operations are queued, and teardown deletes only the URLs this run's own create calls
 * returned, each carrying this run's unique UID. Fixture addresses are `@example.test`; failure
 * messages go through [FixtureRedactor].
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerImportedUnknownPropertiesTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerImportedUnknownPropertiesTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val ORGANIZER = "import-unknown-organizer@example.test"

        private val SINGLE_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"10600 N Tantau Ave\\nCupertino, CA 95014\";X-APPLE-RADIUS=70;X-TITLE=\"Apple Park: Visitor Center\":geo:37.332,-122.005",
            "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY:mail\\,msg-1234\\;part\\=2",
            "X-MICROSOFT-LOCATIONS:[{\"DisplayName\":\"Room 1\\, Floor 2\"}]",
            "COMMENT:line one\\nline two\\, with comma",
            "RESOURCES:Projector",
            "RESOURCES:Whiteboard",
        )
        private val SERIES_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Room, 1\":geo:1.0,2.0",
            "COMMENT:every week\\nbring notes",
        )
        private val OCCURRENCE_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:3.0,4.0",
            "COMMENT:moved\\nfor this week only",
        )
        /** Fields the pull's change check ignores (PullStrategy.hasContentChanged). */
        private val SYNC_METADATA = setOf(
            "id", "etag", "syncStatus", "caldavUrl", "dtstamp", "importId", "originalEventId",
            "originalInstanceTime", "originalSyncId", "calendarId", "uid", "createdAt", "updatedAt",
            "localModifiedAt", "serverModifiedAt", "lastSyncError", "syncRetryCount",
        )
        private val UNKNOWN_NAMES = (SINGLE_LINES + SERIES_LINES + OCCURRENCE_LINES)
            .map { UnknownPropertyLineCompare.nameOf(it) }.toSet()
    }

    private lateinit var client: CalDavClient
    private lateinit var creds: ServerCredentials
    private lateinit var db: KashCalDatabase
    private val uid = "kc-import-unknown-${UUID.randomUUID()}"
    private val ownUids = mutableSetOf<String>()
    private val cleanup = mutableListOf<String>()

    @Before
    fun setUp() {
        val pair = CalDavTestServerLoader.createClient(config)
        assumeTrue("${config.name} credentials not available", pair != null)
        client = pair!!.first
        creds = pair.second
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds.davEndpoint))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = runBlocking {
        // Only URLs this run's own create calls returned, each carrying this run's UID.
        val own = cleanup.filter { it.contains(uid) }
        for (url in own) {
            runCatching { client.deleteEvent(url, client.fetchEtag(url).getOrNull()) }
            runCatching { client.deleteEvent(url, null) }
        }
        // Zoho answers a DELETE with 204 and drops the event from the calendar, but keeps
        // serving it to a GET by href, so there the calendar's etag listing is checked instead.
        val stillThere = if (config.name == CalDavServerConfig.ZOHO.name && own.isNotEmpty()) {
            val calendarUrl = own.first().substringBeforeLast('/') + "/"
            val listed = client.fetchEtagsInRange(calendarUrl, 0L, Long.MAX_VALUE / 4).getOrNull().orEmpty()
                .map { it.first.substringAfterLast('/') }.toSet()
            own.filter { it.substringAfterLast('/') in listed }
        } else {
            own.filter { client.fetchEvent(it).getOrNull() != null }
        }
        stillThere.forEach { println("IMPORT-UNKNOWN|${config.name}|LEFT BEHIND ${it.substringAfterLast('/')}") }
        if (::db.isInitialized) db.close()
        unmockkAll()
    }

    @Test
    fun `an imported event uploads and keeps its lines as written`() = runBlocking<Unit> {
        val calendarUrl = firstWritableCalendar()
        val start = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val reference = referenceCopy(calendarUrl, "$uid-ref") { single(it, start) }
        val calendar = roomCalendar(calendarUrl)
        val coordinator = coordinator()

        assertEquals(1, import(coordinator, single(uid, start), calendar).created)
        val url = uploadedUrl(calendar, uid, "create")
        assertEquals("sent as written", SINGLE_LINES, unknownLines(blocks(storedIcs(uid)).single()))
        assertServerKeeps("create", reference, fetch(url))
        reportFirstPull(calendar, url, uid, "create")

        coordinator.updateEvent(ownEvent(uid).copy(title = "Visitor tour (edited)"))
        pushOrFail(calendar, "title edit")
        val edited = fetch(url)
        assertTrue(FixtureRedactor.redact("${config.name}: edit reached the server: $edited"), edited.contains("SUMMARY:Visitor tour (edited)"))
        assertServerKeeps("title edit", reference, edited)
        println("IMPORT-UNKNOWN|${config.name}|single|kept after create and edit")
    }

    @Test
    fun `an imported series uploads each VEVENT with its own lines`() = runBlocking<Unit> {
        val calendarUrl = firstWritableCalendar()
        val start = Instant.now().plus(21, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val seriesUid = "$uid-series"
        val reference = referenceCopy(calendarUrl, "$uid-series-ref") { series(it, start) }
        val calendar = roomCalendar(calendarUrl)
        val coordinator = coordinator()

        assertEquals(2, import(coordinator, series(seriesUid, start), calendar).created)
        val url = uploadedUrl(calendar, seriesUid, "create")
        val sent = blocks(storedIcs(seriesUid))
        assertEquals("series sent as written", SERIES_LINES, unknownLines(sent.seriesBlock()))
        assertEquals("occurrence sent as written", OCCURRENCE_LINES, unknownLines(sent.occurrenceBlock()))
        assertServerKeeps("series create", reference, fetch(url))
        reportFirstPull(calendar, url, seriesUid, "series create")

        coordinator.updateEvent(ownEvent(seriesUid).copy(title = "Weekly review (edited)"))
        pushOrFail(calendar, "series title edit")
        assertServerKeeps("series title edit", reference, fetch(url))
        println("IMPORT-UNKNOWN|${config.name}|series|kept per VEVENT after create and edit")
    }

    @Test
    fun `an imported invitation keeps its lines through its CANCEL`() = runBlocking<Unit> {
        val calendarUrl = firstWritableCalendar()
        val self = selfAddress()
        assumeTrue("${config.name}: no calendar-user address", self != null)
        val start = Instant.now().plus(22, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
        val inviteUid = "$uid-invite"
        val calendar = roomCalendar(calendarUrl, self)
        val coordinator = coordinator()

        assertEquals(1, import(coordinator, invitation(inviteUid, start, self!!, "REQUEST", 0), calendar).created)
        val url = uploadedUrl(calendar, inviteUid, "invitation create")
        assertEquals("sent as written", SINGLE_LINES, unknownLines(blocks(storedIcs(inviteUid)).single()))
        val stored = fetch(url)

        val cancel = import(coordinator, invitation(inviteUid, start, self, "CANCEL", 1), calendar)
        assertEquals(FixtureRedactor.redact("${config.name}: $cancel"), 1, cancel.cancelled)
        val pushed = pushOrFail(calendar, "cancel")
        val after = fetch(url)
        val refused = pushed.pushWarnings.any { it.contains("cancellation wasn't applied") }
        println(FixtureRedactor.redact("IMPORT-UNKNOWN|${config.name}|invitation cancel|refused $refused"))
        if (!refused) {
            assertEquals(
                FixtureRedactor.redact("${config.name}: the cancelled copy keeps the server's lines: $after"),
                normalized(blocks(stored).single()), normalized(blocks(after).single()),
            )
        }
    }

    // ---- files ----

    private fun calendarFile(vararg vevents: List<String>) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple Inc.//macOS 15.0//EN") +
            vevents.flatMap { it } + "END:VCALENDAR"
    ).joinToString("\r\n", postfix = "\r\n")

    private fun single(eventUid: String, start: Instant) = calendarFile(
        listOf(
            "BEGIN:VEVENT", "UID:$eventUid", "DTSTAMP:${UTC.format(Instant.now())}",
            "DTSTART:${UTC.format(start)}", "DTEND:${UTC.format(start.plus(1, ChronoUnit.HOURS))}",
            "SUMMARY:Visitor tour",
        ) + SINGLE_LINES + "END:VEVENT",
    )

    private fun series(eventUid: String, start: Instant): String {
        val moved = start.plus(7, ChronoUnit.DAYS)
        return calendarFile(
            listOf(
                "BEGIN:VEVENT", "UID:$eventUid", "DTSTAMP:${UTC.format(Instant.now())}",
                "DTSTART:${UTC.format(start)}", "DTEND:${UTC.format(start.plus(1, ChronoUnit.HOURS))}",
                "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Weekly review",
            ) + SERIES_LINES + "END:VEVENT",
            listOf(
                "BEGIN:VEVENT", "UID:$eventUid", "DTSTAMP:${UTC.format(Instant.now())}",
                "RECURRENCE-ID:${UTC.format(moved)}", "DTSTART:${UTC.format(moved.plus(2, ChronoUnit.HOURS))}",
                "DTEND:${UTC.format(moved.plus(3, ChronoUnit.HOURS))}", "SUMMARY:Weekly review (moved)",
            ) + OCCURRENCE_LINES + "END:VEVENT",
        )
    }

    private fun invitation(eventUid: String, start: Instant, self: String, method: String, sequence: Int) = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Mail//EN", "METHOD:$method") +
            listOf(
                "BEGIN:VEVENT", "UID:$eventUid", "DTSTAMP:${UTC.format(Instant.now())}", "SEQUENCE:$sequence",
                "DTSTART:${UTC.format(start)}", "DTEND:${UTC.format(start.plus(1, ChronoUnit.HOURS))}",
                "SUMMARY:Visitor tour", "ORGANIZER:mailto:$ORGANIZER",
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$self",
            ) + (if (method == "CANCEL") listOf("STATUS:CANCELLED") else emptyList()) + SINGLE_LINES +
            "END:VEVENT" + "END:VCALENDAR"
        ).joinToString("\r\n", postfix = "\r\n")

    // ---- the server ----

    private suspend fun firstWritableCalendar(): String {
        val endpoint = creds.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) client.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        val home = principal?.let { client.discoverCalendarHome(it).getOrNull()?.firstOrNull() }
        val calendars = home?.let { client.listCalendars(it).getOrNull() }.orEmpty()
            .filter { !it.url.contains("inbox") && !it.url.contains("outbox") }
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
        val url = (calendars.firstOrNull { it.url.trimEnd('/').endsWith("/work") } ?: calendars.firstOrNull())?.url
        assumeTrue("${config.name}: no writable calendar", url != null)
        return url!!
    }

    private suspend fun selfAddress(): String? {
        val endpoint = creds.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) client.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        return principal?.let { client.discoverCalendarUserAddresses(it).getOrNull() }.orEmpty()
            .firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
    }

    /** Creates the same file directly on the server under [refUid] and returns what it stores. */
    private suspend fun referenceCopy(calendarUrl: String, refUid: String, body: (String) -> String): String {
        ownUids += refUid
        val created = client.createEvent(calendarUrl, refUid, body(refUid)).getOrNull()?.first
        assumeTrue("${config.name}: the server refused the reference file", created != null)
        cleanup += created!!
        return fetch(created)
    }

    private suspend fun fetch(url: String): String {
        val body = client.fetchEvent(url).getOrNull()?.icalData
        assertTrue("${config.name}: GET of this run's own resource failed", body != null)
        return body!!
    }

    // ---- the app ----

    private suspend fun roomCalendar(url: String, self: String? = null): Calendar {
        val account = db.accountsDao().insert(
            Account(
                provider = AccountProvider.CALDAV,
                email = self?.removePrefix("mailto:") ?: "import-unknown-${config.name.lowercase()}@example.test",
                calendarUserAddresses = listOfNotNull(self),
            ),
        )
        val id = db.calendarsDao().insert(Calendar(accountId = account, caldavUrl = url, displayName = "Import", color = 0))
        return db.calendarsDao().getById(id)!!
    }

    private suspend fun import(coordinator: EventCoordinator, text: String, calendar: Calendar): IcsImportResult {
        val file = requireNotNull(IcsParserService.parseForImport(text))
        ownUids += file.groups.map { it.uid }
        return coordinator.importIcsFile(file, calendar.id)
    }

    private suspend fun ownEvent(eventUid: String): Event =
        db.eventsDao().getByUid(eventUid).single { it.originalEventId == null }

    /** Returns the stored ICS of [eventUid]'s series row: the body the last push sent. */
    private suspend fun storedIcs(eventUid: String): String = EventIcsStore(db).read(ownEvent(eventUid))!!

    /** Pushes the import's create and returns the URL it landed at (this run's own). */
    private suspend fun uploadedUrl(calendar: Calendar, eventUid: String, step: String): String {
        val pushed = runCatching { pushOrFail(calendar, step) }
        // The row holds the URL this run's own create returned (the push stores it); recorded
        // before any assertion so a failed step still cleans it up.
        val url = ownEvent(eventUid).caldavUrl
        if (url != null && url.contains(eventUid)) cleanup += url
        pushed.getOrThrow()
        assertTrue("${config.name}: the create must land at an href with this run's UID", url != null && url.contains(eventUid))
        return url!!
    }

    private suspend fun pushOrFail(calendar: Calendar, step: String): PushResult.Success {
        val foreign = db.pendingOperationsDao().getAllOnce().filter { db.eventsDao().getById(it.eventId)?.uid !in ownUids }
        assertTrue("${config.name}: refusing to push work for other events: ${foreign.map { it.id }}", foreign.isEmpty())
        val push = pushStrategy().pushForCalendar(db.calendarsDao().getById(calendar.id)!!, client)
        assertTrue(
            FixtureRedactor.redact("${config.name}: push after $step: $push"),
            push is PushResult.Success && push.pushErrors.isEmpty(),
        )
        return push as PushResult.Success
    }

    /** Prints whether the first pull of [url] would report the uploaded event as changed. */
    private suspend fun reportFirstPull(calendar: Calendar, url: String, eventUid: String, step: String) {
        val fetched = client.fetchEventsByHref(calendar.caldavUrl, listOf(java.net.URI(url).rawPath)).getOrNull()?.singleOrNull()
            ?: return println("IMPORT-UNKNOWN|${config.name}|$step|first pull|multiget returned nothing")
        val existing = ownEvent(eventUid)
        val parsed = ICalParser().parseAllEvents(fetched.icalData).getOrNull()?.firstOrNull { it.recurrenceId == null }
            ?: return println("IMPORT-UNKNOWN|${config.name}|$step|first pull|unparseable")
        // Carried over from the stored row before the pull compares, as PullStrategy does.
        val mapped = ICalEventMapper.toEntity(parsed, fetched.icalData, calendar.id, url, fetched.etag).event
        val incoming = mapped.copy(
            color = mapped.color ?: existing.color,
            importSource = existing.importSource,
            itipDtstamp = existing.itipDtstamp,
        )
        val changed = PullStrategy.hasContentChanged(existing, incoming)
        // Field names only: the values can carry account addresses.
        val fields = if (!changed) emptyList() else Event::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .filter { it.name !in SYNC_METADATA && emptyAsNull(it.get(existing)) != emptyAsNull(it.get(incoming)) }
            .map { it.name }
        println("IMPORT-UNKNOWN|${config.name}|$step|first pull reports a change $changed $fields")
    }

    // ---- comparison ----

    /** Room reads a stored null list or map back as empty; the pull's check treats them alike. */
    private fun emptyAsNull(value: Any?): Any? = when (value) {
        is Collection<*> -> value.takeIf { it.isNotEmpty() }
        is Map<*, *> -> value.takeIf { it.isNotEmpty() }
        else -> value
    }

    private fun blocks(ics: String) = UnknownPropertyLineCompare.veventBlocks(ics)
    private fun unknownLines(block: List<String>) = block.filter { UnknownPropertyLineCompare.nameOf(it) in UNKNOWN_NAMES }
    private fun normalized(block: List<String>) = unknownLines(block).map { UnknownPropertyLineCompare.serverNormalized(config, it) }.sorted()
    private fun List<List<String>>.seriesBlock() = single { b -> b.none { UnknownPropertyLineCompare.nameOf(it) == "RECURRENCE-ID" } }
    private fun List<List<String>>.occurrenceBlock() = single { b -> b.any { UnknownPropertyLineCompare.nameOf(it) == "RECURRENCE-ID" } }

    /** Asserts the server stored the upload with the reference's unknown lines, per VEVENT. */
    private fun assertServerKeeps(step: String, reference: String, stored: String) {
        val expected = blocks(reference)
        val actual = blocks(stored)
        assertEquals(FixtureRedactor.redact("${config.name}: $step VEVENT count: $stored"), expected.size, actual.size)
        assertTrue(FixtureRedactor.redact("${config.name}: $step stored copy parses: $stored"), ICalParser().parseAllEvents(stored).getOrNull() != null)
        if (expected.size == 1) {
            assertEquals(FixtureRedactor.redact("${config.name}: $step: $stored"), normalized(expected.single()), normalized(actual.single()))
        } else {
            assertEquals(FixtureRedactor.redact("${config.name}: $step series: $stored"), normalized(expected.seriesBlock()), normalized(actual.seriesBlock()))
            assertEquals(FixtureRedactor.redact("${config.name}: $step occurrence: $stored"), normalized(expected.occurrenceBlock()), normalized(actual.occurrenceBlock()))
        }
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
            dataStore = mockk<KashCalDataStore> {
                every { defaultReminderMinutes } returns flowOf(15)
                every { defaultAllDayReminder } returns flowOf(1440)
            },
        )
    }

    private fun pushStrategy() = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        accountRepository = accountRepository(),
        attendeesDao = db.attendeesDao(),
        pendingCancelsDao = db.pendingCancelsDao(),
        icsSource = EventIcsStore(db),
    )

    private fun accountRepository(): AccountRepository = AccountRepositoryImpl(
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
}
