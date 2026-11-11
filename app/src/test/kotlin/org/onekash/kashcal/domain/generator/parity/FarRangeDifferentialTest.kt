package org.onekash.kashcal.domain.generator.parity

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.onekash.kashcal.testutil.PropertyRuns
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Open-ended DAILY, WEEKLY, MONTHLY and YEARLY rules that started decades before the window
 * expand the same in production (ical4j) as in the lib-recur oracle, which starts its iteration
 * near the window. Windows are 1-4 years, well under 10,000 instances, so neither engine's
 * per-call limit plays a part. Rules are DTSTART-synchronized, as in [RandomRRuleGenerator],
 * since RFC 5545 §3.8.5.3 leaves an unsynchronized set undefined.
 */
class FarRangeDifferentialTest {

    private val weekdays = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private fun utc(y: Int, m: Int, d: Int) =
        ZonedDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val cases = arbitrary {
        val startYear = Arb.int(1960..2000).bind()
        val dtStart = ZonedDateTime.of(startYear, Arb.int(1..12).bind(), Arb.int(1..28).bind(), 0, 0, 0, 0, ZoneOffset.UTC)
            .plusSeconds(Arb.long(0L until 86_400L).bind())
        val freq = Arb.element("DAILY", "WEEKLY", "MONTHLY", "YEARLY").bind()
        val parts = mutableListOf("FREQ=$freq")
        if (Arb.int(0..1).bind() == 1) parts += "INTERVAL=${Arb.int(1..4).bind()}"
        val weekday = weekdays[dtStart.dayOfWeek.value - 1]
        when (freq) {
            "WEEKLY" -> if (Arb.int(0..1).bind() == 1) {
                val extra = weekdays.filter { it != weekday }.shuffled(kotlin.random.Random(Arb.long().bind())).take(Arb.int(0..2).bind())
                parts += "BYDAY=${(listOf(weekday) + extra).joinToString(",")}"
            }
            "MONTHLY" -> when (Arb.int(0..2).bind()) {
                0 -> parts += "BYMONTHDAY=${dtStart.dayOfMonth}"
                1 -> parts += "BYDAY=${(dtStart.dayOfMonth - 1) / 7 + 1}$weekday"
                else -> {}
            }
            "YEARLY" -> if (Arb.int(0..1).bind() == 1) parts += "BYMONTH=${dtStart.monthValue}"
            else -> {}
        }
        val windowStartYear = Arb.int(2020..2060).bind()
        val windowYears = Arb.int(1..4).bind()
        RRuleCase(
            name = "far: ${parts.joinToString(";")} from $dtStart",
            category = "far-range",
            rrule = parts.joinToString(";"),
            dtstartMs = dtStart.toInstant().toEpochMilli(),
            timezone = "UTC",
            isAllDay = false,
            rdateStrings = null,
            exdateStrings = null,
            rangeStartMs = utc(windowStartYear, 1, 1),
            rangeEndMs = utc(windowStartYear + windowYears, 1, 1),
        )
    }

    @Test
    fun `open-ended rules from decades ago expand the same in both engines`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(200), cases) { case ->
            when (val parity = ParityHarnessRunner.runCase(case).parity) {
                is ParityResult.BothAgree -> {}
                is ParityResult.Divergence -> fail(
                    "${case.name}, window ${Instant.ofEpochMilli(case.rangeStartMs)}..${Instant.ofEpochMilli(case.rangeEndMs)}: " +
                        "lib-recur only ${parity.libRecurOnly.take(5)} (${parity.libRecurOnly.size}), " +
                        "ical4j only ${parity.ical4jOnly.take(5)} (${parity.ical4jOnly.size}), common ${parity.common.size}",
                )
                else -> fail("${case.name}: $parity")
            }
        }
    }
}
