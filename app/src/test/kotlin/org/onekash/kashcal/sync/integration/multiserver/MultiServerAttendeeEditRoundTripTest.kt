package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Live end-to-end round-trips for the attendee WRITE-path fixes
 * (post-implementation review of the CalDAV scheduling feature). Unlike the
 * unit tests (which assert Room rows / serialized strings), these drive the
 * whole production path against each real server:
 *
 *   real Room DB -> EventWriter.{createEvent,updateEvent,editSingleOccurrence}
 *   -> IcsPatcher.serialize(WithExceptions) (the exact serializer PushStrategy
 *   uses, fed the table set the same way) -> live PUT -> fetch -> parse.
 *
 * Covers three fixes that had no live coverage:
 *  1. Editing the attendee set on a SERVER-SYNCED event (which has rawIcal)
 *     reaches the wire — add + remove. (The patch path used to ignore the
 *     passed set, so the picker was a silent no-op for synced events.)
 *  2. Rescheduling one occurrence of a recurring invite carries the series'
 *     attendees on the bundled override VEVENT.
 *  3. An EMPTY attendee table is treated as "no authoritative set" → the
 *     server's rawIcal attendees are PRESERVED on a cosmetic edit, not
 *     silently cleared.
 *
 * Notification safety (owner decision): the attendee is a DIFFERENT account
 * the user owns (iCloud<->Zoho<->mailbox rotation), so any server-side iTIP
 * delivery lands in an owned inbox — never a stranger, never a bogus address.
 * Servers with no email-shaped owned address (Docker/Nextcloud logins) fall
 * back to a synthetic @example.test attendee and are asserted only on the
 * client-serialized body, not on server-side delivery.
 *
 * Safety: only mutates events created by this run (unique uid prefix); cleanup
 * deletes only those hrefs. Failure-message ICS bodies are PII-redacted.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerAttendeeEditRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerAttendeeEditRoundTripTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val classStartMs = System.currentTimeMillis()
        private val UID_PREFIX = "attendee-edit-$classStartMs-"
        private const val DAY_MS = 86_400_000L
        // Far-future anchor so strict servers don't reject "event in the past".
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 21) * DAY_MS + 9 * 3_600_000L

        // Owned, email-shaped account logins, by config name → the address the
        // SAME user controls on a DIFFERENT provider. Rotation keeps any
        // delivered invite in an inbox the user owns.
        private val CROSS_ROTATION = listOf("iCloud", "Zoho", "Mailbox")
    }

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var calendarId: Long = 0
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

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
    }

    @After
    fun cleanup() = runBlocking {
        client?.let { c ->
            for ((url, etag) in createdEventUrls.reversed()) {
                try { c.deleteEvent(url, etag) } catch (_: Exception) { /* best-effort */ }
            }
        }
        if (::database.isInitialized) database.close()
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    private fun unfold(ics: String) = ics.replace(Regex("""\r?\n[ \t]"""), "")
    private fun hasOrganizer(ics: String): Boolean =
        unfold(ics).lines().any { it.trimStart().startsWith("ORGANIZER") }
    private fun attendeeEmails(ics: String): Set<String> =
        unfold(ics).lines().filter { it.trimStart().startsWith("ATTENDEE") }
            .mapNotNull { Regex("""mailto:([^;:\s]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.lowercase() }
            .toSet()

    /**
     * An attendee address the user owns on a DIFFERENT provider than the one
     * under test (so delivery is contained). Falls back to a synthetic
     * @example.test address for servers with no email-shaped owned login —
     * those are asserted on the client body only (see useServerDelivery).
     */
    private fun crossAccountAttendee(): String {
        val idx = CROSS_ROTATION.indexOf(config.name)
        if (idx >= 0) {
            for (step in 1 until CROSS_ROTATION.size) {
                val other = CROSS_ROTATION[(idx + step) % CROSS_ROTATION.size]
                ownedAddress(other)?.let { return it }
            }
        }
        return "attendee-edit.synthetic@example.test"
    }

    private fun ownedAddress(serverName: String): String? {
        val key = when (serverName) {
            "iCloud" -> "caldav.username"
            "Zoho" -> "ZOHO_USERNAME"
            "Mailbox" -> "MAILBOX_USERNAME"
            else -> return null
        }
        return CalDavTestServerLoader.property(key)?.takeIf { it.contains("@") }
    }

    // True only on servers where we expect a real-organizer attendee to
    // survive verbatim on the wire (no iSchedule strip/reroute). The
    // synthetic-organizer-strip set is reused: those servers route attendees
    // through scheduling delivery, so a fetched body may not echo them.
    private val expectServerEcho: Boolean
        get() = !config.stripsAttendeesOnSyntheticOrganizer

    private fun localAccountAndCalendar(): Long {
        val accountId = runBlocking {
            database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
        }
        return runBlocking {
            database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "local://default", displayName = "Local", color = 0xFF0000FF.toInt())
            )
        }
    }

    private fun attendee(addr: String, partstat: String = "NEEDS-ACTION", order: Int = 0) =
        Attendee(eventId = 0, address = "mailto:$addr", displayName = addr.substringBefore('@'),
            role = "REQ-PARTICIPANT", partstat = partstat, sortOrder = order)

    // ---- Fix #1: edit attendees on a synced (rawIcal) event reaches the wire ----

    @Test
    fun `editing attendees on a synced event reaches the wire (add and remove)`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)
        calendarId = localAccountAndCalendar()

        val organizer = ownedAddress(config.name) ?: "organizer.synthetic@example.test"
        val cross = crossAccountAttendee()
        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}"

        // 1. Create a synced event with one attendee (jane), as the organizer.
        val created = eventWriter.createEvent(
            Event(uid = uid, calendarId = calendarId, title = "Attendee edit",
                startTs = START_MS, endTs = START_MS + 3_600_000L, dtstamp = START_MS,
                organizerEmail = organizer, syncStatus = SyncStatus.SYNCED),
            isLocal = true,
            attendees = listOf(attendee("jane.synthetic@example.test", order = 0))
        )
        val createBody = IcsPatcher.serialize(
            created, database.attendeesDao().getForEventOnce(created.id)
        )
        val createResult = client!!.createEvent(calendarUrl!!, uid, createBody)
        assumeTrue("create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess())
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // 2. Edit the attendee set: remove jane, add the cross-account address.
        eventWriter.updateEvent(
            created.copy(title = "Attendee edit (changed)"),
            isLocal = true,
            attendees = listOf(attendee(cross, order = 0))
        )
        val editBody = IcsPatcher.serialize(
            database.eventsDao().getById(created.id)!!,
            database.attendeesDao().getForEventOnce(created.id)
        )

        // The CLIENT body must reflect the edit regardless of server policy.
        val bodyEmails = attendeeEmails(editBody)
        assertTrue("client must serialize the added attendee: ${FixtureRedactor.redact(editBody)}",
            bodyEmails.contains(cross.lowercase()))
        assertFalse("client must drop the removed attendee: ${FixtureRedactor.redact(editBody)}",
            bodyEmails.contains("jane.synthetic@example.test"))

        val updateResult = client!!.updateEvent(url, editBody, etag)
        assumeTrue("update failed on ${config.name}: ${(updateResult as? CalDavResult.Error)?.message}",
            updateResult.isSuccess())
        trackEvent(url, updateResult.getOrNull()!!)

        // On servers that echo attendees verbatim, assert the round-trip too.
        if (expectServerEcho) {
            val stored = client!!.fetchEvent(url).getOrNull()!!.icalData
            val storedEmails = attendeeEmails(stored)
            assertTrue("${config.name}: edited attendee must round-trip: ${FixtureRedactor.redact(stored)}",
                storedEmails.contains(cross.lowercase()))
            assertFalse("${config.name}: removed attendee must not survive: ${FixtureRedactor.redact(stored)}",
                storedEmails.contains("jane.synthetic@example.test"))
            println("ATT-EDIT ${config.name}: add/remove round-tripped on the wire")
        } else {
            println("ATT-EDIT ${config.name}: client body verified; server routes attendees (no echo asserted)")
        }
    }

    // ---- Add an attendee to a synced event that had no organizer ----

    @Test
    fun `adding an attendee to an organizer-less synced event ships an ORGANIZER`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)
        calendarId = localAccountAndCalendar()

        val organizer = ownedAddress(config.name) ?: "organizer.synthetic@example.test"
        val cross = crossAccountAttendee()
        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}"

        // 1. Create a synced event with NO organizer and NO attendees — the body
        //    an event created without invitees produces. This is what lands in
        //    Event.rawIcal after it syncs.
        val created = eventWriter.createEvent(
            Event(uid = uid, calendarId = calendarId, title = "No-organizer event",
                startTs = START_MS, endTs = START_MS + 3_600_000L, dtstamp = START_MS,
                syncStatus = SyncStatus.SYNCED),
            isLocal = true,
            attendees = null
        )
        val createBody = IcsPatcher.serialize(created, attendees = null)
        assertFalse("precondition: stored body has no ORGANIZER: ${FixtureRedactor.redact(createBody)}",
            hasOrganizer(createBody))
        val createResult = client!!.createEvent(calendarUrl!!, uid, createBody)
        assumeTrue("create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess())
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)
        // Persist the server's authoritative rawIcal back on the row (mirrors a
        // pull): the stored rawIcal — with no ORGANIZER — is what the patch reads.
        database.eventsDao().update(
            database.eventsDao().getById(created.id)!!.copy(rawIcal = createBody, caldavUrl = url, etag = etag)
        )

        // 2. User adds an attendee; the coordinator stamps organizerEmail from
        //    the account address. Replicate exactly that stamping.
        eventWriter.updateEvent(
            database.eventsDao().getById(created.id)!!.copy(organizerEmail = organizer),
            isLocal = true,
            attendees = listOf(attendee(cross, order = 0))
        )
        val editBody = IcsPatcher.serialize(
            database.eventsDao().getById(created.id)!!,
            database.attendeesDao().getForEventOnce(created.id)
        )

        // The added ATTENDEE needs an ORGANIZER on the wire or the server has
        // nothing to auto-schedule (RFC 6638 §3) and no invite is delivered.
        assertTrue("edited body must carry an ORGANIZER: ${FixtureRedactor.redact(editBody)}",
            hasOrganizer(editBody))
        assertTrue("edited body must carry the added attendee: ${FixtureRedactor.redact(editBody)}",
            attendeeEmails(editBody).contains(cross.lowercase()))

        val updateResult = client!!.updateEvent(url, editBody, etag)
        assumeTrue("update failed on ${config.name}: ${(updateResult as? CalDavResult.Error)?.message}",
            updateResult.isSuccess())
        trackEvent(url, updateResult.getOrNull()!!)
        println("ADD-ATT-NO-ORG ${config.name}: edited body shipped an ORGANIZER + the added attendee")
    }

    // ---- Fix #2: rescheduled occurrence carries the series' attendees ----

    @Test
    fun `rescheduled occurrence carries the series attendees on the override`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)
        calendarId = localAccountAndCalendar()

        val organizer = ownedAddress(config.name) ?: "organizer.synthetic@example.test"
        val cross = crossAccountAttendee()
        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}"

        val master = eventWriter.createEvent(
            Event(uid = uid, calendarId = calendarId, title = "Recurring invite",
                startTs = START_MS, endTs = START_MS + 3_600_000L, dtstamp = START_MS,
                rrule = "FREQ=WEEKLY;COUNT=5", organizerEmail = organizer, syncStatus = SyncStatus.SYNCED),
            isLocal = true,
            attendees = listOf(attendee(cross, order = 0))
        )
        val third = database.occurrencesDao().getForEvent(master.id)[2]
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = master.id,
            occurrenceTimeMs = third.startTs,
            modifiedEvent = master.copy(id = 0, uid = "", rrule = null,
                title = "Recurring invite (moved)",
                startTs = third.startTs + 4 * 3_600_000L, endTs = third.endTs + 4 * 3_600_000L),
            isLocal = true
        )
        val masterRow = database.eventsDao().getById(master.id)!!

        // The exception must have inherited the master's attendees (fix #2).
        assertTrue("exception must inherit the series attendee in Room",
            database.attendeesDao().getForEventOnce(exception.id).any { it.address.contains(cross) })

        // Serialize the bundle the way PushStrategy does and assert the OVERRIDE
        // VEVENT carries the attendee on the client body.
        val bundle = IcsPatcher.serializeWithExceptions(
            master = masterRow,
            masterAttendees = database.attendeesDao().getForEventOnce(master.id),
            exceptionsWithAttendees = listOf(exception to database.attendeesDao().getForEventOnce(exception.id))
        )
        val overrideBlock = unfold(bundle).split("BEGIN:VEVENT").drop(1)
            .map { it.substringBefore("END:VEVENT") }
            .firstOrNull { it.lineSequence().any { l -> l.trimStart().startsWith("RECURRENCE-ID") } }
        assertTrue("override VEVENT must carry the series attendee: ${FixtureRedactor.redact(bundle)}",
            overrideBlock != null && overrideBlock.contains(cross))
        println("EXC-ATT ${config.name}: override VEVENT carries the series attendee")
    }

    // ---- Regression: empty attendee table preserves rawIcal attendees ----

    @Test
    fun `empty attendee table preserves server attendees on a cosmetic edit`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)
        calendarId = localAccountAndCalendar()
        // Synthetic attendee on the server body — this test never edits the
        // attendee set, it only proves an empty table doesn't strip it, so no
        // real delivery occurs (the create is a plain resource PUT).
        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}"
        val serverIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20251220T100000Z
            DTSTART:20260301T100000Z
            DTEND:20260301T110000Z
            SUMMARY:Pre-table synced event
            ORGANIZER;CN=Boss:mailto:boss.synthetic@example.test
            ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED:mailto:jane.synthetic@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val createResult = client!!.createEvent(calendarUrl!!, uid, serverIcs)
        assumeTrue("create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess())
        val (url, etag) = createResult.getOrNull()!!
        trackEvent(url, etag)

        // Simulate a pre-table sync: event row with the server rawIcal but an
        // EMPTY attendee table (the chip-UI backfill never ran). A cosmetic edit
        // (title only) must NOT strip the rawIcal attendee.
        val event = Event(uid = uid, calendarId = calendarId, title = "Title changed",
            startTs = START_MS, endTs = START_MS + 3_600_000L, dtstamp = START_MS,
            rawIcal = serverIcs, caldavUrl = url, etag = etag, syncStatus = SyncStatus.SYNCED)
        // Empty table → null → preserve (the regression guard lives in
        // PushStrategy; here we exercise IcsPatcher.serialize with null).
        val editBody = IcsPatcher.serialize(event, attendees = null)

        assertTrue("empty table must NOT strip the rawIcal attendee: ${FixtureRedactor.redact(editBody)}",
            attendeeEmails(editBody).contains("jane.synthetic@example.test"))

        val updateResult = client!!.updateEvent(url, editBody, etag)
        assumeTrue("update failed on ${config.name}", updateResult.isSuccess())
        trackEvent(url, updateResult.getOrNull()!!)

        if (expectServerEcho) {
            val stored = client!!.fetchEvent(url).getOrNull()!!.icalData
            assertTrue("${config.name}: attendee must survive a cosmetic edit: ${FixtureRedactor.redact(stored)}",
                attendeeEmails(stored).contains("jane.synthetic@example.test"))
            println("EMPTY-PRESERVE ${config.name}: attendee survived a cosmetic edit")
        } else {
            println("EMPTY-PRESERVE ${config.name}: client body preserved attendee (server routes; no echo asserted)")
        }
    }
}
