package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Never-throws property test for the layer directly above the parser:
 * [ICalEventMapper.toEntity]. Untrusted bytes enter at [ICalParser], but the
 * mapper is where a *parseable-yet-hostile* event (DTEND before DTSTART, a
 * multi-year DURATION, a far-future date past the 32-bit time_t boundary that
 * issue #326 surfaces, an empty SUMMARY) turns into a stored [Event] — the shape
 * a crash-on-bad-server-data would take. The mapper must be total (never throw)
 * and internally consistent (a mapped Event carries a real title and finite ts).
 *
 * Seeded for reproducibility: -Dfuzz.mapper.seed= / -Dfuzz.mapper.iterations=.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICalEventMapperFuzzTest {

    private companion object {
        val SEED = System.getProperty("fuzz.mapper.seed")?.toLong() ?: 0xBADCAFEL
        val ITERATIONS = System.getProperty("fuzz.mapper.iterations")?.toInt() ?: 2_000
    }

    private val parser = ICalParser()

    @Test
    fun `toEntity never throws and yields a consistent Event for adversarial parseable ICS`() {
        val rnd = Random(SEED)
        var mapped = 0
        repeat(ITERATIONS) { i ->
            val ics = randomIcs(rnd, i)
            val result = parser.parseAllEvents(ics)
            if (result !is ParseResult.Success) return@repeat // parser rejection is fine here
            for (event in result.value) {
                // The call under test: must be total on any parseable input.
                val entity = try {
                    ICalEventMapper.toEntity(event, ics, 1L, "fuzz.ics", "etag").event
                } catch (t: Throwable) {
                    throw AssertionError("toEntity threw on parseable ICS (iter $i):\n$ics", t)
                }
                mapped++

                // Consistency invariants a stored event must satisfy.
                assertNotNull("title must never be null (mapper defaults to Untitled)", entity.title)
                assertTrue("title must be non-empty", entity.title.isNotEmpty())
                assertTrue(
                    "timestamps must be finite (not sentinel min/max) for iter $i:\n$ics",
                    entity.startTs != Long.MIN_VALUE && entity.startTs != Long.MAX_VALUE &&
                        entity.endTs != Long.MIN_VALUE && entity.endTs != Long.MAX_VALUE,
                )
                // alarmCount is a count, never negative.
                assertTrue("alarmCount must be >= 0", entity.alarmCount >= 0)
            }
        }
        // Guard against the generator silently producing zero parseable events
        // (which would make this test vacuously pass forever).
        assertTrue("Generator must yield some parseable events, got $mapped", mapped > 0)
    }

    /** Build a syntactically valid VCALENDAR with adversarial field values. */
    private fun randomIcs(rnd: Random, salt: Int): String {
        val allDay = rnd.nextBoolean()
        val useDuration = rnd.nextBoolean()

        // Year across a wide range straddling the 32-bit boundary (2038) and 2100.
        val year = 1970 + rnd.nextInt(230) // 1970..2199
        val month = 1 + rnd.nextInt(12)
        val day = 1 + rnd.nextInt(28)
        val hour = rnd.nextInt(24)
        val minute = rnd.nextInt(60)

        val dtStart: String
        val dtEndOrDuration: String
        if (allDay) {
            dtStart = "DTSTART;VALUE=DATE:%04d%02d%02d".format(year, month, day)
            dtEndOrDuration = if (useDuration) {
                "DURATION:P${rnd.nextInt(10)}D"
            } else {
                // Deliberately allow end <= start sometimes (hostile).
                val endDay = 1 + rnd.nextInt(28)
                "DTEND;VALUE=DATE:%04d%02d%02d".format(year, month, endDay)
            }
        } else {
            dtStart = "DTSTART:%04d%02d%02dT%02d%02d00Z".format(year, month, day, hour, minute)
            dtEndOrDuration = if (useDuration) {
                // Sometimes an absurdly long duration.
                if (rnd.nextInt(5) == 0) "DURATION:P${rnd.nextInt(9999)}D"
                else "DURATION:PT${rnd.nextInt(48)}H"
            } else {
                val endHour = rnd.nextInt(24) // may be before start → hostile
                "DTEND:%04d%02d%02dT%02d%02d00Z".format(year, month, day, endHour, minute)
            }
        }

        val summary = when (rnd.nextInt(4)) {
            0 -> "SUMMARY:"                     // empty → mapper must default to Untitled
            1 -> "SUMMARY:${"x".repeat(rnd.nextInt(300))}"
            2 -> ""                             // absent SUMMARY line entirely
            else -> "SUMMARY:Event $salt"
        }

        val alarms = if (rnd.nextInt(3) == 0) {
            val trig = listOf("-PT15M", "-P1D", "-PT1H", "-P7D", "PT0M").random(rnd)
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:$trig\r\nDESCRIPTION:r\r\nEND:VALARM\r\n"
        } else ""

        return buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fuzz//EN\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:fuzz-$salt@test\r\n")
            append("DTSTAMP:20260101T000000Z\r\n")
            append("$dtStart\r\n")
            append("$dtEndOrDuration\r\n")
            if (summary.isNotEmpty()) append("$summary\r\n")
            append(alarms)
            append("END:VEVENT\r\n")
            append("END:VCALENDAR")
        }
    }
}
