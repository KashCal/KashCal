package org.onekash.kashcal.domain.generator

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.RecurrenceSet
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.Difference
import org.dmfs.rfc5545.recurrenceset.FastForwarded
import org.dmfs.rfc5545.recurrenceset.Merged
import org.dmfs.rfc5545.recurrenceset.OfList
import org.dmfs.rfc5545.recurrenceset.OfRuleAndFirst
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction

/**
 * Generates and manages materialized occurrences for recurring events.
 *
 * Uses lib-recur for RFC 5545 compliant RRULE expansion:
 *   RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
 *
 * Occurrences are stored in the database for O(1) range queries.
 * This class handles:
 * - Initial generation when event is created
 * - Regeneration when RRULE/EXDATE changes
 * - Incremental extension for lazy loading
 * - Single occurrence cancellation (EXDATE)
 */
@Singleton
class OccurrenceGenerator @Inject constructor(
    private val database: KashCalDatabase,
    private val occurrencesDao: OccurrencesDao,
    private val eventsDao: EventsDao
) {
    companion object {
        private const val MAX_ITERATIONS = 1000 // Safety limit per expansion
        private const val DEFAULT_EXPANSION_MONTHS = 24 // 2 years - consistent with PullStrategy
        private const val MILLISECONDS_PER_SECOND = 1000L
        private const val SECONDS_PER_DAY = 86400L
    }

    /**
     * Generate occurrences for an event within a date range.
     *
     * For non-recurring events, generates a single occurrence.
     * For recurring events, expands RRULE and stores all occurrences in range.
     *
     * IMPORTANT: Preserves exception_event_id links when regenerating.
     * This ensures modified occurrences (RECURRENCE-ID events) maintain their
     * link to exception events after RRULE re-expansion.
     *
     * @param event The event to generate occurrences for
     * @param rangeStartMs Start of range in milliseconds (epoch)
     * @param rangeEndMs End of range in milliseconds (epoch)
     * @return Number of occurrences generated
     */
    suspend fun generateOccurrences(
        event: Event,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Int {
        return database.withTransaction {
            // PRESERVE EXCEPTION LINKS: Query existing links before modification
            // These are occurrences modified via RECURRENCE-ID (edited single occurrences)
            val existingOccurrences = occurrencesDao.getForEvent(event.id)
            val exceptionLinks = existingOccurrences
                .filter { it.exceptionEventId != null }
                .associate { it.startTs to ExceptionLinkData(it.exceptionEventId!!, it.isCancelled) }

            // EXPAND FIRST: Calculate new occurrences BEFORE deleting existing ones
            // This prevents data loss if expansion fails (e.g., malformed RRULE, timezone issue)
            val occurrences = if (event.rrule.isNullOrBlank()) {
                // Non-recurring: single occurrence
                listOf(createSingleOccurrence(event))
            } else {
                // Recurring: expand RRULE
                expandRRule(event, rangeStartMs, rangeEndMs)
            }

            // Only delete and replace if expansion succeeded
            if (occurrences.isNotEmpty()) {
                // Clear existing occurrences AFTER successful expansion
                occurrencesDao.deleteForEvent(event.id)

                occurrencesDao.insertAll(occurrences)

                // RESTORE EXCEPTION LINKS: Reapply after insert
                // Uses tolerance matching because timestamps may shift slightly on re-expand
                for ((originalStartTs, linkData) in exceptionLinks) {
                    restoreExceptionLink(event.id, originalStartTs, linkData)
                }
            } else if (!event.rrule.isNullOrBlank()) {
                // RRULE expansion returned empty - log warning but preserve existing occurrences
                android.util.Log.w("OccurrenceGenerator",
                    "RRULE expansion returned empty for event ${event.id}, preserving existing ${existingOccurrences.size} occurrences")
            }

            occurrences.size
        }  // Transaction commits here - all or nothing
    }

    /**
     * Data class to hold exception link information during regeneration.
     */
    private data class ExceptionLinkData(
        val exceptionEventId: Long,
        val isCancelled: Boolean
    )

    /**
     * Restore an exception link after occurrence regeneration.
     * Uses 60-second tolerance for timestamp matching (DST/timezone edge cases).
     *
     * CRITICAL: Also restores occurrence times from the exception event!
     * After regeneration, occurrences have master event times (from RRULE).
     * This method updates them to match the exception event's modified times.
     */
    private suspend fun restoreExceptionLink(
        eventId: Long,
        originalStartTs: Long,
        linkData: ExceptionLinkData
    ) {
        // Fetch exception event to get its current times
        val exceptionEvent = eventsDao.getById(linkData.exceptionEventId)
        if (exceptionEvent != null) {
            // Use new method that updates both link AND times
            linkException(eventId, originalStartTs, exceptionEvent)
            // Restore cancelled status using exception's time (occurrence now has exception times)
            if (linkData.isCancelled) {
                occurrencesDao.markCancelled(eventId, exceptionEvent.startTs)
            }
        } else {
            // Fallback: just link if exception not found (edge case - orphaned link)
            android.util.Log.w("OccurrenceGenerator",
                "Exception event ${linkData.exceptionEventId} not found during link restoration")
            occurrencesDao.linkException(eventId, originalStartTs, linkData.exceptionEventId)
            // Use original time since we didn't update times
            if (linkData.isCancelled) {
                occurrencesDao.markCancelled(eventId, originalStartTs)
            }
        }
    }

    /**
     * Regenerate occurrences for an event (e.g., after RRULE change).
     * Uses default horizon of 12 months from now.
     */
    suspend fun regenerateOccurrences(event: Event): Int {
        val now = System.currentTimeMillis()
        val rangeStart = event.startTs.coerceAtMost(now) // Include past if event starts in past
        val rangeEnd = now + (DEFAULT_EXPANSION_MONTHS * 30L * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND)
        return generateOccurrences(event, rangeStart, rangeEnd)
    }

    /**
     * Extend occurrences for an event beyond current range.
     * Used for lazy loading when user scrolls far into future.
     *
     * @param event The event to extend
     * @param extendToMs New end date in milliseconds
     * @return Number of new occurrences added
     */
    suspend fun extendOccurrences(
        event: Event,
        extendToMs: Long
    ): Int {
        if (event.rrule.isNullOrBlank()) {
            return 0 // Non-recurring events don't need extension
        }

        return database.withTransaction {
            // Find current max occurrence
            val currentMaxTs = occurrencesDao.getMaxStartTs(event.id) ?: return@withTransaction 0

            // Expand from current max to new end
            val newOccurrences = expandRRule(
                event,
                currentMaxTs + 1, // Start after current max
                extendToMs
            )

            if (newOccurrences.isNotEmpty()) {
                occurrencesDao.insertAll(newOccurrences)
            }

            newOccurrences.size
        }
    }

    /**
     * Cancel a single occurrence (applies EXDATE).
     * Does not modify the event - caller should update event.exdate separately.
     *
     * @param eventId The event ID
     * @param occurrenceTimeMs The occurrence start time to cancel
     */
    suspend fun cancelOccurrence(eventId: Long, occurrenceTimeMs: Long) {
        occurrencesDao.markCancelled(eventId, occurrenceTimeMs)
    }

    /**
     * Link an exception event to an occurrence.
     * Called when an exception event is created for a modified occurrence.
     *
     * @param masterEventId The master event ID
     * @param occurrenceTimeMs The original occurrence time
     * @param exceptionEventId The exception event ID
     */
    suspend fun linkException(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        exceptionEventId: Long
    ) {
        occurrencesDao.linkException(masterEventId, occurrenceTimeMs, exceptionEventId)
    }

    /**
     * Link exception to occurrence AND update occurrence times to match exception.
     *
     * This is the preferred method when you have the exception Event object,
     * as it also updates the occurrence's start_ts, end_ts, start_day, end_day
     * to match the exception event's modified times.
     *
     * CRITICAL: This method normalizes Model A (PullStrategy) to Model B:
     * - Step 1: Delete Model A occurrence (event_id = exception.id) if exists
     * - Step 2: Update master's occurrence with exception link and times
     * - Step 3: Fallback - insert new occurrence if master occurrence didn't exist
     *
     * The underlying DAO query uses OR condition to handle re-editing:
     *   WHERE (ABS(start_ts - occurrenceTime) < 60000 OR exception_event_id = exceptionEventId)
     *
     * @param masterEventId The master event ID
     * @param occurrenceTimeMs The ORIGINAL occurrence time (from event.originalInstanceTime)
     * @param exceptionEvent The exception event with modified times
     */
    suspend fun linkException(
        masterEventId: Long,
        occurrenceTimeMs: Long,
        exceptionEvent: Event
    ) {
        database.withTransaction {
            // Step 1: Delete Model A occurrence (if exists)
            // PullStrategy creates occurrence with event_id = exception.id
            // This normalizes Model A to Model B (single linked occurrence)
            occurrencesDao.deleteForEvent(exceptionEvent.id)

            // Step 2: Update master's occurrence with exception link and times
            val newStartDay = Occurrence.toDayFormat(exceptionEvent.startTs, exceptionEvent.isAllDay)
            val newEndDay = Occurrence.toDayFormat(exceptionEvent.endTs, exceptionEvent.isAllDay)
            val rowsUpdated = occurrencesDao.updateOccurrenceForException(
                masterEventId,
                occurrenceTimeMs,
                exceptionEvent.id,
                exceptionEvent.startTs,
                exceptionEvent.endTs,
                newStartDay,
                newEndDay
            )

            // Step 3: Fallback - if no master occurrence existed, create one
            // This handles edge case where exception is outside sync window
            if (rowsUpdated == 0) {
                occurrencesDao.insert(Occurrence(
                    eventId = masterEventId,
                    calendarId = exceptionEvent.calendarId,
                    startTs = exceptionEvent.startTs,
                    endTs = exceptionEvent.endTs,
                    startDay = newStartDay,
                    endDay = newEndDay,
                    exceptionEventId = exceptionEvent.id,
                    isCancelled = false
                ))
            }
        }
    }

    /**
     * Expand RRULE to list of Occurrence entities.
     *
     * Uses lib-recur for RFC 5545 compliant expansion:
     *   RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
     */
    private fun expandRRule(
        event: Event,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): List<Occurrence> {
        val rrule = event.rrule ?: return emptyList()

        return try {
            // CRITICAL: All-day events MUST use UTC for RRULE expansion.
            // All-day events are stored as UTC midnight. Using local timezone would
            // shift the date (e.g., Jan 6 00:00 UTC in UTC-6 = Jan 5 18:00 local).
            // This would cause occurrences to appear on the wrong day.
            val tz = when {
                event.isAllDay -> TimeZone.getTimeZone("UTC")
                event.timezone != null -> TimeZone.getTimeZone(event.timezone)
                else -> TimeZone.getDefault()
            }
            val dtstartSeconds = event.startTs / MILLISECONDS_PER_SECOND
            val startDateTime = timestampToDateTime(dtstartSeconds, tz)
            val eventDurationMs = event.endTs - event.startTs

            // Extract time components from DTSTART for RDATE/EXDATE matching
            val dtstartHour = startDateTime.hours
            val dtstartMinute = startDateTime.minutes
            val dtstartSecond = startDateTime.seconds

            // Parse RDATE and EXDATE
            val rdates = parseMultiValueField(event.rdate)
            val exdates = parseMultiValueField(event.exdate)

            // Step 1: Build base set (DTSTART ∪ RRULE)
            val rule = RecurrenceRule(rrule)
            val baseSet: RecurrenceSet = OfRuleAndFirst(rule, startDateTime)

            // Step 2: Union with RDATE
            val withRdates: RecurrenceSet = if (rdates.isNotEmpty()) {
                val rdateDateTimes = rdates.mapNotNull {
                    parseDateCode(it, tz, dtstartHour, dtstartMinute, dtstartSecond)
                }
                if (rdateDateTimes.isNotEmpty()) {
                    Merged(baseSet, OfList(*rdateDateTimes.toTypedArray()))
                } else {
                    baseSet
                }
            } else {
                baseSet
            }

            // Step 3: Subtract EXDATE
            val finalSet: RecurrenceSet = if (exdates.isNotEmpty()) {
                val exdateDateTimes = exdates.mapNotNull {
                    parseDateCode(it, tz, dtstartHour, dtstartMinute, dtstartSecond)
                }
                if (exdateDateTimes.isNotEmpty()) {
                    Difference(withRdates, OfList(*exdateDateTimes.toTypedArray()))
                } else {
                    withRdates
                }
            } else {
                withRdates
            }

            // Step 4: Optionally fast-forward to near range start
            // Only fast-forward if range starts significantly after DTSTART
            val rangeStartSeconds = rangeStartMs / MILLISECONDS_PER_SECOND
            val rangeEndSeconds = rangeEndMs / MILLISECONDS_PER_SECOND
            val optimizedSet: RecurrenceSet = if (rangeStartMs > event.startTs + 30 * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND) {
                // Range starts well after DTSTART - use FastForwarded for performance
                val fastForwardSeconds = rangeStartSeconds - 30 * SECONDS_PER_DAY
                val rangeStartDateTime = timestampToDateTime(fastForwardSeconds.coerceAtLeast(0), tz)
                FastForwarded(rangeStartDateTime, finalSet)
            } else {
                // Range starts at/near DTSTART - don't fast-forward to ensure DTSTART is included
                finalSet
            }

            // Step 5: Iterate and collect occurrences
            val occurrences = mutableListOf<Occurrence>()
            val iterator = optimizedSet.iterator()
            var iterations = 0

            while (iterator.hasNext() && iterations < MAX_ITERATIONS) {
                iterations++
                val occurrence = iterator.next()
                val occurrenceTsSeconds = dateTimeToTimestamp(occurrence, event.isAllDay)
                val occurrenceTsMs = occurrenceTsSeconds * MILLISECONDS_PER_SECOND

                // Skip if before range
                if (occurrenceTsMs < rangeStartMs) {
                    continue
                }

                // Stop if past range
                if (occurrenceTsMs >= rangeEndMs) {
                    break
                }

                occurrences.add(
                    Occurrence(
                        eventId = event.id,
                        calendarId = event.calendarId,
                        startTs = occurrenceTsMs,
                        endTs = occurrenceTsMs + eventDurationMs,
                        startDay = Occurrence.toDayFormat(occurrenceTsMs, event.isAllDay),
                        endDay = Occurrence.toDayFormat(occurrenceTsMs + eventDurationMs, event.isAllDay)
                    )
                )
            }

            occurrences

        } catch (e: Exception) {
            android.util.Log.e("OccurrenceGenerator",
                "expandRRule failed for event ${event.id}, rrule='${event.rrule}': ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Create a single occurrence for a non-recurring event.
     */
    private fun createSingleOccurrence(event: Event): Occurrence {
        val startDay = Occurrence.toDayFormat(event.startTs, event.isAllDay)
        val endDay = Occurrence.toDayFormat(event.endTs, event.isAllDay)

        return Occurrence(
            eventId = event.id,
            calendarId = event.calendarId,
            startTs = event.startTs,
            endTs = event.endTs,
            startDay = startDay,
            endDay = endDay
        )
    }

    /**
     * Parse a multi-value field (RDATE or EXDATE) into list of date codes.
     * Handles comma-separated values in multiple formats:
     *
     * Supported formats:
     *   - Milliseconds: "1737331200000" -> converts to day code via Occurrence.toDayFormat()
     *   - Day codes: "20251225" -> used directly
     *   - DateTime: "20251225T100000Z" -> extracts date portion
     *
     * Examples:
     *   "1737331200000" -> ["20260120"] (milliseconds from ICalEventMapper)
     *   "20251225" -> ["20251225"] (legacy day code format)
     *   "20251225,20251226" -> ["20251225", "20251226"]
     *   "20251225T100000Z" -> ["20251225"]
     *   "1737331200000,20260127" -> ["20260120", "20260127"] (mixed format - backward compat)
     */
    private fun parseMultiValueField(field: String?): List<String> {
        if (field.isNullOrBlank()) return emptyList()

        return field.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { dateValue ->
                // Detect format and convert to day code (YYYYMMDD)
                when {
                    // Milliseconds format: 10+ digit number (timestamps are 13 digits for 2020s)
                    dateValue.length >= 10 && dateValue.all { it.isDigit() } -> {
                        dateValue.toLongOrNull()?.let { ms ->
                            Occurrence.toDayFormat(ms, false).toString()
                        }
                    }
                    // DateTime format: has T separator
                    dateValue.contains("T") -> dateValue.substringBefore("T")
                    // Day code format: 8 digits
                    dateValue.length >= 8 -> dateValue.substring(0, 8)
                    else -> null
                }
            }
            .filter { it.length == 8 && it.all { c -> c.isDigit() } }
    }

    /**
     * Parse a date code (YYYYMMDD) to lib-recur DateTime.
     * Uses DTSTART's time components for proper occurrence matching.
     */
    private fun parseDateCode(
        dateCode: String,
        tz: TimeZone,
        hour: Int,
        minute: Int,
        second: Int
    ): DateTime? {
        return try {
            if (dateCode.length < 8) return null
            val year = dateCode.substring(0, 4).toInt()
            val month = dateCode.substring(4, 6).toInt() - 1 // 0-indexed for lib-recur
            val day = dateCode.substring(6, 8).toInt()
            DateTime(tz, year, month, day, hour, minute, second)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert timestamp (seconds) to lib-recur DateTime.
     */
    private fun timestampToDateTime(timestampSeconds: Long, tz: TimeZone): DateTime {
        val calendar = Calendar.getInstance(tz)
        calendar.timeInMillis = timestampSeconds * MILLISECONDS_PER_SECOND
        return DateTime(
            tz,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH), // 0-indexed
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }

    /**
     * Convert lib-recur DateTime to timestamp (seconds).
     *
     * CRITICAL: For all-day events, ALWAYS use UTC regardless of what lib-recur returns.
     * lib-recur may return DateTime objects with null timezone for some recurrence patterns
     * (e.g., FREQ=YEARLY). Using the device's default timezone would shift the date incorrectly.
     *
     * @param dateTime The lib-recur DateTime to convert
     * @param isAllDay Whether this is for an all-day event (forces UTC)
     */
    private fun dateTimeToTimestamp(dateTime: DateTime, isAllDay: Boolean): Long {
        // All-day events MUST use UTC to preserve the calendar date.
        // lib-recur may return null timezone for some recurrence patterns.
        val tz = when {
            isAllDay -> TimeZone.getTimeZone("UTC")
            dateTime.timeZone != null -> dateTime.timeZone
            else -> TimeZone.getDefault()
        }
        val calendar = Calendar.getInstance(tz)
        calendar.set(
            dateTime.year,
            dateTime.month, // 0-indexed
            dateTime.dayOfMonth,
            dateTime.hours,
            dateTime.minutes,
            dateTime.seconds
        )
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / MILLISECONDS_PER_SECOND
    }

    /**
     * Data class for parsed RRULE information.
     * Useful for UI display without full expansion.
     */
    data class RRuleInfo(
        val freq: String,
        val interval: Int,
        val count: Int?,
        val until: Long?,
        val byDay: List<String>,
        val byMonthDay: List<Int>,
        val byMonth: List<Int>,
        val bySetPos: List<Int>
    )

    /**
     * Parse RRULE and extract key components for UI display.
     */
    fun parseRule(rrule: String): RRuleInfo? {
        return try {
            val rule = RecurrenceRule(rrule)
            RRuleInfo(
                freq = rule.freq.name,
                interval = rule.interval,
                count = rule.count,
                // UNTIL parsing uses default behavior (not all-day specific)
                until = rule.until?.let { dateTimeToTimestamp(it, isAllDay = false) },
                byDay = rule.getByDayPart()?.map { it.toString() } ?: emptyList(),
                byMonthDay = rule.getByPart(RecurrenceRule.Part.BYMONTHDAY)?.toList() ?: emptyList(),
                byMonth = rule.getByPart(RecurrenceRule.Part.BYMONTH)?.toList() ?: emptyList(),
                bySetPos = rule.getByPart(RecurrenceRule.Part.BYSETPOS)?.toList() ?: emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Expand RRULE without storing - for preview/validation.
     *
     * @param rrule The RRULE string
     * @param dtstartMs Event start time in milliseconds
     * @param rangeStartMs Range start in milliseconds
     * @param rangeEndMs Range end in milliseconds
     * @param exdates List of excluded dates in YYYYMMDD format
     * @param timezone Optional timezone ID
     * @param isAllDay Whether this is an all-day event (forces UTC for date calculations)
     * @return List of occurrence start times in milliseconds
     */
    fun expandForPreview(
        rrule: String,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        exdates: List<String> = emptyList(),
        timezone: String? = null,
        isAllDay: Boolean = false
    ): List<Long> {
        if (rrule.isBlank()) return emptyList()

        return try {
            // All-day events MUST use UTC to preserve calendar date
            val tz = when {
                isAllDay -> TimeZone.getTimeZone("UTC")
                timezone != null -> TimeZone.getTimeZone(timezone)
                else -> TimeZone.getDefault()
            }
            val dtstartSeconds = dtstartMs / MILLISECONDS_PER_SECOND
            val startDateTime = timestampToDateTime(dtstartSeconds, tz)

            val dtstartHour = startDateTime.hours
            val dtstartMinute = startDateTime.minutes
            val dtstartSecond = startDateTime.seconds

            val rule = RecurrenceRule(rrule)
            val baseSet: RecurrenceSet = OfRuleAndFirst(rule, startDateTime)

            val finalSet: RecurrenceSet = if (exdates.isNotEmpty()) {
                val exdateDateTimes = exdates.mapNotNull {
                    parseDateCode(it, tz, dtstartHour, dtstartMinute, dtstartSecond)
                }
                if (exdateDateTimes.isNotEmpty()) {
                    Difference(baseSet, OfList(*exdateDateTimes.toTypedArray()))
                } else {
                    baseSet
                }
            } else {
                baseSet
            }

            val rangeStartSeconds = rangeStartMs / MILLISECONDS_PER_SECOND
            val rangeEndSeconds = rangeEndMs / MILLISECONDS_PER_SECOND
            val fastForwardSeconds = rangeStartSeconds - 30 * SECONDS_PER_DAY
            val rangeStartDateTime = timestampToDateTime(fastForwardSeconds.coerceAtLeast(0), tz)
            val optimizedSet = FastForwarded(rangeStartDateTime, finalSet)

            val occurrences = mutableListOf<Long>()
            val iterator = optimizedSet.iterator()
            var iterations = 0

            while (iterator.hasNext() && iterations < MAX_ITERATIONS) {
                iterations++
                val occurrence = iterator.next()
                val occurrenceTsSeconds = dateTimeToTimestamp(occurrence, isAllDay)
                val occurrenceTsMs = occurrenceTsSeconds * MILLISECONDS_PER_SECOND

                if (occurrenceTsMs < rangeStartMs) continue
                if (occurrenceTsMs >= rangeEndMs) break

                occurrences.add(occurrenceTsMs)
            }

            occurrences

        } catch (e: Exception) {
            emptyList()
        }
    }
}
