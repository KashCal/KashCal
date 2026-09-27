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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Answering a recurring invite changes only this account's answer on the
 * server, and an answer to one changed occurrence reaches it.
 *
 * The invite is a daily series (COUNT=5) plus its third occurrence moved two
 * hours later and retitled, both with this account as a NEEDS-ACTION attendee,
 * written series first (the order every tested server and web UI produces).
 *
 * - In the account's own calendar (every server): an organizer at an
 *   `@example.test` address, the file written straight into the calendar, as it
 *   is after a server or another client files an invite there. Three answers:
 *   on the series, on the changed occurrence, and on the series then the
 *   occurrence in one sync.
 * - Delivered by the server (servers with a second test user and server-side
 *   scheduling): the first test user organizes, the server delivers the invite
 *   to the second, and KashCal answers the series as the second user; the
 *   organizer's copy must then show the real answer and an unchanged event.
 *
 * KashCal stores the resource the way pull does, answers through
 * EventCoordinator.replyRsvp and pushes; the resource is then read back. On the
 * real accounts (iCloud, Zoho, Mailbox) only this run's own resource is fetched
 * into the database, never the calendar, and every push first checks that only
 * this run's answers are queued. `@example.test` addresses can't be delivered,
 * so no mail leaves. Each case uses a new UID and deletes only what it made.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerRecurringInviteReplyTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerRecurringInviteReplyTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val ORGANIZER = "mailto:recurring-invite-organizer@example.test"
        private const val SYNTHETIC_SELF = "mailto:recurring-invite-self@example.test"
        private const val SERIES_TITLE = "Recurring invite probe"
        private const val MOVED_TITLE = "Recurring invite probe moved"

        /** The owner's real accounts: only this run's own resource may enter the database. */
        private val REAL_ACCOUNTS = setOf(
            CalDavServerConfig.ICLOUD.name, CalDavServerConfig.ZOHO.name, CalDavServerConfig.MAILBOX.name
        )

        /** Servers whose second test user can receive a server-delivered invite. */
        private val SECOND_USER = mapOf(
            "Nextcloud" to ("NEXTCLOUD_USERNAME_2" to "NEXTCLOUD_PASSWORD_2"),
            "SOGo" to ("SOGO_USERNAME_2" to "SOGO_PASSWORD_2"),
        )
    }

    /**
     * One logged-in user: client, credentials, own address, first writable calendar and all
     * writable calendars.
     */
    private class User(val client: CalDavClient, val creds: ServerCredentials, val address: String?, val calendarUrl: String, val calendars: List<String>)

    /** The invite's times: series start, the changed occurrence's original and moved start. */
    private class Times(val start: Instant) {
        val original: Instant = start.plus(2, ChronoUnit.DAYS)
        val moved: Instant = original.plus(2, ChronoUnit.HOURS)
    }

    private val cleanup = mutableListOf<Pair<CalDavClient, String>>()
    private val databases = mutableListOf<KashCalDatabase>()

    @Before
    fun notFastmail() {
        assumeTrue("Fastmail is not covered by this suite", config.name != CalDavServerConfig.FASTMAIL.name)
    }

    @After
    fun tearDown() = runBlocking {
        // Only URLs this run's own create returned, or the delivered copy found by
        // this run's full UID. Organizer first: deleting it makes the server cancel
        // the attendee's copy, which it may rewrite in place.
        for ((c, url) in cleanup) {
            runCatching { c.deleteEvent(url, c.fetchEtag(url).getOrNull()) }
            runCatching { c.deleteEvent(url, null) }
        }
        databases.forEach { it.close() }
        unmockkAll()
    }

    // ---- the invite filed in the account's own calendar ----

    @Test
    fun `answering the series keeps the changed occurrence as the organizer made it`() = runBlocking<Unit> {
        val case = ownCalendarInvite()
        val after = case.answer(listOf(Answer.SERIES to "ACCEPTED"))
        val found = problems(after, case.times) + answers(after, case.self, series = "ACCEPTED", change = "NEEDS-ACTION")
        report("own calendar|answer series", after, case.self, found)
    }

    @Test
    fun `answering the changed occurrence reaches the server and changes only it`() = runBlocking<Unit> {
        val case = ownCalendarInvite()
        val after = case.answer(listOf(Answer.CHANGE to "ACCEPTED"))
        val found = problems(after, case.times) + answers(after, case.self, series = "NEEDS-ACTION", change = "ACCEPTED")
        report("own calendar|answer changed occurrence", after, case.self, found)
    }

    @Test
    fun `answering the series and then the changed occurrence keeps both answers`() = runBlocking<Unit> {
        val case = ownCalendarInvite()
        val after = case.answer(listOf(Answer.SERIES to "ACCEPTED", Answer.CHANGE to "TENTATIVE"))
        val found = problems(after, case.times) + answers(after, case.self, series = "ACCEPTED", change = "TENTATIVE")
        report("own calendar|answer series then changed occurrence", after, case.self, found)
    }

    // ---- the invite delivered by the server ----

    @Test
    fun `answering a delivered invite leaves the organizer an unchanged event with the real answer`() = runBlocking<Unit> {
        val keys = SECOND_USER[config.name]
        assumeTrue("${config.name}: no second test user for server-delivered invites", keys != null)
        val organizer = login(config)
        val attendee = login(config.copy(usernameKey = keys!!.first, passwordKey = keys.second))
        assumeTrue("${config.name}: users have no calendar addresses", organizer.address != null && attendee.address != null)

        val uid = "recurring-invite-${UUID.randomUUID()}"
        val times = Times(start())
        val created = organizer.client.createEvent(
            organizer.calendarUrl, uid, invite(uid, times, organizer.address!!, attendee.address!!, organizerIsAttendee = true)
        )
        val organizerUrl = created.getOrNull()?.first
        assumeTrue(FixtureRedactor.redact("${config.name}: organizer create refused: $created"), organizerUrl != null)
        cleanup += organizer.client to organizerUrl!!

        val found = settled { findByUid(attendee, uid) }
        assumeTrue("${config.name}: the server delivered no copy to the attendee", found != null)
        val (deliveredCalendar, delivered) = found!!
        cleanup += attendee.client to delivered

        val case = InviteCase(attendee, attendee.address, uid, delivered, times, deliveredCalendar)
        val after = case.answer(listOf(Answer.SERIES to "ACCEPTED"))
        val problems = problems(after, times) + answers(after, attendee.address, series = "ACCEPTED", change = "NEEDS-ACTION")

        // The server turns the answer into a reply to the organizer; give it time to land.
        val organizerAfter = settled {
            organizer.client.fetchEvent(organizerUrl).getOrNull()?.icalData
                ?.takeIf { partstatOf(it, attendee.address)[0] == "ACCEPTED" }
        } ?: organizer.client.fetchEvent(organizerUrl).getOrNull()?.icalData.orEmpty()
        val organizerProblems = problems(organizerAfter, times) +
            answers(organizerAfter, attendee.address, series = "ACCEPTED", change = "NEEDS-ACTION")
        println(
            FixtureRedactor.redact(
                "REPLY|${config.name}|delivered|answer series|attendee ${describe(after, attendee.address)}" +
                    "|organizer ${describe(organizerAfter, attendee.address)}|problems ${problems + organizerProblems.map { "organizer: $it" }}"
            )
        )
        assertTrue(
            FixtureRedactor.redact("${config.name}: attendee $problems; organizer $organizerProblems"),
            problems.isEmpty() && organizerProblems.isEmpty(),
        )
    }

    // ---- one invite and its answers ----

    private enum class Answer { SERIES, CHANGE }

    private inner class InviteCase(
        val user: User,
        val self: String,
        val uid: String,
        val url: String,
        val times: Times,
        val calendarUrl: String = user.calendarUrl,
    ) {
        /**
         * Stores the resource the way pull does, answers each VEVENT in
         * [answers] in order through the app, pushes once, and returns the
         * resource as the server then holds it. Skips, never fails, when the
         * server changed the invite at setup, as Zoho does.
         */
        suspend fun answer(answers: List<Pair<Answer, String>>): String {
            val before = user.client.fetchEvent(url).getOrNull()?.icalData
            assumeTrue(FixtureRedactor.redact("${config.name}: setup GET failed"), before != null)
            val setup = problems(before!!, times)
            assumeTrue(FixtureRedactor.redact("${config.name}: the server changed the invite at setup: $setup"), setup.isEmpty())
            assumeTrue(
                FixtureRedactor.redact("${config.name}: the server doesn't keep this account as a NEEDS-ACTION attendee (${describe(before, self)})"),
                partstatOf(before, self).all { it == "NEEDS-ACTION" }
            )

            val db = newDatabase()
            val calendar = localCalendar(db, calendarUrl, self)
            val rows = bringIntoRoom(db, calendar, user) ?: throw AssertionError(FixtureRedactor.redact("${config.name}: nothing stored for the invite"))
            val coordinator = coordinator(db)
            for ((on, partstat) in answers) {
                val target: Event = when (on) {
                    Answer.SERIES -> rows.firstOrNull { it.originalEventId == null }
                    Answer.CHANGE -> rows.firstOrNull { it.originalEventId != null }
                } ?: throw AssertionError(FixtureRedactor.redact("${config.name}: no $on row (${rows.size} rows)"))
                assertTrue(
                    FixtureRedactor.redact("${config.name}: the app didn't find this account among the $on attendees"),
                    coordinator.replyRsvp(target.id, partstat)
                )
            }

            assertOnlyOwnWorkQueued(db)
            val push = push(db).pushForCalendar(db.calendarsDao().getById(calendar.id)!!, user.client)
            assertTrue(
                FixtureRedactor.redact("${config.name}: push $push"),
                push is PushResult.Success && push.pushErrors.isEmpty()
            )
            return user.client.fetchEvent(url).getOrNull()?.icalData
                ?: throw AssertionError(FixtureRedactor.redact("${config.name}: GET after the answer failed"))
        }

        /**
         * Gets the invite into Room. On the real accounts only this run's own
         * resource is fetched (with the multiget pull uses for changed hrefs)
         * and stored the way pull stores it; elsewhere the real PullStrategy
         * runs over the whole calendar.
         */
        private suspend fun bringIntoRoom(db: KashCalDatabase, calendar: Calendar, user: User): List<Event>? {
            if (config.name in REAL_ACCOUNTS) {
                val fetched = user.client.fetchEventsByHref(calendar.caldavUrl, listOf(URI(url).rawPath)).getOrNull()?.singleOrNull()
                assertTrue(FixtureRedactor.redact("${config.name}: multiget must return this run's own event"), fetched != null)
                storeOwnResource(db, calendar, fetched!!.icalData, fetched.etag)
            } else {
                pullStrategy(db, user).pull(calendar, forceFullSync = true, client = user.client)
            }
            return settled { db.eventsDao().getByUid(uid).ifEmpty { null } }
        }

        /**
         * Stores this run's own resource the way pull does: every VEVENT row
         * carries the whole body with its attendees, and the changed occurrence
         * is normalized against the series DTSTART and linked to its occurrence.
         */
        private suspend fun storeOwnResource(db: KashCalDatabase, calendar: Calendar, body: String, etag: String?) {
            val parsed = ICalParser().parseAllEvents(body).getOrNull()!!
            assertTrue(FixtureRedactor.redact("${config.name}: the stored resource must be this run's own event"), parsed.all { it.uid == uid })
            val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
            val seriesParsed = parsed.first { it.recurrenceId == null }
            val series = ICalEventMapper.toEntity(seriesParsed, body, calendar.id, url, etag)
            val seriesId = db.eventsDao().insert(series.event)
            db.attendeesDao().replaceForEvent(seriesId, series.attendees.map { it.copy(eventId = seriesId) })
            generator.regenerateOccurrences(db.eventsDao().getById(seriesId)!!)
            for (occurrence in parsed.filter { it.recurrenceId != null }) {
                val mapped = ICalEventMapper.toEntity(occurrence, body, calendar.id, url, etag, masterDtStart = seriesParsed.dtStart)
                val rowId = db.eventsDao().insert(
                    mapped.event.copy(originalEventId = seriesId, originalSyncId = uid)
                )
                db.attendeesDao().replaceForEvent(rowId, mapped.attendees.map { it.copy(eventId = rowId) })
                generator.linkException(seriesId, mapped.event.originalInstanceTime!!, db.eventsDao().getById(rowId)!!)
            }
        }

        /**
         * Fails unless every queued operation is for this run's UID: the pulled calendar may
         * hold other events.
         */
        private suspend fun assertOnlyOwnWorkQueued(db: KashCalDatabase) {
            val foreign = db.pendingOperationsDao().getAllOnce().filter { op ->
                db.eventsDao().getById(op.eventId)?.uid != uid
            }
            assertTrue(FixtureRedactor.redact("${config.name}: refusing to push work for other events: ${foreign.map { it.id }}"), foreign.isEmpty())
        }
    }

    private suspend fun ownCalendarInvite(): InviteCase {
        val me = login(config)
        val self = me.address ?: SYNTHETIC_SELF
        val uid = "recurring-invite-${UUID.randomUUID()}"
        val times = Times(start())
        val created = me.client.createEvent(me.calendarUrl, uid, invite(uid, times, ORGANIZER, self))
        val url = created.getOrNull()?.first
        assumeTrue(FixtureRedactor.redact("${config.name}: setup create refused: $created"), url != null)
        cleanup += me.client to url!!
        return InviteCase(me, self, uid, url, times)
    }

    private fun report(case: String, after: String, self: String, found: List<String>) {
        println(FixtureRedactor.redact("REPLY|${config.name}|$case|${describe(after, self)}|problems $found"))
        assertTrue(FixtureRedactor.redact("${config.name} $case: $found (${describe(after, self)})"), found.isEmpty())
    }

    // ---- the invite ----

    private fun start(): Instant = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS)

    private fun invite(uid: String, times: Times, organizer: String, attendee: String, organizerIsAttendee: Boolean = false): String {
        fun people() = buildList {
            add("ORGANIZER:$organizer")
            if (organizerIsAttendee) add("ATTENDEE;ROLE=CHAIR;PARTSTAT=ACCEPTED:$organizer")
            add("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:$attendee")
        }
        val series = listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${UTC.format(Instant.now())}",
            "DTSTART:${UTC.format(times.start)}", "DTEND:${UTC.format(times.start.plus(1, ChronoUnit.HOURS))}",
            "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:$SERIES_TITLE", "SEQUENCE:0") + people() + "END:VEVENT"
        val change = listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${UTC.format(Instant.now())}",
            "RECURRENCE-ID:${UTC.format(times.original)}",
            "DTSTART:${UTC.format(times.moved)}", "DTEND:${UTC.format(times.moved.plus(1, ChronoUnit.HOURS))}",
            "SUMMARY:$MOVED_TITLE", "SEQUENCE:0") + people() + "END:VEVENT"
        return (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Recurring invite probe//EN") +
            series + change + listOf("END:VCALENDAR", "")).joinToString("\r\n")
    }

    // ---- reading a resource ----

    private fun vevents(ics: String): List<String> {
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        return Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).map { it.value }.toList()
    }

    private fun order(ics: String) = vevents(ics).joinToString(",") { if (it.contains("RECURRENCE-ID")) "change" else "series" }

    /** Returns [self]'s PARTSTAT on the series and on the changed occurrence, in that order. */
    private fun partstatOf(ics: String, self: String): List<String?> {
        val key = self.removePrefix("mailto:").lowercase()
        fun of(block: String?) = block?.lines()?.firstOrNull { it.startsWith("ATTENDEE") && it.lowercase().contains(key) }
            ?.let { Regex("""PARTSTAT=([A-Z-]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase() }
        val blocks = vevents(ics)
        return listOf(of(blocks.firstOrNull { !it.contains("RECURRENCE-ID") }), of(blocks.firstOrNull { it.contains("RECURRENCE-ID") }))
    }

    private fun answers(ics: String, self: String, series: String, change: String): List<String> {
        val (s, c) = partstatOf(ics, self).let { it[0] to it[1] }
        return buildList {
            if (s != series) add("series answer $s, expected $series")
            if (c != change) add("changed-occurrence answer $c, expected $change")
        }
    }

    /**
     * Lists how [ics] differs from what the organizer made: one series with its repeat rule,
     * one changed occurrence moved and retitled.
     */
    private fun problems(ics: String, times: Times): List<String> {
        val blocks = vevents(ics)
        val series = blocks.filter { !it.contains("RECURRENCE-ID") }
        val changes = blocks.filter { it.contains("RECURRENCE-ID") }
        return buildList {
            if (series.size != 1) add("${series.size} series VEVENTs")
            if (series.none { it.contains("RRULE:") }) add("series has no RRULE")
            if (changes.size != 1) add("${changes.size} changed-occurrence VEVENTs (moved occurrence lost)")
            if (changes.isNotEmpty() && changes.none { it.contains("SUMMARY:$MOVED_TITLE") }) add("changed occurrence lost its title")
            val parsedChange = ICalParser().parseAllEvents(ics).getOrNull().orEmpty().firstOrNull { it.recurrenceId != null }
            if (parsedChange != null) {
                if (parsedChange.recurrenceId?.timestamp != times.original.toEpochMilli()) add("changed occurrence's RECURRENCE-ID changed")
                if (parsedChange.dtStart.timestamp != times.moved.toEpochMilli()) add("changed occurrence lost its moved time")
            }
        }
    }

    private fun describe(ics: String, self: String?): String {
        val p = self?.let { partstatOf(ics, it) } ?: listOf(null, null)
        return "order ${order(ics)}, self series=${p[0]} change=${p[1]}"
    }

    // ---- logging in ----

    private suspend fun login(cfg: CalDavServerConfig): User {
        val pair = CalDavTestServerLoader.createClient(cfg)
        assumeTrue("${cfg.name} credentials not available", pair != null)
        val (client, creds) = pair!!
        assumeTrue("${cfg.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds.davEndpoint))
        val endpoint = creds.davEndpoint
        val caldavUrl = if (cfg.usesWellKnownDiscovery) client.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        assumeTrue("${cfg.name}: no principal", principal != null)
        val address = client.discoverCalendarUserAddresses(principal!!).getOrNull().orEmpty()
            .firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull()
        assumeTrue("${cfg.name}: no calendar home", home != null)
        val writable = client.listCalendars(home!!).getOrNull().orEmpty()
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
            .map { it.url }.filter { !it.contains("inbox") && !it.contains("outbox") }
        assumeTrue("${cfg.name}: no writable calendar", writable.isNotEmpty())
        return User(client, creds, address, writable.first(), writable)
    }

    /** Returns the calendar and resource URL holding [uid] among [user]'s calendars, or null. */
    private suspend fun findByUid(user: User, uid: String): Pair<String, String>? {
        for (calendar in user.calendars) {
            // A server may file a delivered invite under a name of its own, so match the full
            // UID in the data.
            val href = user.client.fetchEventsInRange(calendar, 0L, 4_102_444_800_000L).getOrNull().orEmpty()
                .firstOrNull { it.icalData.contains("UID:$uid") }?.url ?: continue
            return calendar to if (href.startsWith("http")) href else URI(calendar).resolve(href).toString()
        }
        return null
    }

    /**
     * Re-reads until non-null or a minute passes: some servers file or list a change a few
     * seconds late.
     */
    private suspend fun <T> settled(read: suspend () -> T?): T? {
        val deadline = System.currentTimeMillis() + 60_000
        var got = read()
        while (got == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = read()
        }
        return got
    }

    // ---- the app ----

    private fun newDatabase(): KashCalDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
            .also { databases += it }
    }

    private suspend fun localCalendar(db: KashCalDatabase, url: String, self: String): Calendar {
        val accountId = db.accountsDao().insert(
            Account(provider = AccountProvider.CALDAV, email = self.removePrefix("mailto:"), calendarUserAddresses = listOf(self)),
        )
        val id = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Recurring invite", color = 0))
        return db.calendarsDao().getById(id)!!
    }

    private fun coordinator(db: KashCalDatabase): EventCoordinator {
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        return EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accountRepository(db),
            // Side-effect collaborators only: the test pushes and pulls itself.
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
    }

    private fun push(db: KashCalDatabase) = PushStrategy(
        calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        accountRepository = accountRepository(db),
        attendeesDao = db.attendeesDao(),
        pendingCancelsDao = db.pendingCancelsDao(),
    )

    private fun pullStrategy(db: KashCalDatabase, user: User): PullStrategy {
        val dataStore = mockk<KashCalDataStore>(relaxed = true)
        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        return PullStrategy(
            database = db,
            calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
            eventsDao = db.eventsDao(),
            attendeesDao = db.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()),
            defaultQuirks = config.quirksFactory(user.creds.serverUrl),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = accountRepository(db),
            reminderScheduler = mockk(relaxed = true),
        )
    }

    private fun accountRepository(db: KashCalDatabase): AccountRepository = AccountRepositoryImpl(
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
