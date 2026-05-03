package org.onekash.kashcal.domain.insights

import android.util.Log
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.domain.insights.generators.localDateToDayCode
import org.onekash.kashcal.util.DateTimeUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InsightsRepository @Inject constructor(
    private val occurrencesDao: OccurrencesDao,
    private val calendarsDao: CalendarsDao,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val dataStore: KashCalDataStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun getStatsWithOccurrences(
        period: AnalysisPeriod,
        now: Long
    ): Pair<PeriodStats, List<InsightOccurrence>> = withContext(ioDispatcher) {
        val firstDayPref = dataStore.getFirstDayOfWeek()
        val firstDay = DateTimeUtils.resolveFirstDayOfWeek(firstDayPref)
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val (periodStart, periodEnd) = calculatePeriodBounds(period, today, firstDay, zone)
        val startTs = periodStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = periodEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val roomOccurrences = occurrencesDao.getOccurrencesWithEventsForInsights(startTs, endTs)
        val roomOccs: List<InsightOccurrence> = roomOccurrences.map {
            SimpleOccurrence(it.startTs, it.endTs, it.event.isAllDay, it.startDay, it.endDay, it.calendarId)
        }

        val startDayCode = localDateToDayCode(periodStart)
        val endDayCode = localDateToDayCode(periodEnd)
        val (deviceOccs, deviceCalendarMeta) = queryDeviceOccurrences(startDayCode, endDayCode)
        val allOccurrences = roomOccs + deviceOccs

        val calendarMap = buildCalendarMap(roomOccurrences.map { it.calendarId }.distinct()).toMutableMap()
        calendarMap.putAll(deviceCalendarMeta)

        val stats = computeStats(allOccurrences, calendarMap, periodStart, periodEnd, startTs, endTs, zone)
        stats to allOccurrences
    }

    suspend fun getDelta(
        currentPeriod: AnalysisPeriod,
        currentStats: PeriodStats,
        now: Long
    ): String? = withContext(ioDispatcher) {
        val previousPeriod = when (currentPeriod) {
            AnalysisPeriod.THIS_WEEK -> AnalysisPeriod.LAST_WEEK
            AnalysisPeriod.LAST_WEEK -> null
            AnalysisPeriod.THIS_MONTH -> null
        } ?: return@withContext null

        val (previousStats, _) = getStatsWithOccurrences(previousPeriod, now)
        if (previousStats.totalMinutes == 0L && currentStats.totalMinutes == 0L) return@withContext null

        val diffMinutes = currentStats.totalMinutes - previousStats.totalMinutes
        if (diffMinutes == 0L) return@withContext null

        val formatted = formatMinutesShort(kotlin.math.abs(diffMinutes))
        if (diffMinutes > 0) "+$formatted" else "-$formatted"
    }

    suspend fun classifyPeriod(period: AnalysisPeriod, now: Long): TemporalClass {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstDayPref = dataStore.getFirstDayOfWeek()
        val firstDay = DateTimeUtils.resolveFirstDayOfWeek(firstDayPref)
        val (periodStart, periodEnd) = calculatePeriodBounds(period, today, firstDay, zone)

        return when {
            today.isBefore(periodStart) -> TemporalClass.FUTURE
            today.isAfter(periodEnd) -> TemporalClass.PAST
            else -> TemporalClass.IN_PROGRESS
        }
    }

    internal fun calculatePeriodBounds(
        period: AnalysisPeriod,
        today: LocalDate,
        firstDay: Int,
        zone: ZoneId
    ): Pair<LocalDate, LocalDate> {
        return when (period) {
            AnalysisPeriod.THIS_WEEK -> {
                val weekStart = getWeekStart(today, firstDay)
                weekStart to weekStart.plusDays(6)
            }
            AnalysisPeriod.LAST_WEEK -> {
                val thisWeekStart = getWeekStart(today, firstDay)
                val lastWeekStart = thisWeekStart.minusWeeks(1)
                lastWeekStart to lastWeekStart.plusDays(6)
            }
            AnalysisPeriod.THIS_MONTH -> {
                val monthStart = today.withDayOfMonth(1)
                val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
                monthStart to monthEnd
            }
        }
    }

    private fun getWeekStart(date: LocalDate, firstDayCalConst: Int): LocalDate {
        val firstDow = when (firstDayCalConst) {
            Calendar.SUNDAY -> DayOfWeek.SUNDAY
            Calendar.MONDAY -> DayOfWeek.MONDAY
            Calendar.SATURDAY -> DayOfWeek.SATURDAY
            else -> DayOfWeek.MONDAY
        }
        var d = date
        while (d.dayOfWeek != firstDow) {
            d = d.minusDays(1)
        }
        return d
    }

    private suspend fun queryDeviceOccurrences(
        startDayCode: Int,
        endDayCode: Int
    ): Pair<List<SimpleOccurrence>, Map<Long, Pair<String, Int>>> {
        return try {
            val featureEnabled = dataStore.getDeviceCalendarsEnabled()
            val enabledIds = if (featureEnabled) dataStore.getEnabledDeviceCalendarIds() else emptySet()
            val hiddenIds = if (featureEnabled) dataStore.getHiddenDeviceCalendarIds() else emptySet()
            val visibleIds = enabledIds - hiddenIds
            if (visibleIds.isEmpty()) return emptyList<SimpleOccurrence>() to emptyMap()

            val hideDeclined = !dataStore.getShowDeclinedEvents()
            val instances = calendarProviderRepository.getInstancesForDayRange(
                startDayCode, endDayCode, visibleIds, hideDeclined
            )

            val calMeta = mutableMapOf<Long, Pair<String, Int>>()
            val occs = instances.map { inst ->
                // Negate device calendar IDs to avoid collision with Room's positive auto-increment IDs
                val deviceCalId = -inst.calendarId
                calMeta.putIfAbsent(deviceCalId, Pair(inst.calendarDisplayName, inst.calendarColor))
                SimpleOccurrence(
                    startTs = inst.startTs,
                    endTs = inst.endTs,
                    isAllDay = inst.isAllDay,
                    startDay = inst.startDay,
                    endDay = inst.endDay,
                    calendarId = deviceCalId
                )
            }
            occs to calMeta
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked, falling back to Room-only", e)
            emptyList<SimpleOccurrence>() to emptyMap()
        }
    }

    private suspend fun buildCalendarMap(calendarIds: List<Long>): Map<Long, Pair<String, Int>> {
        if (calendarIds.isEmpty()) return emptyMap()
        val calendars = calendarsDao.getByIds(calendarIds)
        return calendars.associate { cal ->
            cal.id to Pair(cal.displayName, cal.localColorOverride ?: cal.color)
        }
    }

    private fun computeStats(
        occurrences: List<InsightOccurrence>,
        calendarMap: Map<Long, Pair<String, Int>>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        periodStartTs: Long,
        periodEndTs: Long,
        zone: ZoneId
    ): PeriodStats {
        if (occurrences.isEmpty()) {
            return PeriodStats(
                totalMinutes = 0,
                allDayCount = 0,
                calendarBreakdown = emptyList(),
                dailyBreakdown = buildEmptyDailyBreakdown(periodStart, periodEnd),
                periodStart = periodStartTs,
                periodEnd = periodEndTs
            )
        }

        var totalMinutes = 0L
        var allDayCount = 0
        val calendarMinutes = mutableMapOf<Long, Long>()
        val dayMinutes = mutableMapOf<Int, Long>()

        for (occ in occurrences) {
            if (occ.isAllDay) {
                allDayCount++
                continue
            }

            if (occ.startTs == occ.endTs) continue

            val clampedStart = maxOf(occ.startTs, periodStartTs)
            val clampedEnd = minOf(occ.endTs, periodEndTs)
            if (clampedStart >= clampedEnd) continue

            val occMinutes = apportionToDays(clampedStart, clampedEnd, zone)
            var occTotal = 0L

            for ((dayCode, mins) in occMinutes) {
                dayMinutes[dayCode] = (dayMinutes[dayCode] ?: 0L) + mins
                occTotal += mins
            }

            totalMinutes += occTotal
            calendarMinutes[occ.calendarId] = (calendarMinutes[occ.calendarId] ?: 0L) + occTotal
        }

        val calendarBreakdown = calendarMinutes.map { (calId, mins) ->
            val (name, color) = calendarMap[calId] ?: Pair("Unknown", 0xFF888888.toInt())
            CalendarHours(calendarId = calId, calendarName = name, color = color, minutes = mins)
        }.sortedByDescending { it.minutes }

        val dailyBreakdown = buildDailyBreakdown(periodStart, periodEnd, dayMinutes)

        return PeriodStats(
            totalMinutes = totalMinutes,
            allDayCount = allDayCount,
            calendarBreakdown = calendarBreakdown,
            dailyBreakdown = dailyBreakdown,
            periodStart = periodStartTs,
            periodEnd = periodEndTs
        )
    }

    internal fun apportionToDays(startTs: Long, endTs: Long, zone: ZoneId): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        val startInstant = Instant.ofEpochMilli(startTs)
        val endInstant = Instant.ofEpochMilli(endTs)

        val startZoned = startInstant.atZone(zone)
        val endZoned = endInstant.atZone(zone)

        var currentDayStart = startZoned.toLocalDate()
        val lastDay = endZoned.toLocalDate()

        while (!currentDayStart.isAfter(lastDay)) {
            val dayStartMs = currentDayStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndMs = currentDayStart.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val effectiveStart = maxOf(startTs, dayStartMs)
            val effectiveEnd = minOf(endTs, dayEndMs)

            if (effectiveEnd > effectiveStart) {
                val minutes = (effectiveEnd - effectiveStart) / 60_000L
                val dayCode = currentDayStart.year * 10000 + currentDayStart.monthValue * 100 + currentDayStart.dayOfMonth
                result[dayCode] = minutes
            }

            currentDayStart = currentDayStart.plusDays(1)
        }

        return result
    }

    private fun buildDailyBreakdown(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dayMinutes: Map<Int, Long>
    ): List<DayHours> {
        val result = mutableListOf<DayHours>()
        var date = periodStart
        while (!date.isAfter(periodEnd)) {
            val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
            result.add(DayHours(dayCode = dayCode, minutes = dayMinutes[dayCode] ?: 0L, isInMonth = true))
            date = date.plusDays(1)
        }
        return result
    }

    private fun buildEmptyDailyBreakdown(
        periodStart: LocalDate,
        periodEnd: LocalDate
    ): List<DayHours> = buildDailyBreakdown(periodStart, periodEnd, emptyMap())

    companion object {
        private const val TAG = "InsightsRepository"

        fun formatMinutesShort(totalMinutes: Long): String {
            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            return when {
                hours == 0L -> "${mins}m"
                mins == 0L -> "${hours}h"
                else -> "${hours}h ${mins}m"
            }
        }
    }
}
