package org.onekash.kashcal.data.calendar_provider

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.text.format.DateUtils
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            Instances.DISPLAY_COLOR,    // 10
            Instances.CALENDAR_DISPLAY_NAME, // 11
            Instances.STATUS,           // 12
            Instances.AVAILABILITY,     // 13
            Instances.HAS_ALARM,        // 14
            Instances.SELF_ATTENDEE_STATUS,  // 15
            Instances.CALENDAR_ACCESS_LEVEL  // 16
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
        private const val COL_DISPLAY_COLOR = 10
        private const val COL_CALENDAR_DISPLAY_NAME = 11
        private const val COL_STATUS = 12
        private const val COL_AVAILABILITY = 13
        private const val COL_HAS_ALARM = 14
        private const val COL_SELF_ATTENDEE_STATUS = 15
        private const val COL_ACCESS_LEVEL = 16

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
                            displayName = cursor.getString(1) ?: "",
                            color = cursor.getInt(2),
                            accountName = cursor.getString(3) ?: "",
                            accountType = cursor.getString(4) ?: "",
                            visible = cursor.getInt(5) == 1,
                            accessLevel = cursor.getInt(6)
                        )
                    )
                }
                results
            } ?: emptyList()
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

            contentResolver.query(
                builder.build(), INSTANCES_PROJECTION, selection, null, SORT_ORDER
            )?.use { cursor -> mapToInstances(cursor, enabledCalendarIds) }
                ?: emptyList()
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
        while (cursor.moveToNext()) {
            val calendarId = cursor.getLong(COL_CALENDAR_ID)
            if (calendarId !in enabledCalendarIds) continue

            val beginMs = cursor.getLong(COL_BEGIN)
            val endMs = cursor.getLong(COL_END)
            val isAllDay = cursor.getInt(COL_ALL_DAY) == 1
            val accessLevel = cursor.getInt(COL_ACCESS_LEVEL)

            // CalendarProvider uses exclusive end for all-day events:
            // 1-day event on Feb 15 → END = Feb 16 00:00 UTC.
            // Subtract 1ms to get inclusive end day code.
            val endDayMs = if (isAllDay && endMs > beginMs) endMs - 1 else endMs

            results.add(
                DeviceCalendarInstance(
                    instanceId = cursor.getLong(COL_ID),
                    eventId = cursor.getLong(COL_EVENT_ID),
                    title = cursor.getString(COL_TITLE) ?: "",
                    description = cursor.getString(COL_DESCRIPTION) ?: "",
                    location = cursor.getString(COL_LOCATION) ?: "",
                    startTs = beginMs,
                    endTs = endDayMs,
                    startDay = DateTimeUtils.eventTsToDayCode(beginMs, isAllDay),
                    endDay = DateTimeUtils.eventTsToDayCode(endDayMs, isAllDay),
                    isAllDay = isAllDay,
                    hasRrule = !cursor.getString(COL_RRULE).isNullOrEmpty(),
                    calendarId = calendarId,
                    calendarDisplayName = cursor.getString(COL_CALENDAR_DISPLAY_NAME) ?: "",
                    displayColor = cursor.getInt(COL_DISPLAY_COLOR),
                    status = cursor.getInt(COL_STATUS),
                    availability = cursor.getInt(COL_AVAILABILITY),
                    hasAlarm = cursor.getInt(COL_HAS_ALARM) == 1,
                    selfAttendeeStatus = cursor.getInt(COL_SELF_ATTENDEE_STATUS),
                    isWritable = accessLevel >= 500 // CAL_ACCESS_CONTRIBUTOR
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

            contentResolver.query(
                builder.build(), INSTANCES_PROJECTION, selection, null, SORT_ORDER
            )?.use { cursor -> mapToInstances(cursor, enabledCalendarIds) }
                ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            emptyList()
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
