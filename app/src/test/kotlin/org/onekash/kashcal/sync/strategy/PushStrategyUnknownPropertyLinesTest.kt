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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Changing occurrences of a pulled series sends each VEVENT's unknown
 * properties back as the server sent them: the series keeps its own, a
 * changed occurrence the server already holds keeps its own, and a newly
 * changed occurrence starts from the series' lines. Drives the real writer,
 * the real push and the real client against a local server, over a real
 * database seeded the way pull stores a resource.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PushStrategyUnknownPropertyLinesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient
    private lateinit var pushStrategy: PushStrategy
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private val putBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var calendarId = 0L
    private var seriesId = 0L

    private val seriesLines = listOf(
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS=\"10600 N Tantau Ave\\nCupertino, CA 95014\";X-TITLE=\"Apple Park: Visitor Center\":geo:37.332,-122.005",
        "COMMENT:line one\\nline two\\, with comma",
        "RESOURCES:Projector",
        "RESOURCES:Whiteboard",
    )
    private val occurrenceLines = listOf(
        "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Cafe, Main St\":geo:1.0,2.0",
        "COMMENT:moved\\nthis week only",
    )

    private val uid = "series-unknown@example.test"
    private val resource = (
        listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20260101T100000Z",
            "DTSTART:20261203T140000Z", "DTEND:20261203T150000Z", "RRULE:FREQ=WEEKLY;COUNT=5",
            "SUMMARY:Series",
        ) + seriesLines + listOf(
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20260101T100000Z",
            "RECURRENCE-ID:20261210T140000Z", "DTSTART:20261210T160000Z", "DTEND:20261210T170000Z",
            "SUMMARY:Changed occurrence",
        ) + occurrenceLines + listOf("END:VEVENT", "END:VCALENDAR", "")
        ).joinToString("\r\n")

    private val existingOccurrenceMs = 1_796_911_200_000L // 2026-12-10T14:00:00Z
    private val otherOccurrenceMs = existingOccurrenceMs + 7 * 86_400_000L

    @Before
    fun setup() = runTest {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        client = OkHttpCalDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = base), DefaultQuirks(base)
        )

        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries().build()
        occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault()
        )
        eventWriter = EventWriter(database, occurrenceGenerator)

        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "me@example.test"))
        calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = server.url("/cal/").toString(), displayName = "Cal", color = -1)
        )
        seedPulledSeries()

        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.getAccountById(any()) } returns database.accountsDao().getById(accountId)
        pushStrategy = PushStrategy(
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            pendingOperationsDao = database.pendingOperationsDao(),
            accountRepository = accountRepository,
            attendeesDao = database.attendeesDao(),
            pendingCancelsDao = database.pendingCancelsDao()
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "PUT" -> {
                    putBodies += request.body.readUtf8()
                    MockResponse().setResponseCode(204).setHeader("ETag", "\"e2\"")
                }
                "GET" -> MockResponse().setResponseCode(200).setHeader("ETag", "\"e2\"").setBody(putBodies.lastOrNull() ?: resource)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    /** Stores the resource the way pull does: every row carries the whole body. */
    private suspend fun seedPulledSeries() {
        val parsed = ICalParser().parseAllEvents(resource).getOrNull()!!
        val url = server.url("/cal/series-unknown.ics").toString()
        val seriesParsed = parsed.single { it.recurrenceId == null }
        val series = ICalEventMapper.toEntity(seriesParsed, resource, calendarId, url, "e1").event
        seriesId = database.eventsDao().insert(series)
        occurrenceGenerator.regenerateOccurrences(database.eventsDao().getById(seriesId)!!)

        val exception = ICalEventMapper.toEntity(
            parsed.single { it.recurrenceId != null }, resource, calendarId, url, "e1",
            masterDtStart = seriesParsed.dtStart
        ).event.copy(originalEventId = seriesId)
        val exceptionId = database.eventsDao().insert(exception)
        occurrenceGenerator.linkException(seriesId, exception.originalInstanceTime!!, database.eventsDao().getById(exceptionId)!!)
    }

    private fun unfold(ics: String) = ics.replace(Regex("\r?\n[ \t]"), "")

    /** Top-level lines of the VEVENT titled [summary] in the last PUT body. */
    private fun sentBlock(summary: String): List<String> {
        val body = unfold(putBodies.last())
        val blocks = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (line in body.split("\r\n", "\n")) {
            when {
                current == null && line == "BEGIN:VEVENT" -> { current = mutableListOf(); depth = 0 }
                current == null -> Unit
                line == "END:VEVENT" && depth == 0 -> { blocks += current; current = null }
                line.startsWith("BEGIN:") -> depth++
                line.startsWith("END:") -> depth--
                depth == 0 -> current += line
            }
        }
        return blocks.firstOrNull { "SUMMARY:$summary" in it }
            ?: throw AssertionError("no VEVENT titled '$summary' in: $body")
    }

    private fun unknownIn(block: List<String>) = block.filter { it in seriesLines || it in occurrenceLines }

    private suspend fun series(): Event = database.eventsDao().getById(seriesId)!!

    @Test
    fun `changing another occurrence keeps every VEVENT's own properties and seeds the new one from the series`() = runTest {
        eventWriter.editSingleOccurrence(
            masterEventId = seriesId,
            occurrenceTimeMs = otherOccurrenceMs,
            modifiedEvent = series().copy(title = "New occurrence"),
            isLocal = false
        )

        pushStrategy.pushAll(client)

        assertTrue("the series was pushed", putBodies.isNotEmpty())
        assertEquals(seriesLines, unknownIn(sentBlock("Series")))
        assertEquals(occurrenceLines, unknownIn(sentBlock("Changed occurrence")))
        assertEquals(seriesLines, unknownIn(sentBlock("New occurrence")))
    }

    @Test
    fun `after a this-and-future split the new series never takes the old series' changed occurrence`() = runTest {
        // The new series is a copy of the modified event, here the series row with its
        // stored body, so it still carries the old body with the old changed occurrence.
        val newSeries = eventWriter.splitSeries(
            masterEventId = seriesId,
            splitTimeMs = existingOccurrenceMs,
            modifiedEvent = series().copy(title = "Split series"),
            isLocal = false
        )
        eventWriter.editSingleOccurrence(
            masterEventId = newSeries.id,
            occurrenceTimeMs = existingOccurrenceMs,
            modifiedEvent = database.eventsDao().getById(newSeries.id)!!.copy(title = "Edited after split"),
            isLocal = false
        )

        pushStrategy.pushAll(client)

        val body = putBodies.lastOrNull { "SUMMARY:Edited after split" in unfold(it) }
            ?: throw AssertionError("the new series was not pushed: $putBodies")
        putBodies += body // sentBlock reads the last body
        val edited = sentBlock("Edited after split")
        assertTrue(
            "the old series' changed occurrence leaked into the new series: $edited",
            edited.none { it in occurrenceLines }
        )
        assertEquals(seriesLines, unknownIn(edited))
    }

    @Test
    fun `re-editing a changed occurrence keeps its own properties, not the series'`() = runTest {
        eventWriter.editSingleOccurrence(
            masterEventId = seriesId,
            occurrenceTimeMs = existingOccurrenceMs,
            modifiedEvent = series().copy(title = "Changed again"),
            isLocal = false
        )

        pushStrategy.pushAll(client)

        assertEquals(seriesLines, unknownIn(sentBlock("Series")))
        assertEquals(occurrenceLines, unknownIn(sentBlock("Changed again")))
    }
}
