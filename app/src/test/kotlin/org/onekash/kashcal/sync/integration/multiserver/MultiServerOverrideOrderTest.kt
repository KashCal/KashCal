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
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
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
import org.onekash.kashcal.sync.contacts.FakeContactsProviderRepository
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
 * A recurring series with one changed occurrence is one resource holding two
 * VEVENTs: the series and the changed occurrence (RECURRENCE-ID). RFC 5545 puts
 * no order on them, so the file a server hands back may list the changed
 * occurrence first. Editing the series must still keep the repeat rule and the
 * changed occurrence, whatever order the server stored.
 *
 * Each server gets the same series written three ways: series first, the changed
 * occurrence added in a second write, and the changed occurrence first. For each,
 * the test records the order the server returns and the order the pull stores,
 * then renames the series through the app (EventCoordinator + PushStrategy) and
 * reads the resource back.
 *
 * No attendees, so no invitations are sent. Each run uses a new UID and deletes
 * its resources afterwards.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration --tests '*MultiServerOverrideOrderTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerOverrideOrderTest(private val config: CalDavServerConfig) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> = CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val FUTURE_END_MS = 4_102_444_800_000L // Jan 1, 2100 UTC
    }

    private enum class Written { SERIES_FIRST, CHANGE_ADDED_LATER, CHANGE_FIRST }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private var calendarUrl: String? = null
    private val created = mutableListOf<String>()
    private val databases = mutableListOf<KashCalDatabase>()

    @Before
    fun setUp() {
        CalDavTestServerLoader.createClient(config)?.let { client = it.first; creds = it.second }
    }

    @After
    fun tearDown() = runBlocking {
        val c = client
        if (c != null) {
            for (url in created) {
                runCatching { c.deleteEvent(url, c.fetchEtag(url).getOrNull()) }
                runCatching { c.deleteEvent(url, null) }
            }
            val home = calendarUrl
            if (home != null) {
                val keys = created.map { it.substringAfterLast('/').substringBefore(".ics") }
                val listed = runCatching { c.fetchEtagsInRange(home, 0L, FUTURE_END_MS).getOrNull().orEmpty() }.getOrDefault(emptyList())
                for ((href, _) in listed.filter { (h, _) -> keys.any { h.contains(it) } }) {
                    runCatching { c.deleteEvent(resolve(home, href), null) }
                }
            }
        }
        databases.forEach { it.close() }
        unmockkAll()
    }

    @Test
    fun `editing a series keeps its repeat rule and changed occurrence whatever order the server stores`() = runBlocking<Unit> {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val url = discoverWritableCalendar()
        assumeTrue("${config.name}: no writable calendar", url != null)
        calendarUrl = url

        val failures = mutableListOf<String>()
        for (written in Written.values()) {
            val outcome = runCatching { roundTrip(written) }.getOrElse { "error ${it.javaClass.simpleName}: ${it.message}" }
            println(FixtureRedactor.redact("ORDER|${config.name}|$written|$outcome"))
            if (!outcome.startsWith("ok")) failures += "$written: $outcome"
        }
        assertTrue(FixtureRedactor.redact("${config.name}: $failures"), failures.isEmpty())
    }

    /**
     * Writes the series one way, pulls it, renames the series in the app, pushes and reads it
     * back. Returns an outcome line starting with "ok" when nothing was lost.
     */
    private suspend fun roundTrip(written: Written): String {
        val c = client!!
        val uid = "override-order-${UUID.randomUUID()}"
        val start = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS)
        val series = seriesVevent(uid, start)
        val change = changeVevent(uid, start)

        val resourceUrl: String = when (written) {
            Written.SERIES_FIRST -> put(uid, listOf(series, change))
            Written.CHANGE_FIRST -> put(uid, listOf(change, series))
            Written.CHANGE_ADDED_LATER -> {
                val u = put(uid, listOf(series))
                val etag = c.fetchEtag(u).getOrNull()
                val r = c.updateEvent(u, calendar(listOf(series, change)), etag ?: "")
                if (!r.isSuccess()) return "setup: second write refused: $r"
                u
            }
        }
        val served = c.fetchEvent(resourceUrl).getOrNull()?.icalData ?: return "setup: GET failed"
        val servedOrder = order(served)

        // The app's own pull into a fresh database.
        val db = newDatabase()
        val calendar = localCalendar(db, calendarUrl!!)
        val pulled = settled { pullStrategy(db).pull(calendar, forceFullSync = true, client = c); db.eventsDao().getByUid(uid) }
        val master = pulled.firstOrNull { it.originalEventId == null }
            ?: return "pull: no series row (server order $servedOrder, rows ${pulled.size})"
        val storedOrder = master.rawIcal?.let { order(it) } ?: "none"
        val exceptions = pulled.count { it.originalEventId != null }

        // Rename the whole series, as a user editing "All events" does, and push it.
        coordinator(db).updateEvent(master.copy(title = "Override order renamed", updatedAt = master.updatedAt + 1))
        val push = push(db).pushForCalendar(db.calendarsDao().getById(calendar.id)!!, c)
        val pushNote = when (push) {
            is PushResult.Success -> if (push.pushErrors.isEmpty()) "pushed" else "push errors ${push.pushErrors}"
            else -> "push $push"
        }

        val after = c.fetchEvent(db.eventsDao().getById(master.id)?.caldavUrl ?: resourceUrl).getOrNull()?.icalData
            ?: return "served $servedOrder, stored $storedOrder, $pushNote, GET after edit failed"
        val blocks = vevents(after)
        val seriesBlocks = blocks.filter { !it.contains("RECURRENCE-ID") }
        val changeBlocks = blocks.filter { it.contains("RECURRENCE-ID") }
        val problems = buildList {
            if (seriesBlocks.size != 1) add("${seriesBlocks.size} series VEVENTs")
            if (seriesBlocks.none { it.contains("RRULE:") }) add("series has no RRULE")
            if (seriesBlocks.none { it.contains("SUMMARY:Override order renamed") }) add("series not renamed")
            if (changeBlocks.size != 1) add("${changeBlocks.size} changed-occurrence VEVENTs")
            if (changeBlocks.any { it.contains("RRULE:") }) add("changed occurrence carries an RRULE")
            if (changeBlocks.none { it.contains("SUMMARY:Override order moved") }) add("changed occurrence lost its title")
            if (pushNote != "pushed") add(pushNote)
        }
        val facts = "served $servedOrder, stored $storedOrder, exceptions $exceptions, after ${order(after)}"
        return if (problems.isEmpty()) "ok ($facts)" else "BROKEN ($facts): ${problems.joinToString(", ")}"
    }

    // ---- the resource ----

    private fun seriesVevent(uid: String, start: Instant) = listOf(
        "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${UTC.format(Instant.now())}",
        "DTSTART:${UTC.format(start)}", "DTEND:${UTC.format(start.plus(1, ChronoUnit.HOURS))}",
        "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:Override order probe", "END:VEVENT",
    )

    /** The third occurrence, moved two hours later and retitled. */
    private fun changeVevent(uid: String, start: Instant): List<String> {
        val original = start.plus(2, ChronoUnit.DAYS)
        val moved = original.plus(2, ChronoUnit.HOURS)
        return listOf(
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${UTC.format(Instant.now())}",
            "RECURRENCE-ID:${UTC.format(original)}",
            "DTSTART:${UTC.format(moved)}", "DTEND:${UTC.format(moved.plus(1, ChronoUnit.HOURS))}",
            "SUMMARY:Override order moved", "END:VEVENT",
        )
    }

    private fun calendar(components: List<List<String>>): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KashCal//Override order//EN") +
            components.flatten() + listOf("END:VCALENDAR", "")).joinToString("\r\n")

    private suspend fun put(uid: String, components: List<List<String>>): String {
        val r = client!!.createEvent(calendarUrl!!, uid, calendar(components))
        val url = r.getOrNull()?.first ?: error("create refused: $r")
        created += url
        return url
    }

    /** Returns the order of the VEVENTs in a body, e.g. "series,change". */
    private fun order(ics: String): String =
        vevents(ics).joinToString(",") { if (it.contains("RECURRENCE-ID")) "change" else "series" }

    private fun vevents(ics: String): List<String> {
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        return Regex("""BEGIN:VEVENT.*?END:VEVENT""", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).map { it.value }.toList()
    }

    // ---- the app ----

    private suspend fun discoverWritableCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) c.discoverWellKnown(endpoint).getOrNull() ?: endpoint else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull().orEmpty()
            .filter { !it.isReadOnly && (it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents) }
            .map { it.url }
            .firstOrNull { !it.contains("inbox") && !it.contains("outbox") }
    }

    /** Zoho lists a new resource a few seconds late: re-read until it shows or a minute passes. */
    private suspend fun <T> settled(read: suspend () -> List<T>): List<T> {
        val deadline = System.currentTimeMillis() + 60_000
        var got = read()
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            got = read()
        }
        return got
    }

    private fun newDatabase(): KashCalDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
            .also { databases += it }
    }

    private suspend fun localCalendar(db: KashCalDatabase, url: String): Calendar {
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "override-order@example.test"))
        val id = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = url, displayName = "Override order", color = 0))
        return db.calendarsDao().getById(id)!!
    }

    private fun resolve(calendarUrl: String, href: String): String =
        if (href.startsWith("http")) href else URI(calendarUrl).resolve(href).toString()

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

    private fun pullStrategy(db: KashCalDatabase): PullStrategy {
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
            defaultQuirks = config.quirksFactory(creds?.serverUrl ?: config.defaultServerUrl ?: ""),
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
        // Never read here: the test hands the client its credentials itself.
        credentialManager = mockk(),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = FakeContactsProviderRepository(),
    )
}
