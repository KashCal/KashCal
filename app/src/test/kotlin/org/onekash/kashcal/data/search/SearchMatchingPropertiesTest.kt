package org.onekash.kashcal.data.search

import android.icu.lang.UCharacter
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventSearchSql
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.search.SearchQuery
import org.onekash.kashcal.testutil.PropertyRuns
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.random.Random

/**
 * Holds app-calendar and device-calendar search to one matching rule over generated events and
 * queries. The Room search ([EventSearchSql] through
 * [org.onekash.kashcal.data.db.dao.EventsDao.searchMatches]) and the platform's Instances search
 * ([SqliteCalendarProvider] read through [AndroidCalendarProviderRepository.searchInstances] with
 * [SearchQuery.toProviderQuery]) find the same events, and both find what [referenceMatches]
 * finds: every word of [SearchQuery.of] is a substring, case folded per code point and accents
 * kept, of the title, location, description, a guest's name or a guest's email.
 *
 * Generation stays where both sides are defined to agree; each documented difference is
 * excluded where the generator applies it. [SearchMatchingContractTest] pins the rule with named
 * cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SearchMatchingPropertiesTest {

    private lateinit var database: KashCalDatabase
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repo: AndroidCalendarProviderRepository
    private lateinit var savedZone: TimeZone
    private var calendarId = 0L

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val accountId = database.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
            calendarId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "local://calendar", displayName = "Test", color = 0)
            )
        }
        provider = SqliteCalendarProvider.install()
        repo = SqliteCalendarProvider.repository(ApplicationProvider.getApplicationContext())
        provider.seedCalendar(CAL)
    }

    @After
    fun tearDown() {
        database.close()
        provider.db.close()
        TimeZone.setDefault(savedZone)
    }

    /** One guest; [roomPrefix] is how the Room row stores the address before the email. */
    private data class Guest(val name: String, val email: String, val roomPrefix: String)

    private data class GeneratedEvent(
        val title: String,
        val location: String?,
        val description: String?,
        val guests: List<Guest>,
    )

    private data class Case(val events: List<GeneratedEvent>, val queries: List<String>)

    private val cases = arbitrary { rs ->
        val r = rs.random
        val events = List(r.nextInt(1, 7)) { generateEvent(r) }
        // A query without words is not a search: the app never sends one.
        val queries = List(20) { generateQuery(r, events) }
            .filter { SearchQuery.of(it).words.isNotEmpty() }
            .take(3)
        Case(events, queries)
    }

    @Test
    fun `app and device search find the events the reference rule finds`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(60), cases) { case ->
            clearStores()
            val roomKeys = seedRoom(case.events)
            val deviceKeys = seedDevice(case.events)
            for (text in case.queries) {
                val query = SearchQuery.of(text)
                val expected = case.events.indices.filter { referenceMatches(case.events[it], query.words) }
                val room = database.eventsDao()
                    .searchMatches(EventSearchSql.nextAndLast(query, T0, DAY))
                    .map { roomKeys.getValue(it.event.uid) }
                    .sorted()
                val device = repo.searchInstances(query.toProviderQuery(), DAY, DAY, setOf(CAL))
                    .map { deviceKeys.getValue(it.eventId) }
                    .sorted()
                val context = describe(case.events, text, query.words)
                assertEquals("room search\n$context", expected, room)
                assertEquals("device search\n$context", expected, device)
            }
        }
    }

    private fun clearStores() {
        database.openHelper.writableDatabase.apply {
            execSQL("DELETE FROM attendees")
            execSQL("DELETE FROM events")
        }
        provider.db.delete(SqliteCalendarProvider.ATTENDEES, null, null)
        provider.db.delete(SqliteCalendarProvider.EVENTS, null, null)
    }

    /** Seeds [events] into Room and returns each event's index by its uid. */
    private suspend fun seedRoom(events: List<GeneratedEvent>): Map<String, Int> =
        events.withIndex().associate { (index, e) ->
            val uid = "generated-$index"
            val id = database.eventsDao().insert(
                Event(
                    uid = uid,
                    calendarId = calendarId,
                    title = e.title,
                    location = e.location,
                    description = e.description,
                    startTs = T0,
                    endTs = T0 + HOUR,
                    dtstamp = T0,
                )
            )
            database.attendeesDao().insertAll(
                e.guests.map { Attendee(eventId = id, address = it.roomPrefix + it.email, displayName = it.name) }
            )
            uid to index
        }

    /**
     * Seeds [events] into the provider and returns each event's index by its row id. Every event
     * is a one-off at [T0], inside the searched day: the Instances search only sees occurrences
     * in its window, while the Room search has none.
     */
    private fun seedDevice(events: List<GeneratedEvent>): Map<Long, Int> =
        events.withIndex().associate { (index, e) ->
            val id = provider.insertRow(
                SqliteCalendarProvider.EVENTS,
                Events.CALENDAR_ID to CAL, Events.TITLE to e.title, Events.DTSTART to T0, Events.DTEND to T0 + HOUR,
                Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0, Events.STATUS to Events.STATUS_CONFIRMED,
                Events.DESCRIPTION to e.description, Events.EVENT_LOCATION to e.location,
            )
            // The platform stores guest emails bare.
            e.guests.forEach {
                provider.insertRow(
                    SqliteCalendarProvider.ATTENDEES,
                    Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to it.email, Attendees.ATTENDEE_NAME to it.name,
                )
            }
            id to index
        }

    private fun referenceMatches(event: GeneratedEvent, words: List<String>): Boolean {
        val fields = listOfNotNull(event.title, event.location, event.description) +
            event.guests.flatMap { listOf(it.name, it.email) }
        val folded = fields.map(::fold)
        return words.all { word -> fold(word).let { w -> folded.any { it.contains(w) } } }
    }

    private fun fold(value: String): String = buildString(value.length) {
        value.codePoints().forEach { appendCodePoint(UCharacter.foldCase(it, UCharacter.FOLD_CASE_DEFAULT)) }
    }

    private fun describe(events: List<GeneratedEvent>, text: String, words: List<String>): String =
        "query <$text> words $words\n" + events.withIndex().joinToString("\n") { (i, e) -> "  $i: $e" }

    private fun generateEvent(r: Random): GeneratedEvent = GeneratedEvent(
        title = text(r, ALPHABET, 1, 12),
        location = if (r.nextBoolean()) text(r, ALPHABET, 0, 10) else null,
        description = if (r.nextBoolean()) text(r, ALPHABET, 0, 16) else null,
        // Only guest rows, no organizer row: Room keeps the organizer on the event
        // (organizer_email), which its search does not read, while the platform keeps it as an
        // Attendees row, which its search does.
        guests = List(r.nextInt(0, 4)) {
            Guest(
                name = text(r, ALPHABET, 1, 10),
                // Email-shaped only: Room never searches an address that is not an email
                // (a urn:uuid id, a principal path), while the platform stores emails.
                email = text(r, EMAIL_CHARS, 1, 8) + "@example.test",
                // Room rows carry mailto: (as the pull stores them), MAILTO: or no prefix; the
                // search removes a leading mailto: in any case.
                roomPrefix = ROOM_PREFIXES[r.nextInt(ROOM_PREFIXES.size)],
            )
        },
    )

    /** One to three words, each a piece of a generated text or a few random characters. */
    private fun generateQuery(r: Random, events: List<GeneratedEvent>): String {
        val texts = events.flatMap { e ->
            listOfNotNull(e.title, e.location, e.description) + e.guests.flatMap { listOf(it.name, it.email) }
        }.filter { it.isNotEmpty() }
        val words = MutableList(r.nextInt(1, 4)) { piece(r, texts, 5) }
        if (r.nextInt(3) == 0) {
            // A phrase never holds ',': the platform matches guests through one comma-joined
            // string of all names and one of all emails, so a phrase spanning two guests could
            // match there and not in Room.
            words[r.nextInt(words.size)] = "\"" + piece(r, texts, 9).replace(",", "") + "\""
        }
        return words.joinToString(" ")
    }

    private fun piece(r: Random, texts: List<String>, maxLength: Int): String {
        val raw = if (texts.isNotEmpty() && r.nextInt(4) != 0) {
            val t = texts[r.nextInt(texts.size)]
            val start = r.nextInt(t.length)
            t.substring(start, r.nextInt(start + 1, minOf(t.length, start + maxLength) + 1))
        } else {
            text(r, ALPHABET, 1, 3)
        }
        return raw.map { if (r.nextBoolean()) otherCase(it) else it }.joinToString("")
    }

    /** The other case of [c] when it is in [ALPHABET], else [c]. */
    private fun otherCase(c: Char): Char {
        val other = if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()
        return if (other in ALPHABET) other else c
    }

    private fun text(r: Random, chars: List<Char>, min: Int, max: Int): String =
        String(CharArray(r.nextInt(min, max + 1)) { chars[r.nextInt(chars.size)] })

    private companion object {
        const val CAL = 7L
        const val T0 = 1_709_650_800_000L // 2024-03-05T15:00:00Z
        const val DAY = 20240305
        const val HOUR = 3_600_000L

        val ALPHABET: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9') +
            listOf(' ', '\'', '.', ',', '%', '_', '#', 'ä', 'Ä', 'д', 'Д', 'é', '歯')

        /** Characters for an email's local part: no space or ','. */
        val EMAIL_CHARS: List<Char> = ALPHABET - listOf(' ', ',')

        val ROOM_PREFIXES = listOf("mailto:", "MAILTO:", "")
    }
}
