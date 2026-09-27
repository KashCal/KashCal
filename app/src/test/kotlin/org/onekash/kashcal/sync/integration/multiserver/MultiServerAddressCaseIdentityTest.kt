package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import org.onekash.kashcal.domain.identity.canEditAsOrganizer
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.onekash.kashcal.util.AddressNormalizer
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Round-trips case-insensitive "is this me?" matching live on each server.
 *
 * Each server's own calendar-user-address is discovered the way production does it, then
 * written back as ORGANIZER and as a NEEDS-ACTION ATTENDEE with every letter's case flipped.
 * After fetch, parse, map and persist (the pull path), the account seeded with the address as
 * discovered must still:
 *  - be allowed to edit as organizer,
 *  - not see its own event as a pending invitation,
 *  - get the "you" chip, with an organizer chip present,
 *  - patch its own PARTSTAT, with the server accepting the PUT (when the server echoes the
 *    self ATTENDEE).
 * When the address is email-shaped, the login fallback (no discovered addresses, login
 * differing only in case) must match too.
 *
 * The pulled ORGANIZER is stored without its mailto: prefix, so each check depends on bare
 * mailboxes comparing case-insensitively whenever the server echoes a casing different from the
 * discovered address.
 *
 * Whether a server keeps or normalises the casing it was sent is logged per server (grep
 * CASE-ECHO). No other attendee is on the event, so nothing is delivered to anyone.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerAddressCaseIdentityTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerAddressCaseIdentityTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private const val DAY_MS = 86_400_000L
        // Future anchor so strict servers don't reject an event in the past.
        private val START_MS = ((System.currentTimeMillis() / DAY_MS) + 21) * DAY_MS + 9 * 3_600_000L

        private fun icsUtc(ms: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(ms))

        /** Swaps the case of every letter, so the result differs wherever a letter exists. */
        private fun flipCase(s: String): String = s.map {
            when {
                it.isUpperCase() -> it.lowercaseChar()
                it.isLowerCase() -> it.uppercaseChar()
                else -> it
            }
        }.joinToString("")
    }

    private val parser = ICalParser()
    private lateinit var database: KashCalDatabase
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null
    private val createdEventUrls = mutableListOf<Pair<String, String>>()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()
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

    @Test
    fun `own address in a different case is still recognised after a server round trip`() = runBlocking {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue("${config.name} server not reachable", CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint))
        val c = client!!

        val principal = discoverPrincipal()
        assumeTrue("${config.name}: principal discovery failed", principal != null)
        val discovered = c.discoverCalendarUserAddresses(principal!!).getOrNull().orEmpty()
        val own = discovered.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
        assumeTrue("${config.name}: no mailto calendar-user-address discovered (${discovered.size} addresses)", own != null)
        val ownBare = AddressNormalizer.stripMailto(own!!)
        val flipped = flipCase(ownBare)
        assumeTrue("${config.name}: discovered address has no letters to flip", flipped != ownBare)

        val calendarUrl = discoverCalendar()
        assumeTrue("${config.name}: no writable calendar found", calendarUrl != null)

        val uid = "address-case-${config.name.lowercase()}-${UUID.randomUUID()}"
        val ics = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Address Case Identity//EN
BEGIN:VEVENT
UID:$uid
DTSTAMP:${icsUtc(System.currentTimeMillis())}
DTSTART:${icsUtc(START_MS)}
DTEND:${icsUtc(START_MS + 3_600_000L)}
SUMMARY:Address case on ${config.name}
ORGANIZER;CN=Case Test:mailto:$flipped
ATTENDEE;CN=Case Test;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:$flipped
END:VEVENT
END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val created = c.createEvent(calendarUrl!!, uid, ics)
        assertTrue(
            "${config.name}: createEvent failed: ${(created as? CalDavResult.Error)?.message}",
            created.isSuccess()
        )
        val (eventUrl, createEtag) = created.getOrNull()!!
        createdEventUrls += eventUrl to createEtag

        val fetched = c.fetchEvent(eventUrl).getOrNull()
        assertNotNull("${config.name}: fetchEvent failed", fetched)
        val raw = fetched!!.icalData
        val etag = fetched.etag ?: createEtag
        createdEventUrls[createdEventUrls.lastIndex] = eventUrl to etag

        // Read the addresses the way the app does: through the parser, which takes the EMAIL
        // parameter when a server rewrites the value to a principal path, and ignores ATTENDEE
        // lines inside VALARMs.
        val parsed = parser.parse(raw)
        assertTrue("${config.name}: parse failed", parsed is ParseResult.Success)
        val icalEvent = (parsed as ParseResult.Success).value.events.single()
        val echoedOrganizer = icalEvent.organizer?.email
        val echoedSelf = icalEvent.attendees.firstOrNull { it.email.equals(ownBare, ignoreCase = true) }?.email
        fun caseOf(value: String?) = when (value) {
            null -> "dropped"
            flipped -> "kept-sent-case"
            ownBare -> "normalised-to-discovered-case"
            else -> if (value.equals(ownBare, ignoreCase = true)) "other-case" else "other-address"
        }
        val echo = caseOf(echoedOrganizer)
        println("CASE-ECHO ${config.name}: organizer=$echo selfAttendee=${caseOf(echoedSelf)}")
        assumeTrue("${config.name}: server dropped ORGANIZER; nothing to match", echoedOrganizer != null)

        val accountId = database.accountsDao().insert(
            Account(
                provider = if (config == CalDavServerConfig.ICLOUD) AccountProvider.ICLOUD else AccountProvider.CALDAV,
                email = creds!!.username,
                principalUrl = principal,
                homeSetUrl = creds!!.davEndpoint,
                calendarUserAddresses = discovered
            )
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calendarUrl, displayName = "Case test", color = 0xFF0000FF.toInt())
        )
        val mapped = ICalEventMapper.toEntity(
            icalEvent = icalEvent, rawIcal = raw, calendarId = calendarId, caldavUrl = eventUrl, etag = etag
        )
        val eventId = database.eventsDao().upsert(mapped.event.copy(syncStatus = SyncStatus.SYNCED))
        database.attendeesDao().replaceForEvent(eventId, mapped.attendees.map { it.copy(eventId = eventId) })
        val event = database.eventsDao().getById(eventId)!!
        OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        ).generateOccurrences(event, event.startTs - DAY_MS, event.endTs + 365 * DAY_MS)

        val account = database.accountsDao().getById(accountId)!!

        assertTrue(
            "${config.name}: organizer '${echo}' not recognised as the account",
            account.canEditAsOrganizer(event)
        )

        val pending = EventReader(database).getPendingInvitations(now = 0L).first()
        assertTrue(
            "${config.name}: own event surfaced as a pending invitation",
            pending.none { it.event.id == eventId }
        )

        // The login is only used as an address when it is email-shaped.
        if (AddressNormalizer.isEmailShaped(ownBare)) {
            val fallback = account.copy(email = flipCase(ownBare), calendarUserAddresses = emptyList())
            assertTrue(
                "${config.name}: login fallback differing only in case did not match the organizer",
                fallback.matchesAttendee(event.organizerEmail!!)
            )
        }

        val attendees = database.attendeesDao().getForEventOnce(eventId)
        val chips = AttendeeUiModel.fromRoom(attendees, account, event.organizerEmail, event.organizerName)
        assertTrue("${config.name}: no chip marked as you", chips.any { it.isYou })
        assertTrue("${config.name}: no chip marked as organizer", chips.any { it.isOrganizer })

        if (echoedSelf == null) {
            println("CASE-ECHO ${config.name}: self ATTENDEE not echoed; RSVP patch not applicable")
            return@runBlocking
        }
        val patched = IcsPatcher.patchAttendeeReply(rawIcal = event.rawIcal, account = account, partstat = "ACCEPTED")
        assertNotNull("${config.name}: RSVP patch did not find the account's attendee row", patched)
        val patchedSelf = (parser.parse(patched!!) as ParseResult.Success).value.events.single()
            .attendees.single { it.email.equals(ownBare, ignoreCase = true) }
        assertTrue(
            "${config.name}: patched attendee lost the server's casing",
            patchedSelf.email == echoedSelf
        )
        assertTrue(
            "${config.name}: patched attendee PARTSTAT not ACCEPTED",
            patchedSelf.partStat == org.onekash.icaldav.model.PartStat.ACCEPTED
        )

        val updated = c.updateEvent(eventUrl = eventUrl, icalData = patched, etag = etag)
        assertTrue(
            "${config.name}: RSVP PUT failed: ${(updated as? CalDavResult.Error)?.message}",
            updated.isSuccess()
        )
        createdEventUrls[createdEventUrls.lastIndex] = eventUrl to updated.getOrNull()!!
        println("CASE-ECHO ${config.name}: RSVP PUT accepted")
    }

    private suspend fun discoverPrincipal(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
        return c.discoverPrincipal(caldavUrl).getOrNull()
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val principal = discoverPrincipal() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return c.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }
}
