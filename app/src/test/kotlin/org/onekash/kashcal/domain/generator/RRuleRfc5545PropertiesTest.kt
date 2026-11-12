package org.onekash.kashcal.domain.generator

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.subsequence
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.PropertyRuns
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Holds [IcalDavRRuleEngine] (ical4j) to the recurrence rules of RFC 5545 §3.3.10 (RECUR) and
 * §3.8.5.3 (RRULE), with no second engine as oracle: each property checks one sentence of the
 * RFC against the expanded set.
 *
 * DTSTART is always synchronized with the rule, since §3.8.5.3 leaves any other set undefined.
 * Start times fall between 06:00 and 22:45 local, away from the DST transitions of the zones
 * used, so no instance lands on a nonexistent or repeated local time.
 *
 * One rule is departed from on purpose: an instance on a local time a spring-forward gap skips
 * moves forward by the gap instead of being dropped, so a daily 02:30 event still happens that
 * day, at 03:30.
 */
class RRuleRfc5545PropertiesTest {

    private val zones = listOf(
        "UTC", "America/New_York", "Europe/Berlin", "Asia/Tokyo", "Australia/Sydney",
        "Pacific/Auckland", "Asia/Kolkata", "America/Sao_Paulo",
    ).map(ZoneId::of)

    private val dayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private fun code(d: DayOfWeek) = dayCodes[d.value - 1]

    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun expand(
        rrule: String,
        start: ZonedDateTime,
        rangeEnd: ZonedDateTime,
        rdates: List<Long> = emptyList(),
        exdates: List<Long> = emptyList(),
    ): List<Long> = IcalDavRRuleEngine.expandToTimestamps(
        rrule = rrule,
        dtstartMs = start.toInstant().toEpochMilli(),
        rangeStartMs = start.toInstant().toEpochMilli(),
        rangeEndMs = rangeEnd.toInstant().toEpochMilli(),
        timezone = start.zone.id,
        isAllDay = false,
        rdateStrings = rdates.joinToString(",") { utcFormat.format(Instant.ofEpochMilli(it)) }.ifEmpty { null },
        exdateStrings = exdates.joinToString(",") { utcFormat.format(Instant.ofEpochMilli(it)) }.ifEmpty { null },
    )

    private fun local(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    /** Returns a timed DTSTART in one of [zones], on a day of month in [day]. */
    private fun startArb(day: IntRange = 1..28, years: IntRange = 1990..2040) = arbitrary {
        val zone = Arb.element(zones).bind()
        val date = LocalDate.of(Arb.int(years).bind(), Arb.int(1..12).bind(), 1)
        val dom = Arb.int(day).bind().coerceAtMost(date.lengthOfMonth())
        val time = LocalTime.of(Arb.int(6..22).bind(), Arb.element(0, 15, 30, 45).bind())
        ZonedDateTime.of(date.withDayOfMonth(dom), time, zone)
    }

    /** A rule synchronized with DTSTART: FREQ, optional INTERVAL, and a BYDAY with its day. */
    private data class Rule(val freq: String, val interval: Int, val byDay: List<DayOfWeek>?)

    private fun ruleArb(start: ZonedDateTime) = arbitrary {
        val freq = Arb.element("DAILY", "WEEKLY", "MONTHLY", "YEARLY").bind()
        val interval = Arb.int(1..4).bind()
        val byDay = if (freq == "WEEKLY" && Arb.int(0..1).bind() == 1) {
            (listOf(start.dayOfWeek) + Arb.subsequence(DayOfWeek.entries).bind()).distinct()
        } else {
            null
        }
        Rule(freq, interval, byDay)
    }

    private fun Rule.text(extra: String = ""): String = buildString {
        append("FREQ=").append(freq)
        if (interval > 1) append(";INTERVAL=").append(interval)
        byDay?.let { days -> append(";BYDAY=").append(days.joinToString(",") { code(it) }) }
        append(extra)
    }

    private fun spanFor(rule: Rule, count: Int): Long = when (rule.freq) {
        "DAILY" -> count.toLong() * rule.interval + 2
        "WEEKLY" -> (count.toLong() * rule.interval + 2) * 7
        "MONTHLY" -> (count.toLong() * rule.interval + 2) * 31
        else -> (count.toLong() * rule.interval + 2) * 366
    }

    private fun assertStrictlyIncreasing(label: String, times: List<Long>) {
        times.zipWithNext().forEach { (a, b) -> assertTrue("$label: not strictly increasing at $a, $b", a < b) }
    }

    // §3.3.10: "The COUNT rule part defines the number of occurrences at which to range-bound
    // the recurrence. The "DTSTART" property value always counts as the first occurrence."
    @Test
    fun `COUNT bounds the set and DTSTART is its first instance`(): Unit = runBlocking {
        val cases = arbitrary { val s = startArb().bind(); Triple(s, ruleArb(s).bind(), Arb.int(1..60).bind()) }
        checkAll(PropertyRuns.config(300), cases) { (start, rule, count) ->
            val rrule = rule.text(";COUNT=$count")
            val out = expand(rrule, start, start.plusDays(spanFor(rule, count)))
            assertEquals("$rrule from $start", count, out.size)
            assertEquals("$rrule from $start: first instance", start.toInstant().toEpochMilli(), out.first())
            assertStrictlyIncreasing("$rrule from $start", out)
        }
    }

    // §3.3.10: "The UNTIL rule part defines a DATE or DATE-TIME value that bounds the recurrence
    // rule in an inclusive manner. If the value specified by UNTIL is synchronized with the
    // specified recurrence, this DATE or DATE-TIME becomes the last instance of the recurrence."
    @Test
    fun `UNTIL on an instance makes it the last one`(): Unit = runBlocking {
        val cases = arbitrary { val s = startArb().bind(); Triple(s, ruleArb(s).bind(), Arb.int(0..39).bind()) }
        checkAll(PropertyRuns.config(300), cases) { (start, rule, pick) ->
            val end = start.plusDays(spanFor(rule, 40))
            val open = expand(rule.text(), start, end)
            val last = open[pick.coerceAtMost(open.lastIndex)]
            val rrule = rule.text(";UNTIL=${utcFormat.format(Instant.ofEpochMilli(last))}")
            val bounded = expand(rrule, start, end)
            assertEquals("$rrule from $start", open.takeWhile { it <= last }, bounded)
        }
    }

    // §3.3.10: "The INTERVAL rule part contains a positive integer representing at which
    // intervals the recurrence rule repeats." Missing day and time come from DTSTART: "This
    // information would be the same as what is specified for "DTSTART"."
    @Test
    fun `INTERVAL spaces instances by that many periods at DTSTART's local time`(): Unit = runBlocking {
        val cases = arbitrary { val s = startArb().bind(); Triple(s, ruleArb(s).bind().copy(byDay = null), Arb.int(2..30).bind()) }
        checkAll(PropertyRuns.config(300), cases) { (start, rule, count) ->
            val rrule = rule.text(";COUNT=$count")
            val out = expand(rrule, start, start.plusDays(spanFor(rule, count))).map { local(it, start.zone) }
            val unit = when (rule.freq) {
                "DAILY" -> ChronoUnit.DAYS
                "WEEKLY" -> ChronoUnit.WEEKS
                "MONTHLY" -> ChronoUnit.MONTHS
                else -> ChronoUnit.YEARS
            }
            out.forEachIndexed { i, t ->
                val expected = start.toLocalDate().plus(i.toLong() * rule.interval, unit)
                assertEquals("$rrule from $start: instance $i date", expected, t.toLocalDate())
                assertEquals("$rrule from $start: instance $i time", start.toLocalTime(), t.toLocalTime())
            }
        }
    }

    // §3.3.10 BYxxx table: BYDAY "Expand" for WEEKLY, so every listed day of each week appears;
    // and "Limit" for DAILY, so only days with a listed weekday appear.
    @Test
    fun `BYDAY expands WEEKLY and limits DAILY to the listed days`(): Unit = runBlocking {
        val cases = arbitrary {
            val s = startArb().bind()
            val days = (listOf(s.dayOfWeek) + Arb.subsequence(DayOfWeek.entries).bind()).distinct()
            Triple(s, days, Arb.element("DAILY", "WEEKLY").bind()) to Arb.long(7L..120L).bind()
        }
        checkAll(PropertyRuns.config(300), cases) { (c, spanDays) ->
            val (start, days, freq) = c
            val rrule = "FREQ=$freq;BYDAY=${days.joinToString(",") { code(it) }}"
            val end = start.plusDays(spanDays)
            val out = expand(rrule, start, end).map { local(it, start.zone).toLocalDate() }
            val expected = generateSequence(start.toLocalDate()) { it.plusDays(1) }
                .takeWhile { ZonedDateTime.of(it, start.toLocalTime(), start.zone).isBefore(end) }
                .filter { it.dayOfWeek in days }
                .toList()
            assertEquals("$rrule from $start", expected, out)
        }
    }

    // §3.3.10 BYxxx table: BYMONTH is "Limit" for DAILY. "FREQ=DAILY;BYMONTH=1 reduces the
    // number of recurrence instances from all days ... to all days in January."
    @Test
    fun `BYMONTH limits DAILY to every day of the listed months`(): Unit = runBlocking {
        val cases = arbitrary {
            val s = startArb().bind()
            val months = (listOf(s.monthValue) + Arb.subsequence((1..12).toList()).bind()).distinct().sorted()
            s to months
        }
        checkAll(PropertyRuns.config(200), cases) { (start, months) ->
            val rrule = "FREQ=DAILY;BYMONTH=${months.joinToString(",")}"
            val end = start.plusDays(800)
            val out = expand(rrule, start, end).map { local(it, start.zone).toLocalDate() }
            val expected = generateSequence(start.toLocalDate()) { it.plusDays(1) }
                .takeWhile { ZonedDateTime.of(it, start.toLocalTime(), start.zone).isBefore(end) }
                .filter { it.monthValue in months }
                .toList()
            assertEquals("$rrule from $start", expected, out)
        }
    }

    // §3.3.10: "Recurrence rules may generate recurrence instances with an invalid date (e.g.,
    // February 30) ... Such recurrence instances MUST be ignored and MUST NOT be counted as part
    // of the recurrence set."
    @Test
    fun `invalid dates are skipped and not counted`(): Unit = runBlocking {
        val cases = arbitrary {
            val zone = Arb.element(zones).bind()
            val time = LocalTime.of(Arb.int(6..22).bind(), 0)
            when (Arb.int(0..1).bind()) {
                // MONTHLY on the 29th, 30th or 31st from a month that has it.
                0 -> {
                    val dom = Arb.int(29..31).bind()
                    var d = LocalDate.of(Arb.int(1990..2040).bind(), 1, 1)
                    while (d.lengthOfMonth() < dom) d = d.plusMonths(1)
                    Triple(ZonedDateTime.of(d.withDayOfMonth(dom), time, zone), "FREQ=MONTHLY;BYMONTHDAY=$dom", dom) to Arb.int(1..12).bind()
                }
                // YEARLY on February 29th.
                else -> {
                    var y = Arb.int(1990..2040).bind()
                    while (!Year.isLeap(y.toLong())) y++
                    Triple(ZonedDateTime.of(LocalDate.of(y, 2, 29), time, zone), "FREQ=YEARLY", 29) to Arb.int(1..12).bind()
                }
            }
        }
        checkAll(PropertyRuns.config(200), cases) { (c, count) ->
            val (start, base, dom) = c
            val rrule = "$base;COUNT=$count"
            val out = expand(rrule, start, start.plusYears(60)).map { local(it, start.zone) }
            assertEquals("$rrule from $start", count, out.size)
            out.forEach { t ->
                assertEquals("$rrule from $start: $t", dom, t.dayOfMonth)
                if (base == "FREQ=YEARLY") assertTrue("$rrule from $start: $t", t.monthValue == 2)
            }
            // The skipped dates are the months (or years) without that day.
            val stepUnit = if (base == "FREQ=YEARLY") ChronoUnit.YEARS else ChronoUnit.MONTHS
            val expected = generateSequence(start.toLocalDate().withDayOfMonth(1)) { it.plus(1, stepUnit) }
                .filter { it.lengthOfMonth() >= dom }
                .map { it.withDayOfMonth(dom) }
                .take(count)
                .toList()
            assertEquals("$rrule from $start", expected, out.map { it.toLocalDate() })
        }
    }

    // §3.3.10: BYMONTHDAY "-10 represents the tenth to the last day of the month"; so -1 is the
    // last day of every month.
    @Test
    fun `negative BYMONTHDAY counts from the end of the month`(): Unit = runBlocking {
        val cases = arbitrary {
            val zone = Arb.element(zones).bind()
            val first = LocalDate.of(Arb.int(1990..2040).bind(), Arb.int(1..12).bind(), 1)
            val back = Arb.int(1..28).bind()
            val date = first.withDayOfMonth(first.lengthOfMonth() - back + 1)
            Triple(ZonedDateTime.of(date, LocalTime.of(Arb.int(6..22).bind(), 30), zone), back, Arb.int(1..36).bind())
        }
        checkAll(PropertyRuns.config(200), cases) { (start, back, count) ->
            val rrule = "FREQ=MONTHLY;BYMONTHDAY=-$back;COUNT=$count"
            val out = expand(rrule, start, start.plusMonths(count + 2L)).map { local(it, start.zone).toLocalDate() }
            val expected = (0 until count).map {
                val m = start.toLocalDate().withDayOfMonth(1).plusMonths(it.toLong())
                m.withDayOfMonth(m.lengthOfMonth() - back + 1)
            }
            assertEquals("$rrule from $start", expected, out)
        }
    }

    // §3.8.5.3: "The final recurrence set is generated by gathering all of the start DATE-TIME
    // values generated by any of the specified "RRULE" and "RDATE" properties, and then
    // excluding any start DATE-TIME values specified by "EXDATE" properties. ... Where duplicate
    // instances are generated by the "RRULE" and "RDATE" properties, only one recurrence is
    // considered."
    @Test
    fun `RDATE adds, EXDATE removes and wins, duplicates count once`(): Unit = runBlocking {
        val cases = arbitrary { val s = startArb().bind(); Triple(s, ruleArb(s).bind(), Arb.long().bind()) }
        checkAll(PropertyRuns.config(300), cases) { (start, rule, seed) ->
            val end = start.plusDays(spanFor(rule, 30))
            val base = expand(rule.text(), start, end)
            val r = kotlin.random.Random(seed)
            // RDATEs: some off-rule hours, some equal to rule instances (duplicates).
            val offRule = (0 until r.nextInt(0, 4)).map {
                start.plusDays(r.nextLong(1, end.toEpochSecond() / 86_400 - start.toEpochSecond() / 86_400))
                    .withHour(r.nextInt(0, 6)).toInstant().toEpochMilli()
            }.filter { it < end.toInstant().toEpochMilli() }
            val onRule = base.drop(1).shuffled(r).take(r.nextInt(0, 3))
            val rdates = offRule + onRule
            // EXDATEs: some rule instances (never DTSTART) and some of the off-rule RDATEs.
            val exdates = base.drop(1).shuffled(r).take(r.nextInt(0, 4)) + offRule.shuffled(r).take(r.nextInt(0, 2))
            val out = expand(rule.text(), start, end, rdates, exdates)
            val expected = ((base + rdates).toSortedSet() - exdates.toSet()).toList()
            assertEquals("${rule.text()} from $start, seed $seed", expected, out)
        }
    }

    // §3.3.10 defines BYDAY as "a COMMA-separated list of days of the week", so a day listed
    // twice names the same day, and COUNT still counts instances, not list entries.
    @Test
    fun `a repeated BYDAY entry changes nothing`(): Unit = runBlocking {
        val cases = arbitrary {
            val s = startArb().bind()
            val days = (listOf(s.dayOfWeek) + Arb.subsequence(DayOfWeek.entries).bind()).distinct()
            Triple(s, days, Arb.int(1..40).bind())
        }
        checkAll(PropertyRuns.config(200), cases) { (start, days, count) ->
            val once = "FREQ=WEEKLY;BYDAY=${days.joinToString(",") { code(it) }};COUNT=$count"
            val twice = "FREQ=WEEKLY;BYDAY=${(days + days).joinToString(",") { code(it) }};COUNT=$count"
            val end = start.plusWeeks(count + 2L)
            assertEquals("$twice from $start", expand(once, start, end), expand(twice, start, end))
        }
    }

    // §3.3.10 says an instance on a "nonexistent local time" MUST be ignored and not counted.
    // The app keeps it instead, moved forward by the gap as java.time's ZonedDateTime.of does,
    // so a daily 02:30 event happens at 03:30 on the spring-forward day and COUNT is unchanged.
    @Test
    fun `an instance in a spring-forward gap moves forward and still counts`(): Unit = runBlocking {
        val cases = arbitrary {
            val zone = Arb.element(listOf("America/New_York", "Europe/Berlin", "Australia/Sydney")).bind()
                .let(ZoneId::of)
            val year = Arb.int(2000..2040).bind()
            val from = if (zone.id == "Australia/Sydney") LocalDate.of(year, 7, 1) else LocalDate.of(year, 1, 1)
            val gap = generateSequence(zone.rules.nextTransition(from.atStartOfDay(zone).toInstant())) {
                zone.rules.nextTransition(it.instant)
            }.first { it.isGap }
            Triple(zone, gap.dateTimeBefore.toLocalDate(), Arb.int(1..3).bind())
        }
        checkAll(PropertyRuns.config(100), cases) { (zone, gapDay, before) ->
            val time = LocalTime.of(2, 30)
            val start = ZonedDateTime.of(gapDay.minusDays(before.toLong()), time, zone)
            val count = before + 3
            val rrule = "FREQ=DAILY;COUNT=$count"
            val out = expand(rrule, start, start.plusDays(count + 2L)).map { local(it, zone) }
            val expected = (0 until count).map { ZonedDateTime.of(start.toLocalDate().plusDays(it.toLong()), time, zone) }
            assertEquals("$rrule from $start", expected.map { it.toInstant().toEpochMilli() }, out.map { it.toInstant().toEpochMilli() })
            assertEquals("$rrule from $start: gap day", LocalTime.of(3, 30), out[before].toLocalTime())
        }
    }
}
