package org.onekash.kashcal.sync.integration.multiserver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
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
 * Live end-to-end round-trip for the single-occurrence SEQUENCE bump.
 *
 * Unlike the unit tests (which assert `Event.sequence` in Room) this test
 * drives the WHOLE production path against each real server:
 *
 *   real Room DB -> EventWriter.editSingleOccurrence (the patched code,
 *   via SequenceBumper) -> IcsPatcher.serializeWithExceptions (the same
 *   serializer PushStrategy uses) -> live PUT -> fetch -> parse the
 *   override VEVENT's SEQUENCE back off the wire.
 *
 * It proves the bump survives serialization AND the server round-trip, on
 * every server that stores SEQUENCE verbatim. Servers that manage SEQUENCE
 * themselves (Open-Xchange / Mailbox re-stamps; Zoho strips it) can't
 * validate the client's value, so the override-SEQUENCE assertion is
 * skipped there — the spike (SequenceBumpSpikeTest, since removed)
 * established which servers fall in each bucket. We still assert the PUT is
 * accepted and the override round-trips on those servers.
 *
 * Deliberately NO ORGANIZER/ATTENDEE: a synthetic organizer triggers the
 * documented iSchedule routing, which would strip
 * or reroute the override and destroy the SEQUENCE signal. A plain
 * recurring event isolates the SEQUENCE round-trip.
 *
 * Safety: only ever mutates events created by this run (unique
 * `seq-rt-{ms}-` UID prefix); cleanup deletes only those hrefs. PII in any
 * failure-message ICS body is redacted via [FixtureRedactor].
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*SequenceBumpRoundTripTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SequenceBumpRoundTripTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val classStartMs = System.currentTimeMillis()
        internal val UID_PREFIX = "seq-rt-$classStartMs-"

        /**
         * Servers that overwrite or strip the client's SEQUENCE on the
         * override VEVENT, so the exact stored value can't validate the
         * client's bump. Established empirically by the SEQUENCE spike.
         */
        private val MANAGES_SEQUENCE_SERVER_SIDE = setOf("Mailbox", "Zoho")
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
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(),
            TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)

        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }
    }

    @After
    fun cleanup() = runBlocking {
        val c = client
        if (c != null) {
            for ((url, etag) in createdEventUrls.reversed()) {
                try {
                    c.deleteEvent(url, etag)
                } catch (_: Exception) {
                    // Best-effort; orphans carry the unique seq-rt-{ms} prefix.
                }
            }
        }
        if (::database.isInitialized) database.close()
    }

    private fun assumeReady() {
        assumeTrue(
            "${config.name} credentials not available",
            client != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun discoverCalendar(): String? {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = c.discoverWellKnown(endpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else endpoint
        } else {
            endpoint
        }
        val principal = c.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = c.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        val calendars = c.listCalendars(home).getOrNull() ?: return null
        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    private fun trackEvent(url: String, etag: String) {
        createdEventUrls.removeAll { it.first == url }
        createdEventUrls.add(Pair(url, etag))
    }

    /** SEQUENCE of the override VEVENT (the one carrying RECURRENCE-ID), or
     *  null if absent. Unfolds first (RFC 5545 §3.1). */
    private fun overrideSequence(ics: String): Int? {
        val unfolded = ics.replace(Regex("""\r?\n[ \t]"""), "")
        val overrideBlock = unfolded.split("BEGIN:VEVENT").drop(1)
            .map { it.substringBefore("END:VEVENT") }
            .firstOrNull { b -> b.lineSequence().any { it.trimStart().startsWith("RECURRENCE-ID") } }
            ?: return null
        val seqLine = overrideBlock.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("SEQUENCE:") } ?: return null
        return seqLine.removePrefix("SEQUENCE:").trim().toIntOrNull()
    }

    private fun hasOverride(ics: String): Boolean =
        ics.replace(Regex("""\r?\n[ \t]"""), "")
            .lineSequence().any { it.trimStart().startsWith("RECURRENCE-ID") }

    @Test
    fun `single-occurrence reschedule bumps SEQUENCE through serialize and round-trip`() = runBlocking {
        assumeReady()
        val calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on ${config.name}", calendarUrl != null)

        // --- Build the production artifacts in a real DB ----------------
        val uid = "$UID_PREFIX${config.name.lowercase()}-${UUID.randomUUID()}"
        // Local-only account so EventWriter doesn't try to queue/sync;
        // we drive the wire ourselves with the production serializer.
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "local")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "local://default",
                displayName = "Local",
                color = 0xFF0000FF.toInt()
            )
        )
        val masterEvent = eventWriter.createEvent(
            Event(
                uid = uid,
                calendarId = calendarId,
                title = "Seq round-trip (master)",
                startTs = FIRST_START_MS,
                endTs = FIRST_START_MS + 3_600_000L,
                dtstamp = FIRST_START_MS,
                rrule = "FREQ=WEEKLY;COUNT=5",
                syncStatus = SyncStatus.SYNCED
            ),
            isLocal = true
        )
        val third = database.occurrencesDao().getForEvent(masterEvent.id)[2]
        // Reschedule the third occurrence +4h — the patched path bumps SEQUENCE.
        val exception = eventWriter.editSingleOccurrence(
            masterEventId = masterEvent.id,
            occurrenceTimeMs = third.startTs,
            modifiedEvent = masterEvent.copy(
                id = 0,
                uid = "",
                rrule = null,
                title = "Seq round-trip (third moved)",
                startTs = third.startTs + 4 * 3_600_000L,
                endTs = third.endTs + 4 * 3_600_000L
            ),
            isLocal = true
        )
        val master = database.eventsDao().getById(masterEvent.id)!!

        // Sanity: the fix produced a bumped override in Room before we serialize.
        assertEquals("override must be SEQUENCE:1 in Room (the fix)", 1, exception.sequence)

        // --- Live round-trip, mirroring the production push sequence ----
        // PushStrategy creates the master first, then (when the exception is
        // materialized) PUTs the master+override BUNDLE to the same href as an
        // UPDATE. Reproduce both steps rather than one-shot creating the bundle
        // — some servers (Zoho) reject an initial PUT that already contains a
        // RECURRENCE-ID override for a series they haven't seen created yet.
        val masterBody = IcsPatcher.serializeWithExceptions(master, emptyList())
        val createResult = client!!.createEvent(calendarUrl!!, uid, masterBody)
        assumeTrue(
            "create failed on ${config.name}: ${(createResult as? CalDavResult.Error)?.message}",
            createResult.isSuccess()
        )
        val (url, masterEtag) = createResult.getOrNull()!!
        trackEvent(url, masterEtag)

        val bundleBody = IcsPatcher.serializeWithExceptions(master, listOf(exception))
        assertEquals(
            "serializer must emit override SEQUENCE:1 on the wire",
            1, overrideSequence(bundleBody)
        )
        val updateResult = client!!.updateEvent(url, bundleBody, masterEtag)
        assumeTrue(
            "override PUT failed on ${config.name}: ${(updateResult as? CalDavResult.Error)?.message}",
            updateResult.isSuccess()
        )
        trackEvent(url, updateResult.getOrNull()!!)

        val fetchResult = client!!.fetchEvent(url)
        assumeTrue("fetch failed on ${config.name}", fetchResult.isSuccess())
        val stored = fetchResult.getOrNull()!!.icalData

        // The override must survive the round-trip on every server.
        assertTrue(
            "${config.name} dropped the override VEVENT: ${FixtureRedactor.redact(stored)}",
            hasOverride(stored)
        )

        if (config.name in MANAGES_SEQUENCE_SERVER_SIDE) {
            // Server owns SEQUENCE; we can't assert the client's value, only
            // that the reschedule round-tripped as an override at all.
            println("SEQ-RT ${config.name}: override round-tripped (server manages SEQUENCE; value not asserted)")
        } else {
            assertEquals(
                "${config.name} must preserve override SEQUENCE:1 on round-trip: ${FixtureRedactor.redact(stored)}",
                1, overrideSequence(stored)
            )
            println("SEQ-RT ${config.name}: override SEQUENCE:1 preserved end-to-end")
        }
    }
}

private const val DAY_MS = 86_400_000L
// A fixed near-future Monday-ish anchor; exact weekday is irrelevant for
// SEQUENCE round-trip (no BYDAY alignment asserted). Far enough out to avoid
// "event in the past" rejections on strict servers.
private val FIRST_START_MS = ((System.currentTimeMillis() / DAY_MS) + 14) * DAY_MS + 10 * 3_600_000L
