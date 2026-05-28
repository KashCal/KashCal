package org.onekash.kashcal.domain.availability

import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.generators.localDateToDayCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Pure-logic free-block computation for the share-availability feature.
 *
 * Generalizes the algorithm in NextFreeBlockGenerator.findNextFreeBlock to
 * return ALL qualifying free blocks across a span of days, with caller-supplied
 * working-hours window, minimum block length, and zone. Holds no state and
 * never reads DataStore or system clock — every input is explicit so the same
 * logic is testable from a fresh ZoneId without monkey-patching.
 *
 * Working hours are expressed as **minutes from midnight**, where 1440 is the
 * end-of-day sentinel (= the next day's 00:00). LocalTime cannot represent
 * 24:00 directly, so we never round-trip the boundary through a single
 * LocalTime instance — the day-end timestamp is computed as
 * date.plusDays(1).atStartOfDay(zone) when workEndMin == 1440.
 *
 * Caller responsibility: pre-filter the occurrence list for visibility,
 * cancellation, and pending-delete status. The upstream insights query
 * (getOccurrencesWithEventsForInsights) already does this.
 *
 * Zone semantics: `zone` governs the per-day work window, today/now clipping,
 * and timed-event boundaries. All-day matching is zone-independent and uses
 * the occurrence's pre-computed startDay/endDay codes, which producers populate
 * via DateTimeUtils.eventTsToDayCode(isAllDay = true) (UTC-derived). This is
 * what aligns the user's perceived calendar date with the day-code regardless
 * of viewer timezone.
 */
class FreeBlockFinder @Inject constructor() {

    fun find(
        occurrences: List<InsightOccurrence>,
        startDay: LocalDate,
        days: Int,
        workStartMin: Int,
        workEndMin: Int,
        minBlockMinutes: Long,
        includeAllDayAsBusy: Boolean,
        now: Long,
        zone: ZoneId
    ): List<FreeBlock> {
        if (days < 1) return emptyList()
        if (workStartMin < 0 || workEndMin > END_OF_DAY_MIN) return emptyList()
        if (workEndMin - workStartMin < 1) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val blocks = mutableListOf<FreeBlock>()

        for (offset in 0 until days) {
            val date = startDay.plusDays(offset.toLong())
            if (date.isBefore(today)) continue

            val dayWindowStartMs = atTime(date, workStartMin, zone)
            val dayWindowEndMs = atTime(date, workEndMin, zone)

            // Today's window is clipped to max(now, workStart). If now is past
            // workEnd, the day is omitted entirely.
            val effectiveStartMs = if (date == today) maxOf(dayWindowStartMs, now) else dayWindowStartMs
            if (effectiveStartMs >= dayWindowEndMs) continue

            // All-day handling. When the toggle is on, an all-day occurrence
            // covering this date makes the day fully busy. startDay..endDay
            // is the inclusive YYYYMMDD range the producer baked in (see
            // DateTimeUtils.eventTsToEndDayCode); endDay is the last covered
            // day, NOT the RFC-exclusive DTEND.
            val dateCode = localDateToDayCode(date)
            if (includeAllDayAsBusy && occurrences.any {
                    it.isAllDay && it.isBusy() && dateCode in it.startDay..it.endDay
                }) {
                continue
            }

            val timed = occurrences
                .asSequence()
                .filter { !it.isAllDay && it.isBusy() && it.startTs != it.endTs }
                .filter { it.endTs > effectiveStartMs && it.startTs < dayWindowEndMs }
                .sortedBy { it.startTs }
                .toList()

            var gapStart = effectiveStartMs
            for (event in timed) {
                val eventStart = maxOf(event.startTs, dayWindowStartMs)
                if (eventStart > gapStart) {
                    addBlockIfQualifying(
                        blocks, date, gapStart, eventStart, zone,
                        workStartMin, workEndMin, minBlockMinutes
                    )
                }
                gapStart = maxOf(gapStart, minOf(event.endTs, dayWindowEndMs))
            }
            if (dayWindowEndMs > gapStart) {
                addBlockIfQualifying(
                    blocks, date, gapStart, dayWindowEndMs, zone,
                    workStartMin, workEndMin, minBlockMinutes
                )
            }
        }

        return blocks
    }

    private fun addBlockIfQualifying(
        blocks: MutableList<FreeBlock>,
        date: LocalDate,
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        workStartMin: Int,
        workEndMin: Int,
        minBlockMinutes: Long
    ) {
        // Convert epoch ms back to minutes-of-day in the same zone, clamped to
        // the work window. Using minutes (Int) avoids the LocalTime 24:00 trap.
        val startZdt = Instant.ofEpochMilli(startMs).atZone(zone)
        val endZdt = Instant.ofEpochMilli(endMs).atZone(zone)
        val rawStartMin = startZdt.toLocalTime().toMinuteOfDay()
        val rawEndMinSameDay = if (endZdt.toLocalDate().isAfter(date)) END_OF_DAY_MIN else endZdt.toLocalTime().toMinuteOfDay()
        val clampedStart = maxOf(rawStartMin, workStartMin)
        val clampedEnd = minOf(rawEndMinSameDay, workEndMin)
        if (clampedStart >= clampedEnd) return
        val durationMinutes = (clampedEnd - clampedStart).toLong()
        if (durationMinutes < minBlockMinutes) return
        blocks.add(
            FreeBlock(
                day = date,
                start = LocalTime.of(clampedStart / 60, clampedStart % 60),
                // 1440 is the end-of-day sentinel; LocalTime can't represent 24:00,
                // so we encode it as 23:59:59.999999999. Formatters print that as
                // 24:00 / midnight via formatMinutesAsClock; consumers comparing
                // FreeBlock.end to LocalTime should know about the sentinel.
                end = if (clampedEnd == END_OF_DAY_MIN) LocalTime.MAX else LocalTime.of(clampedEnd / 60, clampedEnd % 60),
                durationMinutes = durationMinutes
            )
        )
    }

    private fun InsightOccurrence.isBusy(): Boolean = transparency != "TRANSPARENT"

    companion object {
        const val END_OF_DAY_MIN: Int = 24 * 60

        private fun atTime(date: LocalDate, minutesFromMidnight: Int, zone: ZoneId): Long {
            return if (minutesFromMidnight >= END_OF_DAY_MIN) {
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                date.atTime(minutesFromMidnight / 60, minutesFromMidnight % 60)
                    .atZone(zone).toInstant().toEpochMilli()
            }
        }

        private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
    }
}
