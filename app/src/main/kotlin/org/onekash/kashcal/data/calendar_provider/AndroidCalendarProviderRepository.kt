package org.onekash.kashcal.data.calendar_provider

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.text.format.DateUtils
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.CalendarErrorException
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android CalendarProvider implementation of [CalendarProviderRepository].
 *
 * Queries CalendarContract via ContentResolver. All methods handle
 * SecurityException gracefully (returns empty results if permission revoked).
 *
 * Uses Instances.CONTENT_URI (ms-based) for time range queries — not
 * CONTENT_BY_DAY_URI which uses Julian days (different from KashCal's YYYYMMDD day codes).
 */
@Singleton
class AndroidCalendarProviderRepository @Inject constructor(
    private val contentResolver: ContentResolver
) : CalendarProviderRepository {

    companion object {
        private const val TAG = "CalProviderRepo"

        private val INSTANCES_PROJECTION = arrayOf(
            Instances._ID,              // 0
            Instances.EVENT_ID,         // 1
            Instances.TITLE,            // 2
            Instances.DESCRIPTION,      // 3
            Instances.EVENT_LOCATION,   // 4
            Instances.BEGIN,            // 5
            Instances.END,              // 6
            Instances.ALL_DAY,          // 7
            Instances.RRULE,            // 8
            Instances.CALENDAR_ID,      // 9
            Instances.CALENDAR_DISPLAY_NAME, // 10
            Instances.STATUS,           // 11
            Instances.AVAILABILITY,     // 12
            Instances.HAS_ALARM,        // 13
            Instances.SELF_ATTENDEE_STATUS,  // 14
            Instances.CALENDAR_ACCESS_LEVEL, // 15
            Instances.ORIGINAL_ID,       // 16 - Master event ID for exceptions
            Instances.ORIGINAL_INSTANCE_TIME, // 17 - Original occurrence time for exceptions
            Instances.EVENT_TIMEZONE,    // 18 - Event timezone (exception's for modified occurrences)
            // Color channels — read via getColumnIndexOrThrow to decouple from projection order.
            Instances.CALENDAR_COLOR,    // 19 - raw calendar color (identity)
            Instances.EVENT_COLOR,       // 20 - raw event override (0 = no override)
            Instances.DTSTART            // 21 - master event row's DTSTART (anchors first-occurrence rule)
        )

        // Column indices
        private const val COL_ID = 0
        private const val COL_EVENT_ID = 1
        private const val COL_TITLE = 2
        private const val COL_DESCRIPTION = 3
        private const val COL_LOCATION = 4
        private const val COL_BEGIN = 5
        private const val COL_END = 6
        private const val COL_ALL_DAY = 7
        private const val COL_RRULE = 8
        private const val COL_CALENDAR_ID = 9
        private const val COL_CALENDAR_DISPLAY_NAME = 10
        private const val COL_STATUS = 11
        private const val COL_AVAILABILITY = 12
        private const val COL_HAS_ALARM = 13
        private const val COL_SELF_ATTENDEE_STATUS = 14
        private const val COL_ACCESS_LEVEL = 15
        private const val COL_ORIGINAL_ID = 16
        private const val COL_ORIGINAL_INSTANCE_TIME = 17
        private const val COL_EVENT_TIMEZONE = 18
        private const val COL_DTSTART = 21

        private const val SELECTION_VISIBLE = "${Calendars.VISIBLE} = 1"
        private const val SELECTION_HIDE_DECLINED = "$SELECTION_VISIBLE AND " +
            "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED}"

        private const val SORT_ORDER = "${Instances.BEGIN} ASC, ${Instances.END} ASC"
    }

    override suspend fun getDeviceCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.VISIBLE,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                ),
                null, null, null
            )?.use { cursor ->
                val results = mutableListOf<DeviceCalendar>()
                while (cursor.moveToNext()) {
                    results.add(
                        DeviceCalendar(
                            id = cursor.getLong(0),
                            displayName = cursor.getString(1).orEmpty(),
                            color = cursor.getInt(2),
                            accountName = cursor.getString(3).orEmpty(),
                            accountType = cursor.getString(4).orEmpty(),
                            visible = cursor.getInt(5) == 1,
                            accessLevel = cursor.getInt(6)
                        )
                    )
                }
                results
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            emptyList()
        }
    }

    override suspend fun getInstancesForDayRange(
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean
    ): List<DeviceCalendarInstance> = withContext(Dispatchers.IO) {
        if (enabledCalendarIds.isEmpty()) return@withContext emptyList()

        try {
            // Extend by 1 day to catch events spanning midnight
            val startMs = dayCodeToStartOfDayMs(startDayCode) - DateUtils.DAY_IN_MILLIS
            val endMs = dayCodeToEndOfDayMs(endDayCode) + DateUtils.DAY_IN_MILLIS

            val builder = Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)

            val selection = if (hideDeclined) SELECTION_HIDE_DECLINED else SELECTION_VISIBLE

            val instances = contentResolver.query(
                builder.build(), INSTANCES_PROJECTION, selection, null, SORT_ORDER
            )?.use { cursor -> mapToInstances(cursor, enabledCalendarIds) }
                .orEmpty()

            // Batch fetch reminders to avoid N+1 queries
            populateReminders(instances)
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            emptyList()
        }
    }

    private fun mapToInstances(
        cursor: android.database.Cursor,
        enabledCalendarIds: Set<Long>
    ): List<DeviceCalendarInstance> {
        val results = mutableListOf<DeviceCalendarInstance>()
        // Resolve new columns by name — defensive against future projection reorders.
        val colCalendarColor = cursor.getColumnIndexOrThrow(Instances.CALENDAR_COLOR)
        val colEventColor = cursor.getColumnIndexOrThrow(Instances.EVENT_COLOR)
        while (cursor.moveToNext()) {
            val calendarId = cursor.getLong(COL_CALENDAR_ID)
            if (calendarId !in enabledCalendarIds) continue

            val beginMs = cursor.getLong(COL_BEGIN)
            val endMs = cursor.getLong(COL_END)
            val isAllDay = cursor.getInt(COL_ALL_DAY) == 1
            val accessLevel = cursor.getInt(COL_ACCESS_LEVEL)

            // All-day events: convert CalendarProvider's exclusive end (midnight next day)
            // to inclusive end (last ms of last day), matching Room Event.endTs convention.
            // Used for both endTs and endDay to maintain consistent inclusive semantics.
            val inclusiveEndMs = if (isAllDay && endMs > beginMs) endMs - 1 else endMs

            // ORIGINAL_ID is null for regular events, non-null for exceptions
            val originalId = if (!cursor.isNull(COL_ORIGINAL_ID)) {
                cursor.getLong(COL_ORIGINAL_ID)
            } else null

            val originalInstanceTime = if (!cursor.isNull(COL_ORIGINAL_INSTANCE_TIME)) {
                cursor.getLong(COL_ORIGINAL_INSTANCE_TIME)
            } else null

            val status = cursor.getInt(COL_STATUS)

            // Skip STATUS_CANCELED exception instances — these represent deleted
            // occurrences of recurring events. CalendarProvider should suppress them
            // automatically, but some OEM implementations don't.
            if (status == CalendarContract.Events.STATUS_CANCELED && originalId != null) continue

            val rruleString = cursor.getString(COL_RRULE)

            results.add(
                DeviceCalendarInstance(
                    instanceId = cursor.getLong(COL_ID),
                    eventId = cursor.getLong(COL_EVENT_ID),
                    title = cursor.getString(COL_TITLE).orEmpty(),
                    description = cursor.getString(COL_DESCRIPTION).orEmpty(),
                    location = cursor.getString(COL_LOCATION).orEmpty(),
                    startTs = beginMs,
                    endTs = inclusiveEndMs,
                    startDay = DateTimeUtils.eventTsToDayCode(beginMs, isAllDay),
                    endDay = DateTimeUtils.eventTsToEndDayCode(
                        endTs = inclusiveEndMs,
                        startTs = beginMs,
                        isAllDay = isAllDay
                    ),
                    isAllDay = isAllDay,
                    hasRrule = !rruleString.isNullOrEmpty(),
                    rrule = rruleString,
                    reminders = emptyList(), // Populated by batch query after
                    calendarId = calendarId,
                    calendarDisplayName = cursor.getString(COL_CALENDAR_DISPLAY_NAME).orEmpty(),
                    calendarColor = cursor.getInt(colCalendarColor),
                    eventColor = cursor.getInt(colEventColor).takeIf { it != 0 },
                    status = status,
                    availability = cursor.getInt(COL_AVAILABILITY),
                    hasAlarm = cursor.getInt(COL_HAS_ALARM) == 1,
                    selfAttendeeStatus = cursor.getInt(COL_SELF_ATTENDEE_STATUS),
                    isWritable = accessLevel >= 500, // CAL_ACCESS_CONTRIBUTOR
                    originalId = originalId,
                    originalInstanceTime = originalInstanceTime,
                    timezone = cursor.getString(COL_EVENT_TIMEZONE),
                    eventStartTs = cursor.getLong(COL_DTSTART),
                )
            )
        }
        return results
    }

    override suspend fun searchInstances(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean
    ): List<DeviceCalendarInstance> = withContext(Dispatchers.IO) {
        if (enabledCalendarIds.isEmpty() || query.isBlank()) return@withContext emptyList()

        try {
            val startMs = dayCodeToStartOfDayMs(startDayCode) - DateUtils.DAY_IN_MILLIS
            val endMs = dayCodeToEndOfDayMs(endDayCode) + DateUtils.DAY_IN_MILLIS

            // Instances.CONTENT_SEARCH_URI uses path: instances/when/{begin}/{end}/{query}
            val builder = Instances.CONTENT_SEARCH_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)
            builder.appendPath(query)

            val selection = if (hideDeclined) SELECTION_HIDE_DECLINED else SELECTION_VISIBLE

            val instances = contentResolver.query(
                builder.build(), INSTANCES_PROJECTION, selection, null, SORT_ORDER
            )?.use { cursor -> mapToInstances(cursor, enabledCalendarIds) }
                .orEmpty()

            // Batch fetch reminders to avoid N+1 queries
            populateReminders(instances)
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            emptyList()
        }
    }

    override suspend fun suggestTitlesByPrefix(
        prefix: String,
        sinceMs: Long,
        untilMs: Long,
        visibleCalendarIds: Set<Long>,
        minFreq: Int,
        limit: Int
    ): List<org.onekash.kashcal.data.db.dao.TitleSuggestion> = withContext(Dispatchers.IO) {
        if (visibleCalendarIds.isEmpty() || prefix.isBlank()) return@withContext emptyList()

        try {
            val calendarIdList = visibleCalendarIds.joinToString(",")
            // Recurring events (RRULE non-null, non-empty) bypass the DTSTART window;
            // the master row's DTSTART is the first occurrence and may be very old even
            // while the series is still active. Non-recurring events are bound to
            // [sinceMs, untilMs]. LIKE with COLLATE NOCASE handles ASCII case folding.
            val selection = """
                ${CalendarContract.Events.TITLE} LIKE ? COLLATE NOCASE
                AND ${CalendarContract.Events.CALENDAR_ID} IN ($calendarIdList)
                AND ${CalendarContract.Events.DELETED} = 0
                AND ${CalendarContract.Events.TITLE} IS NOT NULL
                AND LENGTH(TRIM(${CalendarContract.Events.TITLE})) > 0
                AND ${CalendarContract.Events.ORIGINAL_ID} IS NULL
                AND (
                    (${CalendarContract.Events.RRULE} IS NOT NULL AND ${CalendarContract.Events.RRULE} != '')
                    OR (${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?)
                )
            """.trimIndent()
            val args = arrayOf("${prefix.trim()}%", sinceMs.toString(), untilMs.toString())
            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART
            )

            val rows = contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val dtstartIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val out = mutableListOf<Pair<String, Long>>()
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIdx)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    val dtstart = cursor.getLong(dtstartIdx)
                    out.add(title to dtstart)
                }
                out
            }.orEmpty()

            // Cross-calendar dedup: same (title.lowercase(), dtstart) on multiple calendars
            // (e.g., a Google invite visible on personal + work accounts) counts as ONE use,
            // not N. Case-insensitive to match the Fake's contract and handle providers that
            // might round-trip the same invite with different casing.
            rows.distinctBy { it.first.lowercase() to it.second }
                .groupBy { it.first.lowercase() }
                .map { (_, entries) ->
                    val latest = entries.maxByOrNull { it.second }!!
                    org.onekash.kashcal.data.db.dao.TitleSuggestion(
                        title = latest.first,
                        freq = entries.size,
                        lastUsed = latest.second
                    )
                }
                .filter { it.freq >= minFreq }
                .sortedWith(
                    compareByDescending<org.onekash.kashcal.data.db.dao.TitleSuggestion> { it.freq }
                        .thenByDescending { it.lastUsed }
                )
                .take(limit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            emptyList()
        }
    }

    /**
     * Populate instances with reminder data via batch query.
     * Returns new list with reminders field populated.
     */
    private suspend fun populateReminders(
        instances: List<DeviceCalendarInstance>
    ): List<DeviceCalendarInstance> {
        if (instances.isEmpty()) return instances

        val eventIds = instances.map { it.eventId }.toSet()
        val remindersMap = getRemindersForEvents(eventIds)

        return instances.map { instance ->
            val reminders = remindersMap[instance.eventId].orEmpty()
            instance.copy(reminders = reminders)
        }
    }

    override suspend fun pruneStaleCalendarIds(
        dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore
    ) {
        val storedIds = dataStore.getEnabledDeviceCalendarIds()
        if (storedIds.isEmpty()) return

        val actualCalendarIds = getDeviceCalendars().map { it.id }.toSet()
        val staleIds = storedIds - actualCalendarIds
        if (staleIds.isNotEmpty()) {
            Log.i(TAG, "Pruning ${staleIds.size} stale calendar IDs: $staleIds")
            dataStore.setEnabledDeviceCalendarIds(storedIds - staleIds)
        }
    }

    override suspend fun ensureCalendarVisible(calendarId: Long) = withContext(Dispatchers.IO) {
        val account = readCalendarAccount(calendarId)
        if (account == null) {
            Log.w(TAG, "ensureCalendarVisible($calendarId): calendar row not found, skipping")
            return@withContext
        }

        val values = buildCalendarVisibleValues()
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        try {
            val rowsUpdated = contentResolver.update(uri, values, null, null)
            Log.i(TAG, "ensureCalendarVisible($calendarId): SYNC_EVENTS=1, VISIBLE=1, rowsUpdated=$rowsUpdated")
        } catch (e: SecurityException) {
            Log.w(TAG, "ensureCalendarVisible($calendarId): WRITE_CALENDAR blocked, skipping", e)
            return@withContext
        } catch (e: Exception) {
            Log.w(TAG, "ensureCalendarVisible($calendarId): update failed, skipping", e)
            return@withContext
        }

        if (shouldSkipRequestSync(account)) {
            Log.d(TAG, "ensureCalendarVisible($calendarId): skipping requestSync for account type='${account.type}'")
            return@withContext
        }

        try {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            }
            ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
            Log.d(TAG, "ensureCalendarVisible($calendarId): requested sync on ${account.type}")
        } catch (e: SecurityException) {
            Log.w(TAG, "ensureCalendarVisible($calendarId): requestSync blocked", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "ensureCalendarVisible($calendarId): requestSync rejected account", e)
        } catch (e: Exception) {
            Log.w(TAG, "ensureCalendarVisible($calendarId): requestSync failed", e)
        }
    }

    /**
     * Read the ACCOUNT_NAME/ACCOUNT_TYPE of a calendar row.
     * Returns null if the row doesn't exist (race with sync adapter deletion)
     * or if permission is revoked.
     */
    private fun readCalendarAccount(calendarId: Long): Account? {
        return try {
            contentResolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
                arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0).orEmpty()
                val type = cursor.getString(1).orEmpty()
                // Account(String, String) throws IllegalArgumentException on blanks;
                // guard explicitly so shouldSkipRequestSync can assume non-blank inputs.
                if (name.isBlank() || type.isBlank()) null else Account(name, type)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "readCalendarAccount($calendarId): permission revoked", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "readCalendarAccount($calendarId): query failed", e)
            null
        }
    }

    // ==================== Write Operations (Phase 3) ====================

    override suspend fun createEvent(
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long?,
        isAllDay: Boolean,
        rrule: String?,
        duration: String?,
        timezone: String,
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val values = buildEventValues(title, description, location, startTs, endTs, isAllDay, rrule, duration, timezone)
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId)
            values.put(CalendarContract.Events.AVAILABILITY, availability)
            eventColor?.let { values.put(CalendarContract.Events.EVENT_COLOR, it) }

            // Use batch operation for atomicity (event + reminders)
            val ops = ArrayList<android.content.ContentProviderOperation>()

            // Insert event
            ops.add(
                android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(values)
                    .build()
            )

            // Insert reminders (reference event by back-reference)
            for (minutes in reminders) {
                ops.add(
                    android.content.ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                        .withValue(CalendarContract.Reminders.MINUTES, minutes)
                        .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        .build()
                )
            }

            val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            val eventUri = results[0].uri
            val eventId = ContentUris.parseId(eventUri!!)

            Log.d(TAG, "Created device event: id=$eventId, title=${title.take(20)}...")
            Result.success(eventId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied creating event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun updateEvent(
        eventId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long?,
        isAllDay: Boolean,
        rrule: String?,
        duration: String?,
        timezone: String,
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val isException = rrule == null && isExceptionEvent(eventId)
            val values = buildEventValues(title, description, location, startTs, endTs, isAllDay, rrule, duration, timezone, isException = isException)
            values.put(CalendarContract.Events.AVAILABILITY, availability)
            eventColor?.let { values.put(CalendarContract.Events.EVENT_COLOR, it) }
                ?: values.putNull(CalendarContract.Events.EVENT_COLOR)

            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rowsUpdated = contentResolver.update(eventUri, values, null, null)
            if (rowsUpdated == 0) {
                return@withContext Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound))
            }

            // Clear existing reminders and rewrite
            contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString())
            )

            for (minutes in reminders) {
                val reminderValues = android.content.ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }

            Log.d(TAG, "Updated device event: id=$eventId")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied updating event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun deleteEvent(eventId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rowsDeleted = contentResolver.delete(eventUri, null, null)
            if (rowsDeleted == 0) {
                return@withContext Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound))
            }

            Log.d(TAG, "Deleted device event: id=$eventId")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied deleting event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun createException(
        calendarId: Long,
        masterEventId: Long,
        originalInstanceTime: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        timezone: String,
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val values = buildEventValues(title, description, location, startTs, endTs, isAllDay, null, null, timezone, isException = true)
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId)
            values.put(CalendarContract.Events.AVAILABILITY, availability)
            eventColor?.let { values.put(CalendarContract.Events.EVENT_COLOR, it) }
            values.put(CalendarContract.Events.ORIGINAL_ID, masterEventId)
            val masterSyncId = getMasterSyncId(masterEventId)
            if (masterSyncId != null) {
                values.put(CalendarContract.Events.ORIGINAL_SYNC_ID, masterSyncId)
            }
            val normalizedOrigTime = if (isAllDay)
                DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime
            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, normalizedOrigTime)
            values.put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (isAllDay) 1 else 0)
            values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)

            // Use batch for atomicity
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(
                android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(values)
                    .build()
            )

            for (minutes in reminders) {
                ops.add(
                    android.content.ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                        .withValue(CalendarContract.Reminders.MINUTES, minutes)
                        .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        .build()
                )
            }

            val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            val eventUri = results[0].uri
            val eventId = ContentUris.parseId(eventUri!!)

            Log.d(TAG, "Created exception event: id=$eventId, masterId=$masterEventId, origTime=$originalInstanceTime")
            Result.success(eventId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied creating exception", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating exception", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun deleteSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedTime = if (isAllDay)
                DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime

            // If an exception event already exists (previously edited occurrence),
            // update its status to CANCELED
            val existingExceptionId = findExceptionEventId(masterEventId, originalInstanceTime, isAllDay)
            if (existingExceptionId != null) {
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                }
                val exceptionUri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI, existingExceptionId
                )
                contentResolver.update(exceptionUri, values, null, null)
                Log.d(TAG, "Updated exception $existingExceptionId to STATUS_CANCELED")
            } else {
                // No existing exception — insert a new STATUS_CANCELED exception event.
                // CalendarProvider uses exception events (not EXDATE) to track canceled occurrences.
                val masterEvent = getDeviceEvent(masterEventId)
                    ?: return@withContext Result.failure(
                        CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                    )

                // Build ContentValues directly — exception events are non-recurring and
                // must use DTEND (not DURATION). CalendarProvider requires DTEND for
                // non-recurring events; using DURATION causes the exception to be
                // malformed and not properly suppress the original instance.
                val durationMs = parseDurationMs(masterEvent.duration, isAllDay)
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, masterEvent.calendarId)
                    put(CalendarContract.Events.TITLE, masterEvent.title)
                    put(CalendarContract.Events.DTSTART, normalizedTime)
                    put(CalendarContract.Events.DTEND, normalizedTime + durationMs)
                    put(CalendarContract.Events.ALL_DAY, if (isAllDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (isAllDay) "UTC" else masterEvent.timezone)
                    put(CalendarContract.Events.ORIGINAL_ID, masterEventId)
                    put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, normalizedTime)
                    put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (isAllDay) 1 else 0)
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                    // Exception events must not have recurrence fields
                    putNull(CalendarContract.Events.RRULE)
                    putNull(CalendarContract.Events.RDATE)
                    putNull(CalendarContract.Events.EXDATE)
                    putNull(CalendarContract.Events.EXRULE)
                }
                val masterSyncId = getMasterSyncId(masterEventId)
                if (masterSyncId != null) {
                    values.put(CalendarContract.Events.ORIGINAL_SYNC_ID, masterSyncId)
                }

                val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri == null) {
                    return@withContext Result.failure(
                        CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed("Failed to insert canceled exception"))
                    )
                }
                Log.d(TAG, "Inserted STATUS_CANCELED exception for master $masterEventId, origTime=$normalizedTime, uri=$uri")
            }

            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied deleting occurrence", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting occurrence", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun deleteThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val masterEvent = getDeviceEvent(masterEventId)
                ?: return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )

            // If deleting from the first occurrence, delete entire event
            if (fromTimeMs <= masterEvent.startTs) {
                return@withContext deleteEvent(masterEventId)
            }

            val rrule = masterEvent.rrule
                ?: return@withContext Result.failure(
                    CalendarErrorException(
                        CalendarError.DeviceCalendar.WriteFailed("Recurring event has no RRULE")
                    )
                )

            val truncatedRrule = org.onekash.kashcal.util.RruleUtils.addUntilToRrule(
                rrule, fromTimeMs - 1, isAllDay
            )

            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.RRULE, truncatedRrule)
            }
            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, masterEventId)
            val rowsUpdated = contentResolver.update(eventUri, values, null, null)
            if (rowsUpdated == 0) {
                return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )
            }

            // Delete orphaned exception events in the truncated range.
            // CalendarProvider does not auto-cleanup exceptions when RRULE is shortened.
            val normalizedFrom = if (isAllDay)
                DateTimeUtils.normalizeToUtcMidnight(fromTimeMs) else fromTimeMs
            var deletedExceptions = 0
            try {
                contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID),
                    "${CalendarContract.Events.ORIGINAL_ID} = ? AND ${CalendarContract.Events.ORIGINAL_INSTANCE_TIME} >= ?",
                    arrayOf(masterEventId.toString(), normalizedFrom.toString()),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val exceptionId = cursor.getLong(0)
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, exceptionId)
                        contentResolver.delete(uri, null, null)
                        deletedExceptions++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up $deletedExceptions+ orphaned exceptions for master $masterEventId", e)
            }

            Log.d(TAG, "Truncated device event RRULE: id=$masterEventId, from=$fromTimeMs, isAllDay=$isAllDay, deletedExceptions=$deletedExceptions")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied truncating RRULE", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error truncating RRULE", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun editThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean,
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long?,
        rrule: String?,
        duration: String?,
        timezone: String,
        reminders: List<Int>,
        availability: Int,
        eventColor: Int?,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val masterEvent = getDeviceEvent(masterEventId)
                ?: return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )

            // First-occurrence shortcut: a split at-or-before the
            // master's start collapses to "edit all events."
            if (fromTimeMs <= masterEvent.startTs) {
                val updateResult = updateEvent(
                    eventId = masterEventId,
                    title = title,
                    description = description,
                    location = location,
                    startTs = startTs,
                    endTs = endTs,
                    isAllDay = isAllDay,
                    rrule = rrule,
                    duration = duration,
                    timezone = timezone,
                    reminders = reminders,
                    availability = availability,
                    eventColor = eventColor,
                )
                return@withContext updateResult.map { masterEventId }
            }

            val masterRrule = masterEvent.rrule
                ?: return@withContext Result.failure(
                    CalendarErrorException(
                        CalendarError.DeviceCalendar.WriteFailed("Recurring event has no RRULE")
                    )
                )

            // Count the master's expanded instances strictly before
            // the split point so the COUNT-based RRULE branch can
            // preserve the total instance count across the split.
            // Without this, a COUNT=N series produces N more instances
            // on the new row instead of N-pastCount.
            val pastCount = countInstancesInRange(
                masterEventId,
                masterEvent.startTs,
                fromTimeMs,
            )

            // Degenerate COUNT split (pastCount==0 or pastCount>=total)
            // would yield invalid COUNT=0. Fall back to in-place
            // ALL_EVENTS update on the master — the user's rrule
            // wins (including null, which converts the master to
            // non-recurring).
            if (org.onekash.kashcal.util.RruleUtils.isDegenerateCountSplit(masterRrule, pastCount)) {
                val updateResult = updateEvent(
                    eventId = masterEventId,
                    title = title,
                    description = description,
                    location = location,
                    startTs = startTs,
                    endTs = endTs,
                    isAllDay = isAllDay,
                    rrule = rrule,
                    duration = duration,
                    timezone = timezone,
                    reminders = reminders,
                    availability = availability,
                    eventColor = eventColor,
                )
                return@withContext updateResult.map { masterEventId }
            }

            // rrule == null is the user's "Does not repeat" pick — the
            // helper returns null new-series for that, and we let the
            // non-recurring new row pass through to buildEventValues.
            val (truncatedRrule, splitNewSeriesRrule) =
                org.onekash.kashcal.util.RruleUtils.splitRruleAtTime(
                    masterRrule = masterRrule,
                    userRrule = rrule,
                    untilMs = fromTimeMs - 1,
                    pastCount = pastCount,
                    isAllDay = masterEvent.isAllDay,
                )
            val newSeriesRrule = splitNewSeriesRrule

            // Build the new-row values (for the future series). Reuse
            // the same shape as createEvent so reminder back-references
            // line up.
            val newEventValues = buildEventValues(
                title, description, location, startTs, endTs, isAllDay, newSeriesRrule, duration, timezone
            ).apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.AVAILABILITY, availability)
                eventColor?.let { put(CalendarContract.Events.EVENT_COLOR, it) }
            }

            val masterUri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, masterEventId
            )
            val masterTruncate = android.content.ContentValues().apply {
                put(CalendarContract.Events.RRULE, truncatedRrule)
            }

            // Wrap insert + master truncate in a single applyBatch so a
            // failure during INSERT leaves the master untouched. Order:
            // INSERT first (so any constraint failure bails before we
            // mutate the master), then UPDATE the master.
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(
                android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(newEventValues)
                    .build()
            )
            for (minutes in reminders) {
                ops.add(
                    android.content.ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                        .withValue(CalendarContract.Reminders.MINUTES, minutes)
                        .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        .build()
                )
            }
            ops.add(
                android.content.ContentProviderOperation.newUpdate(masterUri)
                    .withValues(masterTruncate)
                    .build()
            )

            val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            val newEventUri = results[0].uri
                ?: return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed("INSERT did not return a URI"))
                )
            val newEventId = ContentUris.parseId(newEventUri)

            // Cleanup orphaned exception children whose
            // originalInstanceTime falls in the truncated half.
            // CalendarProvider doesn't auto-cleanup these when RRULE
            // is shortened. Best-effort: a failure here doesn't
            // invalidate the split; log and move on.
            val normalizedFrom = if (isAllDay)
                DateTimeUtils.normalizeToUtcMidnight(fromTimeMs) else fromTimeMs
            try {
                contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID),
                    "${CalendarContract.Events.ORIGINAL_ID} = ? AND ${CalendarContract.Events.ORIGINAL_INSTANCE_TIME} >= ?",
                    arrayOf(masterEventId.toString(), normalizedFrom.toString()),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val exceptionId = cursor.getLong(0)
                        val uri = ContentUris.withAppendedId(
                            CalendarContract.Events.CONTENT_URI, exceptionId
                        )
                        contentResolver.delete(uri, null, null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up orphaned exceptions for master $masterEventId", e)
            }

            Log.d(TAG, "Split device event: master=$masterEventId, newId=$newEventId, fromTimeMs=$fromTimeMs")
            Result.success(newEventId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied splitting recurring event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error splitting recurring event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, newCalendarId)
            }

            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rowsUpdated = contentResolver.update(eventUri, values, null, null)
            if (rowsUpdated == 0) {
                return@withContext Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound))
            }

            // Move exception events to same calendar (CalendarProvider doesn't cascade)
            try {
                val exValues = android.content.ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, newCalendarId)
                }
                contentResolver.update(
                    CalendarContract.Events.CONTENT_URI,
                    exValues,
                    "${CalendarContract.Events.ORIGINAL_ID} = ?",
                    arrayOf(eventId.toString())
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to move exception events for master $eventId", e)
            }

            Log.d(TAG, "Moved event $eventId to calendar $newCalendarId")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied moving event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error moving event", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    override suspend fun getMaxReminders(calendarId: Long): Int = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.MAX_REMINDERS),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calendarId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(0).coerceAtLeast(1)
                } else {
                    5 // Default fallback
                }
            } ?: 5
        } catch (e: Exception) {
            Log.w(TAG, "Error getting max reminders", e)
            5 // Default fallback
        }
    }

    override suspend fun isEventActive(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(eventId.toString()),
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied checking event active state", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking event active state", e)
            false
        }
    }

    override suspend fun getDeviceEvent(eventId: Long): DeviceEvent? = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                eventsProjection,
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mapToDeviceEvent(cursor)
                } else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading event", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading event", e)
            null
        }
    }

    override suspend fun getDeviceEventWithExceptions(
        masterEventId: Long
    ): Pair<DeviceEvent, List<DeviceEvent>>? = withContext(Dispatchers.IO) {
        val master = getDeviceEvent(masterEventId) ?: return@withContext null

        val exceptions = try {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                eventsProjection,
                "${CalendarContract.Events.ORIGINAL_ID} = ?",
                arrayOf(masterEventId.toString()),
                "${CalendarContract.Events.ORIGINAL_INSTANCE_TIME} ASC"
            )?.use { cursor ->
                val results = mutableListOf<DeviceEvent>()
                while (cursor.moveToNext()) {
                    results.add(mapToDeviceEvent(cursor))
                }
                results
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading exceptions for master $masterEventId", e)
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading exceptions for master $masterEventId", e)
            return@withContext null
        }

        master to exceptions
    }

    override suspend fun getReminders(eventId: Long): List<Int> = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                "${CalendarContract.Reminders.MINUTES} ASC"
            )?.use { cursor ->
                val results = mutableListOf<Int>()
                while (cursor.moveToNext()) {
                    results.add(cursor.getInt(0))
                }
                results
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Error reading reminders", e)
            emptyList()
        }
    }

    /**
     * Batch fetch reminders for multiple events in a single query.
     * Avoids N+1 query problem when loading many instances.
     *
     * @param eventIds Set of event IDs to fetch reminders for
     * @return Map of eventId to sorted list of reminder minutes
     */
    override suspend fun getRemindersForEvents(eventIds: Set<Long>): Map<Long, List<Int>> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyMap()

        try {
            val results = mutableMapOf<Long, MutableList<Int>>()

            // Chunk to avoid SQLite variable limit (default 999)
            for (chunk in eventIds.toList().chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val selection = "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders)"
                val args = chunk.map { it.toString() }.toTypedArray()

                contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Reminders.EVENT_ID,
                        CalendarContract.Reminders.MINUTES
                    ),
                    selection,
                    args,
                    "${CalendarContract.Reminders.EVENT_ID} ASC, ${CalendarContract.Reminders.MINUTES} ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val eventId = cursor.getLong(0)
                        val minutes = cursor.getInt(1)
                        results.getOrPut(eventId) { mutableListOf() }.add(minutes)
                    }
                }
            }

            results
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked during reminders batch query", e)
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Error batch reading reminders", e)
            emptyMap()
        }
    }

    override suspend fun findExceptionEventId(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Long? = withContext(Dispatchers.IO) {
        try {
            val normalizedTime = if (isAllDay)
                DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.ORIGINAL_ID} = ? AND ${CalendarContract.Events.ORIGINAL_INSTANCE_TIME} = ?",
                arrayOf(masterEventId.toString(), normalizedTime.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied finding exception", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding exception event", e)
            null
        }
    }

    /**
     * Check whether an event is an exception (has ORIGINAL_ID set).
     * Used by [updateEvent] to determine whether to omit RRULE from ContentValues.
     */
    /**
     * Count the number of instances of [eventId] whose Begin falls
     * in `[rangeStartMs, rangeEndMs)`. Used by [editThisAndFuture]
     * to compute the master's pre-split instance count for COUNT
     * preservation. Returns 0 on permission errors or empty results.
     */
    private fun countInstancesInRange(
        eventId: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): Int {
        if (rangeEndMs <= rangeStartMs) return 0
        return try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, rangeStartMs)
                ContentUris.appendId(this, rangeEndMs)
            }.build()
            contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances._ID),
                "${CalendarContract.Instances.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { it.count } ?: 0
        } catch (e: SecurityException) {
            Log.w(TAG, "countInstancesInRange permission denied", e)
            0
        } catch (e: Exception) {
            Log.w(TAG, "countInstancesInRange query failed", e)
            0
        }
    }

    private fun isExceptionEvent(eventId: Long): Boolean {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.ORIGINAL_ID),
            null, null, null
        )
        return cursor?.use { if (it.moveToFirst()) !it.isNull(0) else false } ?: false
    }

    /**
     * Read the _SYNC_ID of an event (set by sync adapters).
     * Used by [createException] to set ORIGINAL_SYNC_ID on exception events
     * so sync adapters can associate exceptions with their master events.
     */
    internal fun getMasterSyncId(eventId: Long): String? {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._SYNC_ID),
            null, null, null
        )
        return cursor?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    }

    // ==================== Reminder Operations (Phase 4) ====================

    override suspend fun getNextUpcomingReminder(
        enabledCalendarIds: Set<Long>,
        afterMs: Long
    ): UpcomingDeviceReminder? = withContext(Dispatchers.IO) {
        if (enabledCalendarIds.isEmpty()) return@withContext null

        try {
            // Query instances for next 30 days that have alarms
            val startMs = afterMs
            val endMs = afterMs + (30L * DateUtils.DAY_IN_MILLIS)

            val builder = Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)

            // Query instances with alarms. Selection unconditionally excludes
            // self-declined events: the alarm pipeline treats decline as "no",
            // independent of the display-side "Show declined" toggle.
            val selection = buildUpcomingReminderSelection()

            val instancesWithAlarms = mutableListOf<InstanceWithAlarm>()

            contentResolver.query(
                builder.build(),
                INSTANCES_PROJECTION,
                selection,
                null,
                SORT_ORDER
            )?.use { cursor ->
                val colCalendarColor = cursor.getColumnIndexOrThrow(Instances.CALENDAR_COLOR)
                val colEventColor = cursor.getColumnIndexOrThrow(Instances.EVENT_COLOR)
                while (cursor.moveToNext()) {
                    val calendarId = cursor.getLong(COL_CALENDAR_ID)
                    if (calendarId !in enabledCalendarIds) continue

                    val eventId = cursor.getLong(COL_EVENT_ID)
                    val beginMs = cursor.getLong(COL_BEGIN)
                    val isAllDay = cursor.getInt(COL_ALL_DAY) == 1
                    val title = cursor.getString(COL_TITLE).orEmpty()
                    val location = cursor.getString(COL_LOCATION)
                    val calendarColorValue = cursor.getInt(colCalendarColor)
                    val eventColorValue = cursor.getInt(colEventColor).takeIf { it != 0 }

                    instancesWithAlarms.add(
                        InstanceWithAlarm(
                            eventId = eventId,
                            occurrenceStartTs = beginMs,
                            title = title,
                            location = location,
                            isAllDay = isAllDay,
                            // Reminder notifications show effective display (override if set, else calendar).
                            calendarColor = eventColorValue ?: calendarColorValue,
                            calendarId = calendarId
                        )
                    )
                }
            }

            if (instancesWithAlarms.isEmpty()) return@withContext null

            // For each instance, get its reminders and calculate trigger times
            var earliest: UpcomingDeviceReminder? = null

            for (instance in instancesWithAlarms) {
                val reminders = getReminders(instance.eventId)
                for (reminderMinutes in reminders) {
                    val triggerTime = calculateReminderTriggerTime(
                        occurrenceStartTs = instance.occurrenceStartTs,
                        reminderMinutes = reminderMinutes,
                        isAllDay = instance.isAllDay
                    )

                    // Skip if trigger time is in the past
                    if (triggerTime <= afterMs) continue

                    // Check if this is the earliest
                    if (earliest == null || triggerTime < earliest.triggerTime) {
                        earliest = UpcomingDeviceReminder(
                            eventId = instance.eventId,
                            occurrenceStartTs = instance.occurrenceStartTs,
                            title = instance.title,
                            location = instance.location,
                            isAllDay = instance.isAllDay,
                            reminderMinutes = reminderMinutes,
                            triggerTime = triggerTime,
                            calendarColor = instance.calendarColor,
                            calendarId = instance.calendarId
                        )
                    }
                }
            }

            earliest
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting next upcoming reminder", e)
            null
        }
    }

    /**
     * Calculate reminder trigger time.
     *
     * For timed events: occurrenceStartTs - (reminderMinutes * 60 * 1000)
     * For all-day events: 9 AM local time, N days before (matches Room pattern)
     */
    private fun calculateReminderTriggerTime(
        occurrenceStartTs: Long,
        reminderMinutes: Int,
        isAllDay: Boolean
    ): Long {
        val offsetMs = -reminderMinutes.toLong() * 60 * 1000

        return if (isAllDay) {
            // For all-day events, fire at 9 AM local, N days before
            val localZone = java.time.ZoneId.systemDefault()
            val eventDate = java.time.Instant.ofEpochMilli(occurrenceStartTs)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()

            val oneDayMs = 24 * 60 * 60 * 1000L
            when {
                // Sub-day offset (e.g., -540 min for "9 AM day of event")
                offsetMs > -oneDayMs && offsetMs < 0 -> {
                    val hours = (-offsetMs / (60 * 60 * 1000L)).toInt()
                    val minutes = ((-offsetMs % (60 * 60 * 1000L)) / (60 * 1000L)).toInt()
                    eventDate.atTime(hours, minutes)
                        .atZone(localZone)
                        .toInstant()
                        .toEpochMilli()
                }
                // Day-based offset (e.g., -1440 min = 1 day before)
                else -> {
                    val days = (offsetMs / oneDayMs).toInt()
                    eventDate.plusDays(days.toLong())
                        .atTime(9, 0) // 9 AM local
                        .atZone(localZone)
                        .toInstant()
                        .toEpochMilli()
                }
            }
        } else {
            // Timed events: simple subtraction
            occurrenceStartTs + offsetMs
        }
    }

    /** Helper class for intermediate instance data before joining with reminders */
    private data class InstanceWithAlarm(
        val eventId: Long,
        val occurrenceStartTs: Long,
        val title: String,
        val location: String?,
        val isAllDay: Boolean,
        val calendarColor: Int,
        val calendarId: Long
    )

    // ==================== Events Table Helpers ====================

    private val eventsProjection = arrayOf(
        CalendarContract.Events._ID,                    // 0
        CalendarContract.Events.CALENDAR_ID,            // 1
        CalendarContract.Events.TITLE,                  // 2
        CalendarContract.Events.DESCRIPTION,            // 3
        CalendarContract.Events.EVENT_LOCATION,         // 4
        CalendarContract.Events.DTSTART,                // 5
        CalendarContract.Events.DTEND,                  // 6
        CalendarContract.Events.DURATION,               // 7
        CalendarContract.Events.ALL_DAY,                // 8
        CalendarContract.Events.RRULE,                  // 9
        CalendarContract.Events.RDATE,                  // 10
        CalendarContract.Events.EXDATE,                 // 11
        CalendarContract.Events.EXRULE,                 // 12
        CalendarContract.Events.EVENT_TIMEZONE,         // 13
        CalendarContract.Events.ORIGINAL_ID,            // 14
        CalendarContract.Events.ORIGINAL_INSTANCE_TIME, // 15
        CalendarContract.Events.STATUS,                 // 16
        CalendarContract.Events.AVAILABILITY,           // 17
        CalendarContract.Events.ACCESS_LEVEL,           // 18
        CalendarContract.Events.CALENDAR_COLOR,         // 19
        CalendarContract.Events.EVENT_COLOR             // 20
    )

    private fun mapToDeviceEvent(cursor: android.database.Cursor): DeviceEvent {
        val isAllDay = cursor.getInt(8) == 1
        val endTs = if (cursor.isNull(6)) null else cursor.getLong(6)

        return DeviceEvent(
            id = cursor.getLong(0),
            calendarId = cursor.getLong(1),
            title = cursor.getString(2).orEmpty(),
            description = cursor.getString(3),
            location = cursor.getString(4),
            startTs = cursor.getLong(5),
            endTs = endTs,
            duration = cursor.getString(7),
            isAllDay = isAllDay,
            rrule = cursor.getString(9),
            rdate = cursor.getString(10),
            exdate = cursor.getString(11),
            exrule = cursor.getString(12),
            timezone = cursor.getString(13) ?: java.util.TimeZone.getDefault().id,
            originalId = if (cursor.isNull(14)) null else cursor.getLong(14),
            originalInstanceTime = if (cursor.isNull(15)) null else cursor.getLong(15),
            status = cursor.getInt(16),
            availability = cursor.getInt(17),
            accessLevel = cursor.getInt(18),
            calendarColor = if (cursor.isNull(19)) null else cursor.getInt(19),
            eventColor = if (cursor.isNull(20)) null else cursor.getInt(20)
        )
    }
}

/**
 * Selection clause for the upcoming-device-reminder query.
 *
 * Unconditionally hides self-declined events — the alarm pipeline treats a
 * self-decline as "no", regardless of the display-side "Show declined"
 * toggle.
 */
internal fun buildUpcomingReminderSelection(): String =
    "${Instances.HAS_ALARM} = 1 AND " +
        "${Calendars.VISIBLE} = 1 AND " +
        "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED}"

/**
 * Build the ContentValues written when the user ticks a device calendar.
 *
 * Flips both flags together because on Xiaomi/MIUI Google calendars ship with
 * SYNC_EVENTS=0 AND VISIBLE=0 by default. Our Instances query filters on
 * VISIBLE=1, and events are never downloaded without SYNC_EVENTS=1.
 * Extracted to file level so tests can verify both keys are written (a typo
 * in either would silently break MIUI users).
 */
internal fun buildCalendarVisibleValues(): android.content.ContentValues {
    return android.content.ContentValues().apply {
        put(android.provider.CalendarContract.Calendars.SYNC_EVENTS, 1)
        put(android.provider.CalendarContract.Calendars.VISIBLE, 1)
    }
}

/**
 * Whether `requestSync` should be skipped for this account.
 *
 * Skips LOCAL accounts since they have no sync adapter to receive the request.
 * Callers must ensure `account.name` and `account.type` are non-blank;
 * `readCalendarAccount` guards this.
 */
internal fun shouldSkipRequestSync(account: android.accounts.Account): Boolean {
    return account.type.equals(android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL, ignoreCase = true)
}

/**
 * Build ContentValues for CalendarProvider Events table.
 *
 * Handles:
 * - All-day events: Uses UTC timezone, converts inclusive end to exclusive (+1 day)
 * - Recurring events: Uses DURATION instead of DTEND (RFC 5545 format)
 * - Single events: Uses DTEND
 *
 * @param title Event title
 * @param description Event description (optional)
 * @param location Event location (optional)
 * @param startTs Start timestamp (UTC millis for all-day, local for timed)
 * @param endTs End timestamp (inclusive for KashCal's internal format)
 * @param isAllDay Whether event is all-day
 * @param rrule Recurrence rule (null for single events)
 * @param duration Duration string (optional, calculated from endTs if null)
 * @param timezone Event timezone (ignored for all-day events, uses UTC)
 */
internal fun buildEventValues(
    title: String,
    description: String?,
    location: String?,
    startTs: Long,
    endTs: Long?,
    isAllDay: Boolean,
    rrule: String?,
    duration: String?,
    timezone: String,
    isException: Boolean = false
): android.content.ContentValues {
    val values = android.content.ContentValues()

    values.put(android.provider.CalendarContract.Events.TITLE, title)
    if (description != null) {
        values.put(android.provider.CalendarContract.Events.DESCRIPTION, description)
    } else {
        values.putNull(android.provider.CalendarContract.Events.DESCRIPTION)
    }
    if (location != null) {
        values.put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
    } else {
        values.putNull(android.provider.CalendarContract.Events.EVENT_LOCATION)
    }

    values.put(android.provider.CalendarContract.Events.DTSTART, startTs)
    values.put(android.provider.CalendarContract.Events.ALL_DAY, if (isAllDay) 1 else 0)

    // Timezone handling: all-day events always use UTC
    val effectiveTimezone = if (isAllDay) "UTC" else timezone
    values.put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, effectiveTimezone)

    val isRecurring = !rrule.isNullOrEmpty()

    if (isRecurring) {
        // Recurring events use DURATION, not DTEND
        values.put(android.provider.CalendarContract.Events.RRULE, rrule)

        val effectiveDuration = duration ?: calculateDuration(startTs, endTs, isAllDay)
        values.put(android.provider.CalendarContract.Events.DURATION, effectiveDuration)
        // Explicitly set DTEND to null for recurring events
        values.putNull(android.provider.CalendarContract.Events.DTEND)
    } else {
        if (!isException) {
            // Clear RRULE for regular non-recurring events (needed for recurring→non-recurring conversion).
            // For exceptions: RRULE key must be ABSENT — putNull triggers CalendarProvider
            // recurrence cleanup on master event via ORIGINAL_ID.
            values.putNull(android.provider.CalendarContract.Events.RRULE)
        }
        // Exception events must not have recurrence fields.
        // RDATE/EXDATE/EXRULE are safe to null explicitly (no cleanup side effects).
        if (isException) {
            values.putNull(android.provider.CalendarContract.Events.RDATE)
            values.putNull(android.provider.CalendarContract.Events.EXDATE)
            values.putNull(android.provider.CalendarContract.Events.EXRULE)
        }
        values.putNull(android.provider.CalendarContract.Events.DURATION)

        if (isAllDay && endTs != null) {
            // Convert inclusive end to exclusive:
            // KashCal stores end as last ms of last day (23:59:59.999)
            // CalendarProvider expects 00:00:00 of next day
            // Add 1ms to cross into next day, then round to midnight
            val endPlusOne = endTs + 1
            // Round to start of day (midnight UTC)
            val effectiveEndTs = (endPlusOne / 86_400_000) * 86_400_000
            values.put(android.provider.CalendarContract.Events.DTEND, effectiveEndTs)
        } else if (endTs != null) {
            values.put(android.provider.CalendarContract.Events.DTEND, endTs)
        }
    }

    return values
}

/**
 * Calculate RFC 5545 duration string from start/end timestamps.
 *
 * Format: P[n]D for days, PT[n]H[n]M for hours/minutes
 *
 * @param startTs Start timestamp
 * @param endTs End timestamp (null returns empty string)
 * @param isAllDay Whether event is all-day
 */
private fun calculateDuration(startTs: Long, endTs: Long?, isAllDay: Boolean): String {
    if (endTs == null) return "PT0M"

    if (isAllDay) {
        // For all-day events, calculate days
        // endTs is inclusive (last ms of last day), so add 1ms to get exclusive end
        val durationMs = (endTs + 1) - startTs
        val days = (durationMs / 86_400_000).toInt().coerceAtLeast(1)
        return "P${days}D"
    } else {
        // For timed events, calculate hours and minutes
        val durationMs = endTs - startTs
        val totalMinutes = (durationMs / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return buildString {
            append("PT")
            if (hours > 0) append("${hours}H")
            if (minutes > 0) append("${minutes}M")
            if (hours == 0 && minutes == 0) append("0M")
        }
    }
}

/**
 * Parse an RFC 5545 duration string to milliseconds.
 * Handles common formats: P1D, P2D (days), PT1H, PT30M, PT1H30M (time).
 * Falls back to 1 day for all-day events or 1 hour for timed events.
 */
internal fun parseDurationMs(duration: String?, isAllDay: Boolean): Long {
    val defaultMs = if (isAllDay) 86_400_000L else 3_600_000L
    if (duration.isNullOrEmpty()) return defaultMs
    return try {
        if (duration.startsWith("P") && !duration.contains("T")) {
            // Date-only duration: P1D, P2W, etc.
            val cleaned = duration.removePrefix("P")
            when {
                cleaned.endsWith("W") -> {
                    val weeks = cleaned.removeSuffix("W").toLongOrNull() ?: 1
                    weeks * 7 * 86_400_000L
                }
                cleaned.endsWith("D") -> {
                    val days = cleaned.removeSuffix("D").toLongOrNull() ?: 1
                    days * 86_400_000L
                }
                else -> defaultMs
            }
        } else {
            // Time duration: PT1H, PT30M, PT1H30M — java.time.Duration handles these
            java.time.Duration.parse(duration).toMillis()
        }
    } catch (_: Exception) {
        defaultMs
    }
}

/**
 * Convert YYYYMMDD day code to start-of-day epoch millis (local timezone).
 */
internal fun dayCodeToStartOfDayMs(dayCode: Int): Long {
    val year = dayCode / 10000
    val month = (dayCode % 10000) / 100
    val day = dayCode % 100
    return LocalDate.of(year, month, day)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant().toEpochMilli()
}


/**
 * Convert YYYYMMDD day code to end-of-day epoch millis (local timezone).
 * Returns 23:59:59.999 to include all events on that day.
 */
internal fun dayCodeToEndOfDayMs(dayCode: Int): Long {
    val year = dayCode / 10000
    val month = (dayCode % 10000) / 100
    val day = dayCode % 100
    return LocalDate.of(year, month, day)
        .atTime(23, 59, 59, 999_000_000)
        .atZone(ZoneId.systemDefault())
        .toInstant().toEpochMilli()
}

