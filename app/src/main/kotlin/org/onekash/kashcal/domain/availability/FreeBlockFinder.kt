package org.onekash.kashcal.domain.availability

import org.onekash.kashcal.domain.insights.InsightOccurrence
import org.onekash.kashcal.domain.insights.generators.localDateToDayCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Computes the free blocks for share availability.
 *
 * Like the next-free-block insight (`NextFreeBlockGenerator`), but returns every qualifying
 * block across a span of days, with the caller's working hours, minimum block length and zone.
 * It holds no state and never reads DataStore or the system clock, so every input is explicit
 * and tests can pass any ZoneId.
 *
 * Working hours are minutes from midnight, where 1440 is end of day (the next day's 00:00).
 * LocalTime can't represent 24:00, so the boundary never goes through a LocalTime: the day-end
 * timestamp is `date.plusDays(1).atStartOfDay(zone)` when workEndMin is 1440. A block ending
 * there has an end of `LocalTime.MAX`.
 *
 * The caller must pre-filter the occurrences for visibility, cancellation and pending delete;
 * [org.onekash.kashcal.domain.insights.InsightsRepository.getOccurrencesForRange] does this.
 * Transparent occurrences and zero-length timed ones never make time busy.
 *
 * `zone` governs the per-day work window, clipping today to now, and timed-event boundaries.
 * All-day matching is zone-independent: it uses the occurrence's startDay/endDay codes, which
 * producers compute in UTC for all-day events (`DateTimeUtils.eventTsToDayCode` with
 * `isAllDay = true`), so the date matches the user's calendar date in any viewer zone.
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

            // Today's window starts at max(now, workStart); past workEnd the day is omitted.
            val effectiveStartMs = if (date == today) maxOf(dayWindowStartMs, now) else dayWindowStartMs
            if (effectiveStartMs >= dayWindowEndMs) continue

            // With the toggle on, a busy all-day occurrence covering this date makes the day
            // fully busy. startDay..endDay is the inclusive YYYYMMDD range the producer stored
            // (`DateTimeUtils.eventTsToEndDayCode`): endDay is the last covered day, not the
            // RFC-exclusive DTEND.
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
        // Convert back to minutes of day in the same zone, clamped to the work window. Minutes
        // can hold 1440, which LocalTime can't.
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
                // End of day (1440) is encoded as LocalTime.MAX (23:59:59.999999999).
                // [AvailabilityFormatter] prints it as 24:00 / midnight; any other reader
                // comparing FreeBlock.end must handle it.
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
