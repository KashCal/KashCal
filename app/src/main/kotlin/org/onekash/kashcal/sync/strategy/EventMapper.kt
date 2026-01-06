package org.onekash.kashcal.sync.strategy

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.sync.parser.ParsedEvent

/**
 * Maps ParsedEvent (from iCal parser) to Event entity (database).
 */
object EventMapper {

    /**
     * Convert a ParsedEvent to an Event entity.
     *
     * @param parsed The parsed iCal event
     * @param calendarId ID of the parent calendar
     * @param caldavUrl Full CalDAV URL for this event
     * @param etag HTTP ETag from server
     * @param existingEvent Existing event to update (preserves id and local modifications)
     * @param defaultReminderMinutes Default reminder for timed events (-1 = no default)
     * @param defaultAllDayReminderMinutes Default reminder for all-day events (-1 = no default)
     * @return Event entity ready for database upsert
     */
    fun toEntity(
        parsed: ParsedEvent,
        calendarId: Long,
        caldavUrl: String?,
        etag: String?,
        existingEvent: Event? = null,
        defaultReminderMinutes: Int = -1,
        defaultAllDayReminderMinutes: Int = -1
    ): Event {
        val now = System.currentTimeMillis()

        // Convert reminder minutes to ISO duration format
        // If server provides no reminders, apply user's default based on event type
        val reminders = if (parsed.reminderMinutes.isNotEmpty()) {
            parsed.reminderMinutes.map { minutes -> minutesToIsoDuration(minutes) }
        } else {
            // Apply default reminder based on event type
            val defaultMinutes = if (parsed.isAllDay) {
                defaultAllDayReminderMinutes
            } else {
                defaultReminderMinutes
            }
            if (defaultMinutes > 0) {
                listOf(minutesToIsoDuration(defaultMinutes))
            } else {
                null
            }
        }

        // Convert exdates list to comma-separated string
        val exdate = parsed.exdates.joinToString(",").takeIf { it.isNotEmpty() }

        // Convert rdates list to comma-separated string
        val rdate = parsed.rdates.joinToString(",").takeIf { it.isNotEmpty() }

        return Event(
            id = existingEvent?.id ?: 0,
            uid = parsed.uid,
            calendarId = calendarId,
            title = parsed.summary,
            location = parsed.location.takeIf { it.isNotBlank() },
            description = parsed.description.takeIf { it.isNotBlank() },
            startTs = parsed.startTs * 1000, // Convert seconds to millis
            endTs = parsed.endTs * 1000,
            timezone = parsed.timezone,
            isAllDay = parsed.isAllDay,
            status = parsed.status ?: "CONFIRMED",
            // RFC 5545 round-trip fields
            transp = parsed.transp ?: "OPAQUE",
            classification = parsed.classification ?: "PUBLIC",
            organizerEmail = parsed.organizerEmail,
            organizerName = parsed.organizerName,
            rrule = parsed.rrule,
            rdate = rdate,
            exdate = exdate,
            // Exception linking will be done separately after all events are processed
            originalEventId = existingEvent?.originalEventId,
            originalInstanceTime = parseRecurrenceIdToMillis(parsed.recurrenceId),
            originalSyncId = if (parsed.isException()) parsed.uid else null,
            reminders = reminders,
            extraProperties = parsed.extraProperties,
            dtstamp = parsed.dtstamp * 1000,
            caldavUrl = caldavUrl,
            etag = etag,
            sequence = parsed.sequence,
            syncStatus = SyncStatus.SYNCED,
            lastSyncError = null,
            syncRetryCount = 0,
            localModifiedAt = existingEvent?.localModifiedAt,
            serverModifiedAt = now,
            createdAt = existingEvent?.createdAt ?: now,
            updatedAt = now
        )
    }

    /**
     * Convert reminder minutes to ISO 8601 duration format.
     * Positive minutes = before event (negative trigger in iCal).
     */
    private fun minutesToIsoDuration(minutes: Int): String {
        return when {
            minutes <= 0 -> "PT0M"
            minutes < 60 -> "-PT${minutes}M"
            minutes < 1440 -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0) "-PT${hours}H" else "-PT${hours}H${mins}M"
            }
            minutes < 10080 -> { // Less than 1 week
                val days = minutes / 1440
                val hours = (minutes % 1440) / 60
                if (hours == 0) "-P${days}D" else "-P${days}DT${hours}H"
            }
            else -> {
                val weeks = minutes / 10080
                "-P${weeks}W"
            }
        }
    }

    /**
     * Parse RECURRENCE-ID value to epoch milliseconds.
     * Format: YYYYMMDDTHHMMSSZ or YYYYMMDDTHHMMSS or YYYYMMDD
     */
    private fun parseRecurrenceIdToMillis(recurrenceId: String?): Long? {
        if (recurrenceId == null) return null

        return try {
            // Remove VALUE=DATE: prefix if present
            val value = recurrenceId
                .replace("VALUE=DATE:", "")
                .replace("VALUE=DATE-TIME:", "")
                .trim()

            when {
                // Full datetime with Z (UTC)
                value.length == 16 && value.endsWith("Z") -> {
                    parseICalDateTime(value.dropLast(1), isUtc = true)
                }
                // Full datetime without Z (local)
                value.length == 15 && value.contains("T") -> {
                    parseICalDateTime(value, isUtc = false)
                }
                // Date only
                value.length == 8 -> {
                    parseICalDate(value)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse iCal DATETIME format (YYYYMMDDTHHMMSS) to epoch millis.
     */
    private fun parseICalDateTime(value: String, isUtc: Boolean): Long {
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        val hour = value.substring(9, 11).toInt()
        val minute = value.substring(11, 13).toInt()
        val second = value.substring(13, 15).toInt()

        val cal = java.util.Calendar.getInstance(
            if (isUtc) java.util.TimeZone.getTimeZone("UTC")
            else java.util.TimeZone.getDefault()
        )
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Parse iCal DATE format (YYYYMMDD) to epoch millis (start of day UTC).
     */
    private fun parseICalDate(value: String): Long {
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Public accessor for parsing RECURRENCE-ID to millis.
     * Used by PullStrategy to find existing exceptions.
     */
    fun parseOriginalInstanceTime(recurrenceId: String?): Long? {
        return parseRecurrenceIdToMillis(recurrenceId)
    }
}
