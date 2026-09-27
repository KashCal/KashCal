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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.PushStrategy
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Checks that properties the app doesn't model (vendor structured locations, mail suggestion keys,
 * meeting-location JSON, COMMENT, RESOURCES, ATTACH, repeated X- properties) come back from the
 * server exactly as it sent them after the user edits an event, edits occurrences of a series or
 * replies to an invitation.
 *
 * RFC 5545 §3.2.20 requires applications to preserve the value data of value types they don't
 * recognize without interpreting it. Rebuilding these lines from decoded values loses escapes
 * (a `\n` becomes a real line break, so the next line turns into a bogus property), strips the
 * quotes from quoted parameters and collapses repeated properties to the last one. Several
 * servers refuse such a body (415, 400 or 403), which leaves the user's edit permanently unsynced.
 *
 * Each case drives the production chain: a server-created event, the real PullStrategy into a
 * real in-memory Room (so the scanner sees the bytes a REPORT delivers inside XML, not a plain
 * GET), the real EventWriter, the real PushStrategy, then a fresh GET. The expected lines are the
 * ones the server itself returned before the edit, so a server that normalizes what it stores
 * isn't blamed on the app.
 *
 * Safety: only the resources this run created are ever pushed or deleted. Before every push the
 * test checks that each queued operation belongs to this run's own event, and cleanup deletes
 * only the URLs its own create calls returned. iCloud is the owner's real calendar, so there the
 * calendar is never pulled ([bringIntoRoom]). Fixture addresses are example.test only;
 * failure-message bodies go through [FixtureRedactor].
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerUnknownPropertyRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerUnknownPropertyRoundTripTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val classStartMs = System.currentTimeMillis()
        private val UID_PREFIX = "unknown-prop-$classStartMs-"
        private const val DAY_MS = 86_400_000L
        // Three weeks ahead so strict servers don't reject "event in the past".
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 21) * DAY_MS + 9 * 3_600_000L

        private val icsUtc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private const val READER_ADDRESS = "unknown-prop-reader@example.test"

        /**
         * Shapes seen on real events: a structured location with a quoted
         * address holding an escaped newline and a comma plus a quoted title
         * holding a colon, a suggestion key with escaped comma and semicolon,
         * meeting-location JSON with an escaped comma, a multi-line COMMENT,
         * and properties that repeat.
         */
        private val SERIES_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"10600 N Tantau Ave\\nCupertino, CA 95014\";X-APPLE-RADIUS=70;X-TITLE=\"Apple Park: Visitor Center\":geo:37.332,-122.005",
            "X-APPLE-SUGGESTION-INFO-UNIQUE-KEY:mail\\,msg-1234\\;part\\=2",
            "X-MICROSOFT-LOCATIONS:[{\"DisplayName\":\"Room 1\\, Floor 2\"}]",
            "COMMENT:line one\\nline two\\, with comma",
            "RESOURCES:Projector",
            "RESOURCES:Whiteboard",
            "ATTACH;FMTTYPE=application/pdf:https://example.test/a.pdf",
            "ATTACH;FMTTYPE=application/pdf:https://example.test/b.pdf",
            "X-KASHCAL-PROBE:first",
            "X-KASHCAL-PROBE:second",
        )

        /** Different lines on the changed occurrence, so a mix-up with the series is visible. */
        private val OCCURRENCE_LINES = listOf(
            "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:1.0,2.0",
            "COMMENT:moved\\nfor this week only",
            "X-KASHCAL-OCCURRENCE:own",
        )

        /**
         * The property names the fixtures use that the app doesn't model. A
         * line split into a bogus property still fails the check, because its
         * original line goes missing.
         */
        private val UNKNOWN_NAMES = (SERIES_LINES + OCCURRENCE_LINES)
            .map { it.split(':', ';').first().uppercase() }.toSet()
    }

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var pullStrategy: PullStrategy
    private lateinit var pushStrategy: PushStrategy

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdEventUrls = mutableListOf<String>()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()

        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)

        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first; creds = it.second
        }

        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true)
        )

        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = AccountRepositoryImpl(
                accountsDao = database.accountsDao(),
                addressBookDao = database.addressBookDao(),
                calendarsDao = database.calendarsDao(),
                eventsDao = database.eventsDao(),
                pendingOperationsDao = database.pendingOperationsDao(),
                credentialManager = mockk(relaxed = true),
                reminderScheduler = mockk(relaxed = true),
                workManager = mockk(relaxed = true),
                contactSystemAccountRegistrar = mockk(relaxed = true),
                contactsProviderRepository = mockk(relaxed = true)
            ),
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )
    }

    @After
    fun cleanup() = runBlocking {
        client?.let { c ->
            for (url in createdEventUrls.reversed()) {
                try {
                    c.deleteEvent(url, c.fetchEtag(url).getOrNull())
                } catch (_: Exception) { /* best-effort */ }
            }
        }
        if (::database.isInitialized) database.close()
        unmockkAll()
    }

    // ---- editing a single event ----

    @Test
    fun `editing an event keeps every property the app does not model`() = runBlocking {
        val calendarUrl = readyCalendar()
        val uid = newUid("single")
        val url = createOnServer(calendarUrl, uid, vcalendar(vevent(uid, "Unknown props single", SERIES_LINES)))
        val before = fetch(url)

        val calendar = localCalendarFor(calendarUrl)
        bringIntoRoom(calendar, url, uid)
        val pulled = database.eventsDao().getByUid(uid).single()

        eventWriter.updateEvent(pulled.copy(title = "Unknown props single (edited)"), isLocal = false)
        assertOnlyOwnWorkQueued(uid)
        assertPushAccepted(pushStrategy.pushForCalendar(calendar, client!!))

        val after = fetch(url)
        assertTrue(
            "${config.name}: the title edit must reach the server: ${FixtureRedactor.redact(after)}",
            summaries(after).contains("Unknown props single (edited)")
        )
        assertSameUnknownLines("single event", masterBlock(before), masterBlock(after), after)
    }

    // ---- editing occurrences of a series ----

    @Test
    fun `editing occurrences of a series keeps each occurrence's own properties`() = runBlocking {
        val calendarUrl = readyCalendar()
        val uid = newUid("series")
        val changedOccurrenceMs = START_MS + 7 * DAY_MS
        val body = vcalendar(
            vevent(uid, "Unknown props series", SERIES_LINES, rrule = "FREQ=WEEKLY;COUNT=5"),
            vevent(uid, "Unknown props changed occurrence", OCCURRENCE_LINES, recurrenceIdMs = changedOccurrenceMs)
        )
        val url = createOnServer(calendarUrl, uid, body)
        val before = fetch(url)
        val beforeOccurrence = blockWithSummary(before, "Unknown props changed occurrence")
        assumeTrue(
            "${config.name}: server did not keep the changed occurrence: ${FixtureRedactor.redact(before)}",
            beforeOccurrence != null
        )

        val calendar = localCalendarFor(calendarUrl)
        bringIntoRoom(calendar, url, uid)
        val series = database.eventsDao().getByUid(uid).firstOrNull { it.originalEventId == null }
        assertTrue("${config.name}: series must be stored as recurring", series != null && series.isRecurring)

        // Re-edit the occurrence the server already holds, then change a
        // different one for the first time. Both go out in one PUT.
        eventWriter.editSingleOccurrence(
            masterEventId = series!!.id,
            occurrenceTimeMs = changedOccurrenceMs,
            modifiedEvent = series.copy(title = "Changed occurrence (edited)"),
            isLocal = false
        )
        eventWriter.editSingleOccurrence(
            masterEventId = series.id,
            occurrenceTimeMs = START_MS + 14 * DAY_MS,
            modifiedEvent = series.copy(title = "New occurrence (edited)"),
            isLocal = false
        )
        assertOnlyOwnWorkQueued(uid)
        assertPushAccepted(pushStrategy.pushForCalendar(calendar, client!!))

        val after = fetch(url)
        assertSameUnknownLines("series", masterBlock(before), masterBlock(after), after)
        assertSameUnknownLines(
            "re-edited occurrence",
            beforeOccurrence!!,
            requireBlock(after, "Changed occurrence (edited)"),
            after
        )
        // A newly changed occurrence starts as a copy of the series, so it
        // carries the series' properties, unchanged.
        assertSameUnknownLines(
            "newly changed occurrence",
            masterBlock(before),
            requireBlock(after, "New occurrence (edited)"),
            after
        )
    }

    // ---- replying to an invitation ----

    @Test
    fun `replying to an invitation keeps every property the app does not model`() = runBlocking {
        val calendarUrl = readyCalendar()
        val uid = newUid("reply")
        val invite = vevent(
            uid, "Unknown props invite", SERIES_LINES,
            extra = listOf(
                "ORGANIZER;CN=Organizer:mailto:unknown-prop-organizer@example.test",
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$READER_ADDRESS"
            )
        )
        val url = createOnServer(calendarUrl, uid, vcalendar(invite))
        val before = fetch(url)
        // Some servers route a synthetic organizer's attendees through
        // scheduling instead of storing them; there is nothing to reply to then.
        assumeTrue(
            "${config.name}: server did not keep the invited attendee",
            unfold(before).contains(READER_ADDRESS, ignoreCase = true)
        )

        val reader = Account(provider = AccountProvider.CALDAV, email = READER_ADDRESS)
        val reply = IcsPatcher.patchAttendeeReply(before, reader, "ACCEPTED")
        assertTrue("${config.name}: reply body must be built", reply != null)
        val etag = client!!.fetchEtag(url).getOrNull()
        val put = client!!.updateEvent(url, reply!!, etag ?: "")
        assertTrue(
            "${config.name}: reply PUT must be accepted, got " +
                ((put as? CalDavResult.Error)?.let { "${it.code} ${it.message}" } ?: "success"),
            put.isSuccess()
        )

        val after = fetch(url)
        assertSameUnknownLines("reply", masterBlock(before), masterBlock(after), after)
    }

    // ---- helpers ----

    private suspend fun readyCalendar(): String {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull()
        val home = principal?.let { c.discoverCalendarHome(it).getOrNull()?.firstOrNull() }
        val calendars = home?.let { c.listCalendars(it).getOrNull() }.orEmpty()
            .filter { !it.url.contains("inbox") && !it.url.contains("outbox") }
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
        val url = (calendars.firstOrNull { it.url.trimEnd('/').endsWith("/work") } ?: calendars.firstOrNull())?.url
        assumeTrue("No calendar found on ${config.name}", url != null)
        return url!!
    }

    private fun newUid(kind: String) = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}-$kind"

    private suspend fun createOnServer(calendarUrl: String, uid: String, body: String): String {
        val created = client!!.createEvent(calendarUrl, uid, body)
        assumeTrue(
            "create failed on ${config.name}: ${(created as? CalDavResult.Error)?.let { "${it.code} ${it.message}" }}",
            created.isSuccess()
        )
        return created.getOrNull()!!.first.also { createdEventUrls += it }
    }

    private suspend fun fetch(url: String): String = client!!.fetchEvent(url).getOrNull()!!.icalData

    /**
     * Gets this run's event into Room. Everywhere but iCloud that is the real
     * PullStrategy over the whole calendar. On iCloud, the owner's real
     * calendar, only this run's own resource is fetched, through the multiget
     * REPORT pull uses for changed hrefs, and stored the way pull stores it.
     */
    private suspend fun bringIntoRoom(calendar: Calendar, url: String, uid: String) {
        if (config.name == CalDavServerConfig.ICLOUD.name) {
            val href = java.net.URI(url).rawPath
            val fetched = client!!.fetchEventsByHref(calendar.caldavUrl, listOf(href)).getOrNull()?.singleOrNull()
            assertTrue("${config.name}: multiget must return this run's own event", fetched != null)
            storeOwnResource(calendar, url, uid, fetched!!.icalData, fetched.etag)
        } else {
            val result = pullStrategy.pull(calendar, forceFullSync = true, client = client!!)
            assumeTrue(
                "${config.name}: event did not pull into Room (pull=$result)",
                database.eventsDao().getByUid(uid).isNotEmpty()
            )
        }
    }

    /**
     * The pulled calendar may hold other events; the push must only carry
     * this run's own edit. Fails before pushing if anything else is queued.
     */
    private suspend fun assertOnlyOwnWorkQueued(uid: String) {
        val foreign = database.pendingOperationsDao().getAllOnce().filter { op ->
            database.eventsDao().getById(op.eventId)?.uid != uid
        }
        assertTrue("${config.name}: refusing to push work for other events: $foreign", foreign.isEmpty())
    }

    /**
     * Store this run's own resource the way pull does: every VEVENT row carries
     * the whole body, and changed occurrences are normalized against the series
     * DTSTART and linked to its occurrences.
     */
    private suspend fun storeOwnResource(calendar: Calendar, url: String, uid: String, body: String, etag: String?) {
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!
        assertTrue("${config.name}: the stored resource must be this run's own event", parsed.all { it.uid == uid })
        val seriesParsed = parsed.first { it.recurrenceId == null }
        val seriesId = database.eventsDao().insert(
            ICalEventMapper.toEntity(seriesParsed, body, calendar.id, url, etag).event
        )
        val series = database.eventsDao().getById(seriesId)!!
        if (series.isRecurring) occurrenceGenerator.regenerateOccurrences(series)
        parsed.filter { it.recurrenceId != null }.forEach { occurrence ->
            val row = ICalEventMapper.toEntity(
                occurrence, body, calendar.id, url, etag, masterDtStart = seriesParsed.dtStart
            ).event.copy(originalEventId = seriesId)
            val rowId = database.eventsDao().insert(row)
            occurrenceGenerator.linkException(seriesId, row.originalInstanceTime!!, database.eventsDao().getById(rowId)!!)
        }
    }

    private suspend fun localCalendarFor(calendarUrl: String): Calendar {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = "unknown-prop@example.test")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calendarUrl, displayName = "Unknown props", color = 0xFF0000FF.toInt())
        )
        return database.calendarsDao().getById(calendarId)!!
    }

    private fun vcalendar(vararg vevents: List<String>): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Unknown Property Round Trip//EN") +
            vevents.flatMap { it } + listOf("END:VCALENDAR", "")).joinToString("\r\n")

    private fun vevent(
        uid: String,
        summary: String,
        unknownLines: List<String>,
        rrule: String? = null,
        recurrenceIdMs: Long? = null,
        extra: List<String> = emptyList()
    ): List<String> {
        val start = recurrenceIdMs ?: START_MS
        return listOfNotNull(
            "BEGIN:VEVENT",
            "UID:$uid",
            "DTSTAMP:${icsUtc.format(Date(START_MS))}",
            recurrenceIdMs?.let { "RECURRENCE-ID:${icsUtc.format(Date(it))}" },
            "DTSTART:${icsUtc.format(Date(start))}",
            "DTEND:${icsUtc.format(Date(start + 3_600_000L))}",
            rrule?.let { "RRULE:$it" },
            "SUMMARY:$summary",
        ) + extra + unknownLines + "END:VEVENT"
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")

    /** Top-level property lines of each VEVENT (nested VALARMs skipped). */
    private fun veventBlocks(ics: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (line in unfold(ics).lines().map { it.trimEnd('\r') }) {
            when {
                current == null && line.equals("BEGIN:VEVENT", ignoreCase = true) -> { current = mutableListOf(); depth = 0 }
                current == null -> Unit
                line.equals("END:VEVENT", ignoreCase = true) && depth == 0 -> { blocks += current; current = null }
                line.startsWith("BEGIN:", ignoreCase = true) -> depth++
                line.startsWith("END:", ignoreCase = true) -> depth--
                depth == 0 && line.isNotBlank() -> current += line
            }
        }
        return blocks
    }

    private fun nameOf(line: String) = line.split(':', ';').first().uppercase()
    private fun unknownLines(block: List<String>) = block.filter { nameOf(it) in UNKNOWN_NAMES }
    private fun summaryOf(block: List<String>) =
        block.firstOrNull { nameOf(it) == "SUMMARY" }?.substringAfter(':')
    private fun summaries(ics: String) = veventBlocks(ics).mapNotNull { summaryOf(it) }

    private fun masterBlock(ics: String): List<String> =
        veventBlocks(ics).firstOrNull { block -> block.none { nameOf(it) == "RECURRENCE-ID" } }
            ?: error("${config.name}: no series VEVENT in ${FixtureRedactor.redact(ics)}")

    private fun blockWithSummary(ics: String, summary: String) =
        veventBlocks(ics).firstOrNull { summaryOf(it) == summary }

    private fun requireBlock(ics: String, summary: String): List<String> {
        val block = blockWithSummary(ics, summary)
        assertTrue("${config.name}: no VEVENT titled '$summary' in ${FixtureRedactor.redact(ics)}", block != null)
        return block!!
    }

    /** Same unknown lines, as a multiset: nothing dropped, changed or added. */
    private fun assertSameUnknownLines(what: String, expected: List<String>, actual: List<String>, body: String) {
        assertEquals(
            "${config.name}: $what must keep its unknown properties unchanged. Body after edit: " +
                FixtureRedactor.redact(body),
            unknownLines(expected).map(::serverNormalized).sorted(),
            unknownLines(actual).map(::serverNormalized).sorted()
        )
    }

    /**
     * SOGo rewrites parameters each time it stores an event, even when a client
     * sends back SOGo's own bytes unchanged (checked with plain PUT/GET, no app
     * involved): it doubles every backslash inside a quoted parameter value
     * (`X-ADDRESS="a\\nb, c"` comes back as `"a\\\\nb, c"`, then
     * `"a\\\\\\\\nb, c"`) and shuffles the parameter order between stores. So
     * for SOGo only, backslash runs inside quoted parameter values compare
     * equal and parameters compare as a set. Names, values and everything else
     * still have to match exactly, on SOGo too.
     */
    private fun serverNormalized(line: String): String {
        if (config.name != CalDavServerConfig.SOGO.name) return line
        val colon = splitOutsideQuotes(line, ':').first().length
        val head = line.substring(0, colon)
            .replace(Regex("\"[^\"]*\"")) { quoted -> quoted.value.replace(Regex("""\\+"""), Regex.escapeReplacement("\\")) }
        val parts = splitOutsideQuotes(head, ';')
        return (listOf(parts.first()) + parts.drop(1).sorted()).joinToString(";") + line.substring(colon)
    }

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == separator && !quoted -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    private fun assertPushAccepted(result: PushResult) {
        assertTrue("${config.name}: push must succeed, got $result", result is PushResult.Success)
        val success = result as PushResult.Success
        assertTrue(
            "${config.name}: the server refused the edit: ${success.pushErrors} ${success.pushWarnings}",
            success.pushErrors.isEmpty() && success.pushWarnings.isEmpty()
        )
        assertTrue("${config.name}: expected the update to be pushed, got $success", success.eventsUpdated >= 1)
    }
}
