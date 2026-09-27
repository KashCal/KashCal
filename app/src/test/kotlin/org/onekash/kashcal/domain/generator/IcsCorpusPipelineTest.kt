package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Replays the ICS corpus (every *.ics under the `ical/` test resources) through the steps after the
 * parser: map, Room insert and occurrence generation. Asserts at least 100 files are found, none of
 * the steps throws, each stored occurrence ends at or after its start with well-formed day codes,
 * and at least one event maps. `SogoUrlaubPipelineTest` pins one hand-built event; this widens the
 * same path to ~190 fixtures (basic, datetime, edge_cases, exceptions, extra_properties, recurring,
 * reminders, rfc5545, rfc7986), so a mapper or generator regression on a fixture shape fails here
 * instead of on a user's device. A file the parser rejects is skipped.
 *
 * The corpus is walked from the `ical/` classpath directory, so a new fixture there is covered
 * without a list to keep in sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsCorpusPipelineTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var parser: ICalParser
    private var testCalendarId: Long = 0

    // A window wide enough that recurring and far-future fixtures materialize occurrences.
    private val rangeStartMs = isoUtc("1990-01-01 00:00")
    private val rangeEndMs = isoUtc("2200-01-01 00:00")

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(
            database,
            database.occurrencesDao(),
            database.eventsDao(),
            TestDataStoreFactory.createDefault()
        )
        parser = ICalParser()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "test@test.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://test.com/cal/",
                    displayName = "Corpus Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `every corpus ICS maps, inserts, and generates well-formed occurrences without throwing`() = runTest {
        val files = corpusFiles()
        assertTrue(
            "Expected to find the ICS corpus on the classpath; found none",
            files.size >= 100
        )

        var eventsMapped = 0
        var uidSeq = 0
        for (file in files) {
            val ics = file.readText()
            val result = parser.parseAllEvents(ics)
            // A parser-level rejection is not this test's concern.
            if (result !is ParseResult.Success) continue

            for (event in result.value) {
                val mappedRaw = try {
                    ICalEventMapper.toEntity(event, ics, testCalendarId, file.name, "etag-${file.name}").event
                } catch (t: Throwable) {
                    throw AssertionError("toEntity threw on corpus file ${file.name}", t)
                }
                // Each event goes through map, insert and occurrence generation on its own;
                // exception linkage isn't modeled. Fixtures reuse UIDs and RECURRENCE-IDs
                // across and within files, which would trip the unique (calendar_id, uid,
                // original_instance_time) index that a server's globally unique UIDs never
                // hit, so each gets a distinct UID.
                val mapped = mappedRaw.copy(
                    uid = "corpus-${uidSeq++}::${mappedRaw.uid}",
                    calendarId = testCalendarId,
                    syncStatus = SyncStatus.SYNCED,
                )

                val eventId = database.eventsDao().insert(mapped)
                val stored = mapped.copy(id = eventId)

                try {
                    occurrenceGenerator.generateOccurrences(stored, rangeStartMs, rangeEndMs)
                } catch (t: Throwable) {
                    throw AssertionError("generateOccurrences threw on corpus file ${file.name}", t)
                }

                for (occ in database.occurrencesDao().getForEvent(eventId)) {
                    assertTrue(
                        "occurrence endTs must be >= startTs in ${file.name}",
                        occ.endTs >= occ.startTs
                    )
                    assertTrue(
                        "occurrence day code ${occ.startDay} malformed in ${file.name}",
                        isWellFormedDayCode(occ.startDay)
                    )
                    assertTrue(
                        "occurrence day code ${occ.endDay} malformed in ${file.name}",
                        isWellFormedDayCode(occ.endDay)
                    )
                }
                eventsMapped++
            }
        }
        assertTrue("Corpus produced no mappable events", eventsMapped > 0)
    }

    private fun isWellFormedDayCode(code: Int): Boolean {
        val year = code / 10000
        val month = (code / 100) % 100
        val day = code % 100
        return year in 1900..2300 && month in 1..12 && day in 1..31
    }

    /** Returns every *.ics under the `ical/` classpath directory, or none if it's missing. */
    private fun corpusFiles(): List<File> {
        val root = javaClass.classLoader?.getResource("ical") ?: return emptyList()
        val dir = File(root.toURI())
        return dir.walkTopDown().filter { it.isFile && it.extension == "ics" }.toList()
    }

    private fun isoUtc(dateStr: String): Long {
        val parts = dateStr.split(" ")
        val d = parts[0].split("-")
        val t = parts[1].split(":")
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(d[0].toInt(), d[1].toInt() - 1, d[2].toInt(), t[0].toInt(), t[1].toInt(), 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
