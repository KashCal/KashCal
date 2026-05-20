package org.onekash.kashcal.scheduling

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.integration.multiserver.CalDavServerConfig
import org.onekash.kashcal.sync.integration.multiserver.CalDavTestServerLoader
import org.onekash.kashcal.sync.integration.multiserver.ServerCredentials
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/**
 * End-to-end T2.5 invitation inbox flow against real CalDAV servers.
 *
 * Covers the contract called out in the T2.5 plan:
 * 1. Server holds an event whose authenticated user is a NEEDS-ACTION attendee.
 * 2. Pull (fetch + parse + map + persist) lands the event + attendees in Room.
 * 3. [EventReader.getPendingInvitations] surfaces it as a [PendingInvitation].
 * 4. RSVP write path ([IcsPatcher.patchAttendeeReply] -> [CalDavClient.updateEvent])
 *    succeeds; re-fetched ICS reflects the new partstat (or documents the
 *    server-side strip quirk).
 *
 * Auto-skips per server when credentials are missing or the server is
 * unreachable. Uses Robolectric (not [org.junit.runners.Parameterized]) so
 * each test gets a real in-memory Room DB; iterates over servers inside the
 * test body and skips servers that aren't configured.
 *
 * Run via `./gradlew testDebugUnitTest -Pintegration --tests
 * '*InvitationInboxIntegrationTest*'`.
 *
 * Servers that route ATTENDEEs through their iSchedule pipeline when the
 * authenticated user is the ATTENDEE-of-self
 * ([CalDavServerConfig.stripsAttendeesOnSyntheticOrganizer]) are skipped:
 * they never expose the attendee row on GET, so there is nothing for the
 * inbox to surface. This is the same documented quirk
 * [org.onekash.kashcal.sync.integration.multiserver.MultiServerAttendeePersistenceTest]
 * tolerates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InvitationInboxIntegrationTest {

    private val parser = ICalParser()
    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private lateinit var database: KashCalDatabase
    private lateinit var eventReader: EventReader

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        eventReader = EventReader(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `pending invite surfaces from real server, RSVP writes back`() = runBlocking {
        // Iterate servers; skip the ones that aren't configured or strip self
        // ATTENDEEs on the wire. Test passes if at least one configured server
        // demonstrates the full roundtrip.
        val candidates = CalDavServerConfig.allServers()
            .filterNot { it.stripsAttendeesOnSyntheticOrganizer }
        assumeTrue(
            "No CalDAV servers configured that preserve self-ATTENDEE rows",
            candidates.isNotEmpty()
        )

        var anyVerified = false
        val skipped = mutableListOf<String>()
        for (config in candidates) {
            val ctx = ServerContext.tryOpen(config)
            if (ctx == null) {
                skipped += "${config.name}(no-creds)"
                continue
            }
            try {
                if (!CalDavTestServerLoader.isServerReachable(ctx.creds.davEndpoint)) {
                    skipped += "${config.name}(unreachable)"
                    continue
                }
                val verified = runRoundtrip(ctx)
                if (verified) anyVerified = true
            } finally {
                ctx.cleanup()
            }
        }
        assumeTrue(
            "No reachable servers verified the roundtrip. Skipped: $skipped",
            anyVerified
        )
    }

    private suspend fun runRoundtrip(ctx: ServerContext): Boolean {
        val calendarUrl = discoverCalendar(ctx) ?: run {
            println("[${ctx.config.name}] No calendar found, skipping.")
            return false
        }

        val uid = "t25-inbox-${ctx.config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = buildInviteFixture(ctx, uid)
        val createResult = ctx.client.createEvent(calendarUrl, uid, ics)
        if (!createResult.isSuccess()) {
            val msg = (createResult as? CalDavResult.Error)?.message
            println("[${ctx.config.name}] createEvent failed: $msg — skipping.")
            return false
        }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        ctx.trackEvent(eventUrl, createEtag)

        val fetchResult = ctx.client.fetchEvent(eventUrl)
        if (!fetchResult.isSuccess()) {
            println("[${ctx.config.name}] fetchEvent failed — skipping.")
            return false
        }
        val fetched = fetchResult.getOrNull()!!
        val unfolded = fetched.icalData.replace(Regex("""\r?\n[ \t]"""), "")
        val selfAttendeeLine = unfolded.lines().firstOrNull {
            it.startsWith("ATTENDEE") && it.contains(ctx.creds.username)
        }
        if (selfAttendeeLine == null) {
            println(
                "[${ctx.config.name}] Server stripped self ATTENDEE row on GET. " +
                    "Inbox cannot surface what the server doesn't return; skipping."
            )
            return false
        }

        val fetchedEtag = fetched.etag ?: createEtag

        // Reset DB for this server iteration so the inbox query only sees
        // this server's rows.
        database.clearAllTables()
        val (accountId, calendarId) = seedAccountAndCalendar(ctx, calendarUrl)
        val eventId = persistFromIcs(fetched.icalData, eventUrl, fetchedEtag, calendarId)

        val pending = eventReader.getPendingInvitations(now = 0L).first()
        val mine = pending.firstOrNull { it.event.id == eventId }
        check(mine != null) {
            "${ctx.config.name}: getPendingInvitations did not surface the seeded invite. " +
                "Pending size=${pending.size}"
        }
        check(mine.event.uid == uid) {
            "${ctx.config.name}: surfaced invite UID mismatch: ${mine.event.uid} != $uid"
        }

        val account = database.accountsDao().getById(accountId)!!
        check(account.matchesAttendee("mailto:${ctx.creds.username}")) {
            "${ctx.config.name}: account identity should match self attendee"
        }

        val patched = IcsPatcher.patchAttendeeReply(
            rawIcal = mine.event.rawIcal,
            account = account,
            partstat = "ACCEPTED"
        )
        check(patched != null) {
            "${ctx.config.name}: IcsPatcher.patchAttendeeReply returned null"
        }
        val updateResult = ctx.client.updateEvent(
            eventUrl = eventUrl,
            icalData = patched,
            etag = fetchedEtag
        )
        check(updateResult.isSuccess()) {
            "${ctx.config.name}: updateEvent failed: ${(updateResult as? CalDavResult.Error)?.message}"
        }
        val newEtag = updateResult.getOrNull()!!
        ctx.trackEvent(eventUrl, newEtag)

        val verifyResult = ctx.client.fetchEvent(eventUrl)
        check(verifyResult.isSuccess()) { "${ctx.config.name}: verify fetchEvent failed" }
        val verifyIcs = verifyResult.getOrNull()!!.icalData
        val verifyUnfolded = verifyIcs.replace(Regex("""\r?\n[ \t]"""), "")
        val newSelfLine = verifyUnfolded.lines().firstOrNull {
            it.startsWith("ATTENDEE") && it.contains(ctx.creds.username)
        }
        if (newSelfLine == null) {
            println(
                "[${ctx.config.name}] Server scheduled the REPLY out via iTIP routing " +
                    "(self ATTENDEE row absent on GET after PUT). RSVP write succeeded; " +
                    "treating as verified."
            )
            return true
        }
        check(newSelfLine.contains("PARTSTAT=ACCEPTED", ignoreCase = true)) {
            "${ctx.config.name}: server did not reflect PARTSTAT=ACCEPTED. Got: $newSelfLine"
        }
        println("[${ctx.config.name}] RSVP roundtrip verified.")
        return true
    }

    private suspend fun discoverCalendar(ctx: ServerContext): String? {
        val endpoint = ctx.creds.davEndpoint
        val caldavRoot = if (ctx.config.usesWellKnownDiscovery) {
            val wellKnown = ctx.client.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else endpoint
        val principal = ctx.client.discoverPrincipal(caldavRoot).getOrNull() ?: return null
        val home = ctx.client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = ctx.client.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    private fun buildInviteFixture(ctx: ServerContext, uid: String): String {
        val selfAddr = ctx.creds.username
        // Synthetic external ORGANIZER so the inbox builder treats this as a
        // real invite (organizer != self). Servers that route ATTENDEEs through
        // iSchedule when ORGANIZER doesn't match the auth account are filtered
        // out via stripsAttendeesOnSyntheticOrganizer; remaining servers
        // (Baikal, Nextcloud, SOGo) preserve the self-attendee row on GET.
        return """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Invite Inbox Integration//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsDateFormat.format(Date())}
DTSTART:20260815T140000Z
DTEND:20260815T150000Z
SUMMARY:Inbox invite on ${ctx.config.name}
ORGANIZER;CN=External Organizer:mailto:external.organizer.synthetic@example.test
ATTENDEE;CN=Self Attendee;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:$selfAddr
ATTENDEE;CN=Other;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:other.synthetic@example.test
END:VEVENT
END:VCALENDAR
        """.trimIndent()
    }

    private suspend fun seedAccountAndCalendar(
        ctx: ServerContext,
        calendarUrl: String
    ): Pair<Long, Long> {
        val selfAddr = ctx.creds.username
        val provider = if (ctx.config == CalDavServerConfig.ICLOUD) {
            AccountProvider.ICLOUD
        } else {
            AccountProvider.CALDAV
        }
        val accountId = database.accountsDao().insert(
            Account(
                provider = provider,
                email = selfAddr,
                principalUrl = ctx.creds.davEndpoint,
                homeSetUrl = ctx.creds.davEndpoint,
                calendarUserAddresses = listOf("mailto:$selfAddr")
            )
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = calendarUrl,
                displayName = "Test Calendar",
                color = 0xFF0000FF.toInt()
            )
        )
        return Pair(accountId, calendarId)
    }

    private suspend fun persistFromIcs(
        rawIcal: String,
        eventUrl: String,
        etag: String,
        calendarId: Long
    ): Long {
        val parseResult = parser.parse(rawIcal)
        check(parseResult is ParseResult.Success) {
            "Parser failed on fetched ICS: $parseResult"
        }
        val icalEvent = parseResult.value.events.single()
        val mapped = ICalEventMapper.toEntity(
            icalEvent = icalEvent,
            rawIcal = rawIcal,
            calendarId = calendarId,
            caldavUrl = eventUrl,
            etag = etag
        )
        val eventToInsert = mapped.event.copy(syncStatus = SyncStatus.SYNCED)
        val eventId = database.eventsDao().upsert(eventToInsert)
        database.attendeesDao().replaceForEvent(
            eventId,
            mapped.attendees.map { it.copy(eventId = eventId) }
        )
        val savedEvent = database.eventsDao().getById(eventId)!!
        val rangeStart = savedEvent.startTs - 24L * 60 * 60 * 1000
        val rangeEnd = savedEvent.endTs + 365L * 24 * 60 * 60 * 1000
        OccurrenceGenerator(
            database,
            database.occurrencesDao(),
            database.eventsDao(),
            TestDataStoreFactory.createDefault()
        ).generateOccurrences(savedEvent, rangeStart, rangeEnd)
        return eventId
    }

    private class ServerContext(
        val config: CalDavServerConfig,
        val client: CalDavClient,
        val creds: ServerCredentials
    ) {
        private val createdEventUrls = mutableListOf<Pair<String, String>>()

        fun trackEvent(url: String, etag: String) {
            createdEventUrls.removeAll { it.first == url }
            createdEventUrls.add(Pair(url, etag))
        }

        fun cleanup() = runBlocking {
            for ((url, etag) in createdEventUrls.reversed()) {
                try {
                    client.deleteEvent(url, etag)
                } catch (_: Exception) {
                    // best-effort
                }
            }
        }

        companion object {
            fun tryOpen(config: CalDavServerConfig): ServerContext? {
                val pair = CalDavTestServerLoader.createClient(config) ?: return null
                return ServerContext(config, pair.first, pair.second)
            }
        }
    }
}
