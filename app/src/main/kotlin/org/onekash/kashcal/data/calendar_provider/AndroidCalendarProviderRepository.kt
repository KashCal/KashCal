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
 * Implements [CalendarProviderRepository] over CalendarContract through the ContentResolver.
 *
 * A revoked permission is caught, never thrown; what each method returns then is documented on
 * [CalendarProviderRepository].
 *
 * Time-range queries use Instances.CONTENT_URI (epoch ms). CONTENT_BY_DAY_URI takes Julian days,
 * which are not KashCal's YYYYMMDD day codes.
 */
@Singleton
class AndroidCalendarProviderRepository @Inject constructor(
    private val contentResolver: ContentResolver
) : CalendarProviderRepository {

    companion object {
        private const val TAG = "CalProviderRepo"

        /**
         * Calendars projection of [getDeviceCalendars] and [getDeviceCalendar]. Order matters:
         * [mapToDeviceCalendar] reads by index.
         */
        private val CALENDARS_PROJECTION = arrayOf(
            Calendars._ID,                   // 0
            Calendars.CALENDAR_DISPLAY_NAME, // 1
            Calendars.CALENDAR_COLOR,        // 2
            Calendars.ACCOUNT_NAME,          // 3
            Calendars.ACCOUNT_TYPE,          // 4
            Calendars.VISIBLE,               // 5
            Calendars.CALENDAR_ACCESS_LEVEL, // 6
            Calendars.OWNER_ACCOUNT          // 7
        )

        /**
         * Attendee read projection. Order matters: [mapToDeviceAttendee] reads by index.
         * `internal` so `AttendeesProjectionTest` can assert these five columns in this order.
         */
        internal val ATTENDEES_PROJECTION = arrayOf(
            Attendees._ID,                   // 0
            Attendees.ATTENDEE_NAME,         // 1
            Attendees.ATTENDEE_EMAIL,        // 2
            Attendees.ATTENDEE_RELATIONSHIP, // 3
            Attendees.ATTENDEE_STATUS        // 4
        )

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
            Instances.ORIGINAL_ID,       // 16 - master event ID, for exceptions
            Instances.ORIGINAL_INSTANCE_TIME, // 17 - original occurrence time, for exceptions
            Instances.EVENT_TIMEZONE,    // 18 - event zone; the exception's on a changed occurrence
            // The color columns are read by name, so a projection reorder can't shift them.
            Instances.CALENDAR_COLOR,    // 19 - raw calendar color (identity)
            Instances.EVENT_COLOR,       // 20 - raw event override (0 = no override)
            Instances.DTSTART            // 21 - the event row's DTSTART (first-occurrence anchor)
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

        /** Upper bound for the next-occurrence lookup window (~10 years). */
        private const val TEN_YEARS_MS = 10L * 365L * DateUtils.DAY_IN_MILLIS
    }

    override suspend fun getDeviceCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDARS_PROJECTION,
                null, null, null
            )?.use { cursor ->
                val results = mutableListOf<DeviceCalendar>()
                while (cursor.moveToNext()) {
                    results.add(mapToDeviceCalendar(cursor))
                }
                results
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            emptyList()
        }
    }

    override suspend fun getDeviceCalendar(id: Long): DeviceCalendar? = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
                CALENDARS_PROJECTION,
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) mapToDeviceCalendar(cursor) else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar permission revoked", e)
            null
        }
    }

    private fun mapToDeviceCalendar(cursor: android.database.Cursor) = DeviceCalendar(
        id = cursor.getLong(0),
        displayName = cursor.getString(1).orEmpty(),
        color = cursor.getInt(2),
        accountName = cursor.getString(3).orEmpty(),
        accountType = cursor.getString(4).orEmpty(),
        visible = cursor.getInt(5) == 1,
        accessLevel = cursor.getInt(6),
        ownerAccount = cursor.getString(7).orEmpty()
    )

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

            populateRemindersAndCategories(instances)
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
        val colCalendarColor = cursor.getColumnIndexOrThrow(Instances.CALENDAR_COLOR)
        val colEventColor = cursor.getColumnIndexOrThrow(Instances.EVENT_COLOR)
        while (cursor.moveToNext()) {
            val calendarId = cursor.getLong(COL_CALENDAR_ID)
            if (calendarId !in enabledCalendarIds) continue

            val beginMs = cursor.getLong(COL_BEGIN)
            val endMs = cursor.getLong(COL_END)
            val isAllDay = cursor.getInt(COL_ALL_DAY) == 1
            val accessLevel = cursor.getInt(COL_ACCESS_LEVEL)

            // All-day: the provider's exclusive end (next midnight) becomes the inclusive last
            // ms of the last day, the Room Event.endTs convention, for both endTs and endDay.
            val inclusiveEndMs = if (isAllDay && endMs > beginMs) endMs - 1 else endMs

            // ORIGINAL_ID is set only on exceptions.
            val originalId = if (!cursor.isNull(COL_ORIGINAL_ID)) {
                cursor.getLong(COL_ORIGINAL_ID)
            } else null

            val originalInstanceTime = if (!cursor.isNull(COL_ORIGINAL_INSTANCE_TIME)) {
                cursor.getLong(COL_ORIGINAL_INSTANCE_TIME)
            } else null

            val status = cursor.getInt(COL_STATUS)

            // A STATUS_CANCELED exception is a deleted occurrence. CalendarProvider should
            // suppress it, but some OEM implementations don't.
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
                    reminders = emptyList(), // filled by populateRemindersAndCategories
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

            // Path: instances/search/{begin}/{end}/{query}
            val builder = Instances.CONTENT_SEARCH_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)
            builder.appendPath(query)

            val selection = if (hideDeclined) SELECTION_HIDE_DECLINED else SELECTION_VISIBLE

            val instances = contentResolver.query(
                builder.build(), INSTANCES_PROJECTION, selection, null, SORT_ORDER
            )?.use { cursor -> mapToInstances(cursor, enabledCalendarIds) }
                .orEmpty()

            populateRemindersAndCategories(instances)
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
            // A series skips the DTSTART window: the master's DTSTART is its first occurrence,
            // which can be old while the series is still active. LIKE with COLLATE NOCASE
            // folds ASCII case only.
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

            // Case-insensitive, like the fake, in case a provider returns the same invite with
            // different casing on two calendars.
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
     * Returns a copy of [instances] with reminders (CalendarContract.Reminders) and tags (the
     * categories extended property) filled in, from one batch query each instead of one per
     * event.
     */
    private suspend fun populateRemindersAndCategories(
        instances: List<DeviceCalendarInstance>
    ): List<DeviceCalendarInstance> {
        if (instances.isEmpty()) return instances

        val eventIds = instances.map { it.eventId }.toSet()
        val remindersMap = getRemindersForEvents(eventIds)
        val categoriesMap = getCategoriesForEvents(eventIds)

        return instances.map { instance ->
            instance.copy(
                reminders = remindersMap[instance.eventId].orEmpty(),
                categories = categoriesMap[instance.eventId].orEmpty(),
            )
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
     * Returns a calendar row's account, or null if the row is gone (a sync adapter deleted it),
     * its name or type is blank, the permission is revoked, or the query fails.
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
                // Account(String, String) throws IllegalArgumentException on blanks, and
                // shouldSkipRequestSync assumes non-blank inputs.
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

    /**
     * Replaces an event's tag extended property.
     *
     * On a synced calendar the extended-property store accepts writes only in sync-adapter
     * mode, so the delete and insert go through a URI carrying the calendar's account. When the
     * account can't be resolved (row gone, permission revoked) the write is skipped and the
     * event keeps its old tags. A provider exception on the delete or insert is swallowed, so a
     * failed insert after the delete leaves the event with no tags: the event body is already
     * committed, so failing the save would report a false failure and, on create, invite a
     * duplicating retry.
     *
     * When no usable name survives [encodeCategories] (an empty list, or only blanks) the row
     * is deleted and nothing is inserted, so removing every tag empties the stored value.
     */
    private fun writeCategories(eventId: Long, calendarId: Long, categories: List<String>) {
        val account = readCalendarAccount(calendarId)
        if (account == null) {
            Log.w(TAG, "writeCategories($eventId): no account for calendar $calendarId, skipping")
            return
        }
        val uri = syncAdapterExtendedPropertiesUri(account.name, account.type)
        try {
            contentResolver.delete(
                uri,
                "${CalendarContract.ExtendedProperties.EVENT_ID} = ? AND " +
                    "${CalendarContract.ExtendedProperties.NAME} = ?",
                arrayOf(eventId.toString(), EXTNAME_CATEGORIES)
            )
            val encoded = encodeCategories(categories)
            if (encoded != null) {
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.ExtendedProperties.EVENT_ID, eventId)
                    put(CalendarContract.ExtendedProperties.NAME, EXTNAME_CATEGORIES)
                    put(CalendarContract.ExtendedProperties.VALUE, encoded)
                }
                contentResolver.insert(uri, values)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeCategories($eventId): tag write failed, event saved without tag change", e)
        }
    }

    // ==================== Write Operations ====================

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
        eventColor: Int?,
        attendees: List<DeviceAttendee>?,
        categories: List<String>?
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val guests = attendees.orEmpty()
            val values = buildEventValues(title, description, location, startTs, endTs, isAllDay, rrule, duration, timezone)
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId)
            values.put(CalendarContract.Events.AVAILABILITY, availability)
            eventColor?.let { values.put(CalendarContract.Events.EVENT_COLOR, it) }
            // Set only when there are guests. Without it the sync adapter treats the event as
            // attendee-free and never delivers invitations.
            if (guests.isNotEmpty()) {
                values.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
            }

            // One batch, so the event, reminders and attendees land together.
            val ops = ArrayList<android.content.ContentProviderOperation>()

            ops.add(
                android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(values)
                    .build()
            )

            ops.addAll(reminderInsertOps(alertRows(reminders), eventBackReference = 0))

            // Attendee rows, only when there are guests. The owner row shows the organizer on
            // the guest list. Reminders and attendees reference the new event by back-reference.
            if (guests.isNotEmpty()) {
                val ownerEmail = ownerEmailForCalendar(calendarId)
                // [ownerRowNeeded] decides the owner row; a create has no existing organizer.
                // Guests exclude the owner so it isn't written twice.
                if (ownerRowNeeded(existing = emptyList(), desired = guests, ownerEmail = ownerEmail)) {
                    ops.add(
                        android.content.ContentProviderOperation.newInsert(Attendees.CONTENT_URI)
                            .withValueBackReference(Attendees.EVENT_ID, 0)
                            .withValues(buildOwnerAttendeeValues(ownerEmail!!.trim()))
                            .build()
                    )
                }
                for (guest in guestsExcludingOwner(guests, ownerEmail)) {
                    if (guest.email.isNullOrBlank()) continue
                    ops.add(
                        android.content.ContentProviderOperation.newInsert(Attendees.CONTENT_URI)
                            .withValueBackReference(Attendees.EVENT_ID, 0)
                            .withValues(buildGuestAttendeeValues(guest))
                            .build()
                    )
                }
            }

            val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            val eventUri = results[0].uri
            val eventId = ContentUris.parseId(eventUri!!)

            // Tags are written after the batch, outside it; see [writeCategories].
            if (categories != null) {
                writeCategories(eventId, calendarId, categories)
            }

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
        reminders: List<Int>?,
        availability: Int,
        eventColor: Int?,
        attendees: List<DeviceAttendee>?,
        categories: List<String>?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val isException = rrule == null && isExceptionEvent(eventId)
            val values = buildEventValues(title, description, location, startTs, endTs, isAllDay, rrule, duration, timezone, isException = isException)
            values.put(CalendarContract.Events.AVAILABILITY, availability)
            eventColor?.let { values.put(CalendarContract.Events.EVENT_COLOR, it) }
                ?: values.putNull(CalendarContract.Events.EVENT_COLOR)
            // Set when the edit carries guests. Never clear it: an event that has ever had a
            // guest list keeps the flag (matches the provider's own behaviour and avoids
            // confusing sync adapters).
            if (!attendees.isNullOrEmpty()) {
                values.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
            }

            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rowsUpdated = contentResolver.update(eventUri, values, null, null)
            if (rowsUpdated == 0) {
                return@withContext Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound))
            }

            // Null leaves every reminder row untouched, so a reschedule keeps an email or SMS
            // reminder instead of rewriting it as a pop-up alert.
            if (reminders != null) {
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
            }

            // Null leaves every attendee row and its synced status untouched. Non-null is the
            // full desired set: only new guests are inserted and only removed ones deleted.
            if (attendees != null) {
                applyAttendeeDiff(eventId, attendees)
            }

            // Null leaves the tag row untouched, so a drag-reschedule or an exception edit never
            // wipes tags the user didn't touch. A non-null empty list clears the row.
            if (categories != null) {
                val calId = calendarIdForEvent(eventId)
                if (calId != null) {
                    writeCategories(eventId, calId, categories)
                } else {
                    Log.w(TAG, "updateEvent($eventId): no calendar id, skipping tag write")
                }
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
        reminders: List<Int>?,
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
            // The occurrence this row replaces is a slot of the series, so the series' all-day
            // flag names it, not the flag the row is saved with.
            val seriesAllDay = seriesIsAllDay(masterEventId)
                ?: return@withContext Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound))
            val normalizedOrigTime = if (seriesAllDay)
                DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime
            values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, normalizedOrigTime)
            values.put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (seriesAllDay) 1 else 0)
            values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)

            // Read what the new row copies from the series before any write.
            val reminderRows = reminderRowsForNewRow(reminders, masterEventId)
            val seriesCopy = readSeriesCopy(masterEventId, calendarId, categories = null)
            seriesCopy.putOrganizerInto(values)

            // One batch, so the row and what it copies land together.
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(
                android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
            ops.addAll(reminderInsertOps(reminderRows, eventBackReference = 0))
            ops.addAll(seriesCopy.insertOps(eventBackReference = 0))

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
            // The series' own flag names the occurrence: the caller's is the flag of what it
            // shows, which for a changed occurrence may differ.
            val seriesAllDay = seriesIsAllDay(masterEventId) ?: isAllDay
            val normalizedTime = if (seriesAllDay)
                DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime

            // An existing exception (an edited occurrence) is set to CANCELED. An already
            // cancelled row is reused, so the occurrence never gets a second cancellation; a
            // deleted row is skipped, since cancelling it would leave the occurrence in place.
            val existingExceptionId = findExceptionRowId(
                masterEventId, originalInstanceTime, seriesAllDay, includeCancelled = true,
            )
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
                // Otherwise insert a STATUS_CANCELED exception: CalendarProvider tracks cancelled
                // occurrences with exception rows, not EXDATE.
                val masterEvent = getDeviceEvent(masterEventId)
                    ?: return@withContext Result.failure(
                        CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                    )

                // An exception is non-recurring, so it takes DTEND. CalendarProvider requires
                // DTEND on non-recurring events; with DURATION the exception is malformed and
                // doesn't suppress the original occurrence.
                val durationMs = parseDurationMs(masterEvent.duration, seriesAllDay)
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, masterEvent.calendarId)
                    put(CalendarContract.Events.TITLE, masterEvent.title)
                    put(CalendarContract.Events.DTSTART, normalizedTime)
                    put(CalendarContract.Events.DTEND, normalizedTime + durationMs)
                    put(CalendarContract.Events.ALL_DAY, if (seriesAllDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (seriesAllDay) "UTC" else masterEvent.timezone)
                    put(CalendarContract.Events.ORIGINAL_ID, masterEventId)
                    put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, normalizedTime)
                    put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (seriesAllDay) 1 else 0)
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                    // An exception has no recurrence fields.
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

            // Deleting from the first occurrence deletes the whole event.
            if (fromTimeMs <= masterEvent.startTs) {
                return@withContext deleteEvent(masterEventId)
            }

            val rrule = masterEvent.rrule
                ?: return@withContext Result.failure(
                    CalendarErrorException(
                        CalendarError.DeviceCalendar.WriteFailed("Recurring event has no RRULE")
                    )
                )

            // UNTIL takes the series' value type (RFC 5545 section 3.3.10): the occurrence
            // deleted from may show as all-day on a timed series.
            val truncatedRrule = org.onekash.kashcal.util.RruleUtils.addUntilToRrule(
                rrule, fromTimeMs - 1, masterEvent.isAllDay
            )

            val values = seriesEndValues(masterEventId, truncatedRrule)
                ?: return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )
            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, masterEventId)
            val rowsUpdated = contentResolver.update(eventUri, values, null, null)
            if (rowsUpdated == 0) {
                return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )
            }

            // CalendarProvider keeps the exceptions past a shortened RRULE, so delete them.
            val normalizedFrom = if (masterEvent.isAllDay)
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
        reminders: List<Int>?,
        availability: Int,
        eventColor: Int?,
        categories: List<String>?,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val masterEvent = getDeviceEvent(masterEventId)
                ?: return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )

            // A split at or before the master's start is an "all events" edit.
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
                    categories = categories,
                )
                return@withContext updateResult.map { masterEventId }
            }

            val masterRrule = masterEvent.rrule
                ?: return@withContext Result.failure(
                    CalendarErrorException(
                        CalendarError.DeviceCalendar.WriteFailed("Recurring event has no RRULE")
                    )
                )

            // On a COUNT rule, count the occurrences before the split so the two halves keep
            // the total; otherwise a COUNT=N series gets N more on the new row instead of
            // N - pastCount. The Instances window includes both ends, so it stops 1 ms before
            // the split. If the count can't be read the split fails: a count of 0 would edit
            // the whole series instead.
            val pastCount = if (org.onekash.kashcal.util.RruleUtils.hasCount(masterRrule)) {
                countInstancesInRange(masterEventId, masterEvent.startTs, fromTimeMs - 1)
                    ?: return@withContext Result.failure(
                        CalendarErrorException(
                            CalendarError.DeviceCalendar.WriteFailed("Couldn't count the occurrences before the split")
                        )
                    )
            } else {
                0
            }

            // A degenerate COUNT split ([RruleUtils.isDegenerateCountSplit]) would yield an
            // invalid COUNT=0, so update the master in place as an "all events" edit. The
            // user's rrule wins, and null makes the master non-recurring.
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
                    categories = categories,
                )
                return@withContext updateResult.map { masterEventId }
            }

            // rrule == null is the user's "Does not repeat" pick: the helper returns a null
            // new-series rule, and the new row is written as a one-off.
            val (truncatedRrule, splitNewSeriesRrule) =
                org.onekash.kashcal.util.RruleUtils.splitRruleAtTime(
                    masterRrule = masterRrule,
                    userRrule = rrule,
                    untilMs = fromTimeMs - 1,
                    pastCount = pastCount,
                    isAllDay = masterEvent.isAllDay,
                )
            val newSeriesRrule = splitNewSeriesRrule

            // The future series' row, built like createEvent's.
            val newEventValues = buildEventValues(
                title, description, location, startTs, endTs, isAllDay, newSeriesRrule, duration, timezone
            ).apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.AVAILABILITY, availability)
                eventColor?.let { put(CalendarContract.Events.EVENT_COLOR, it) }
            }

            // Like a new exception, the future half copies the series' reminders (unless given
            // new ones), guests, organizer and tags (unless edited). Read before any write.
            val reminderRows = reminderRowsForNewRow(reminders, masterEventId)
            val seriesCopy = readSeriesCopy(masterEventId, calendarId, categories)
            seriesCopy.putOrganizerInto(newEventValues)

            val masterUri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, masterEventId
            )
            val masterTruncate = seriesEndValues(masterEventId, truncatedRrule)
                ?: return@withContext Result.failure(
                    CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound)
                )

            // One applyBatch, INSERT first: a failed insert stops the batch before the master is
            // truncated.
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(
                android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(newEventValues)
                    .build()
            )
            ops.addAll(reminderInsertOps(reminderRows, eventBackReference = 0))
            ops.addAll(seriesCopy.insertOps(eventBackReference = 0))
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

            // CalendarProvider keeps the exceptions past a shortened RRULE, so delete them.
            // Best effort: a failure here doesn't undo the split.
            val normalizedFrom = if (masterEvent.isAllDay)
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

            // CalendarProvider doesn't cascade the move to exceptions.
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
                    5 // fallback
                }
            } ?: 5
        } catch (e: Exception) {
            Log.w(TAG, "Error getting max reminders", e)
            5 // fallback
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
            val event = contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                eventsProjection,
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mapToDeviceEvent(cursor)
                } else null
            } ?: return@withContext null

            // Tags live in the extended-property table, not the Events projection.
            val categories = getCategoriesForEvents(setOf(event.id))[event.id].orEmpty()
            event.copy(categories = categories)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading event", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading event", e)
            null
        }
    }

    override suspend fun getNextOccurrenceStart(
        eventId: Long,
        afterMs: Long
    ): Long? = withContext(Dispatchers.IO) {
        try {
            // Window [afterMs - 1 day, afterMs + ~10y]. The Instances view expands RRULE/RDATE
            // and drops EXDATE'd occurrences, so the first row (BEGIN ASC) is the next
            // occurrence, not the master DTSTART. The day of padding before afterMs, as in
            // getInstancesForDayRange, keeps today's occurrence when its BEGIN is earlier: an
            // all-day BEGIN is UTC midnight, and a timed one may have started already. Without
            // it they resolve to next week's. The ~10y bound stops the provider expanding an
            // unbounded series forever.
            val startMs = afterMs - DateUtils.DAY_IN_MILLIS
            val endMs = afterMs + TEN_YEARS_MS
            val builder = Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)

            contentResolver.query(
                builder.build(),
                arrayOf(Instances.EVENT_ID, Instances.BEGIN),
                "${Instances.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                "${Instances.BEGIN} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(1) else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading next occurrence for event $eventId", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading next occurrence for event $eventId", e)
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

        // The Events cursor has no tag column; one batch query gives each exception its tags.
        val exceptionsWithCategories = if (exceptions.isEmpty()) {
            exceptions
        } else {
            val categoriesMap = getCategoriesForEvents(exceptions.map { it.id }.toSet())
            exceptions.map { it.copy(categories = categoriesMap[it.id].orEmpty()) }
        }

        master to exceptionsWithCategories
    }

    override suspend fun getAttendees(eventId: Long): List<DeviceAttendee> = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                Attendees.CONTENT_URI,
                ATTENDEES_PROJECTION,
                "${Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { cursor ->
                val results = mutableListOf<DeviceAttendee>()
                while (cursor.moveToNext()) {
                    results.add(mapToDeviceAttendee(cursor))
                }
                results
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading attendees for event $eventId", e)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error reading attendees for event $eventId", e)
            emptyList()
        }
    }

    private fun mapToDeviceAttendee(cursor: android.database.Cursor): DeviceAttendee = DeviceAttendee(
        id = cursor.getLong(0),
        name = cursor.getString(1),
        email = cursor.getString(2),
        relationship = cursor.getInt(3),
        status = cursor.getInt(4)
    )

    /**
     * Returns a calendar's `OWNER_ACCOUNT` (the organizer, "you"), or null when it is blank, the
     * row is missing or the query fails.
     */
    private fun ownerEmailForCalendar(calendarId: Long): String? {
        return try {
            contentResolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
                arrayOf(Calendars.OWNER_ACCOUNT),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeUnless { it.isBlank() } else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "ownerEmailForCalendar($calendarId): permission denied", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "ownerEmailForCalendar($calendarId): query failed", e)
            null
        }
    }

    /**
     * Applies [computeAttendeeDiff] to an existing event: deletes the removed guests' rows (by
     * `_ID`) and inserts the new ones. Unchanged guests are never touched, so their pulled-down
     * `ATTENDEE_STATUS` survives.
     *
     * The owner is left out of the guest set ([guestsExcludingOwner]), and [ownerRowNeeded]
     * adds its row, so adding a guest to a solo event writes the organizer too, as create does.
     *
     * One `applyBatch`, so a failure mid-diff leaves the guest list as it was.
     */
    private fun applyAttendeeDiff(eventId: Long, desired: List<DeviceAttendee>) {
        val existing = readAttendeesBlocking(eventId)
        val ownerEmail = calendarIdForEvent(eventId)?.let { ownerEmailForCalendar(it) }
        val desiredGuests = guestsExcludingOwner(desired, ownerEmail)
        val diff = computeAttendeeDiff(existing, desiredGuests)
        val addOwnerRow = ownerRowNeeded(existing = existing, desired = desiredGuests, ownerEmail = ownerEmail)
        if (diff.toDelete.isEmpty() && diff.toInsert.isEmpty() && !addOwnerRow) return

        val ops = ArrayList<android.content.ContentProviderOperation>()
        for (removed in diff.toDelete) {
            ops.add(
                android.content.ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Attendees.CONTENT_URI, removed.id)
                ).build()
            )
        }
        if (addOwnerRow) {
            val values = buildOwnerAttendeeValues(ownerEmail!!.trim()).apply {
                put(Attendees.EVENT_ID, eventId)
            }
            ops.add(
                android.content.ContentProviderOperation.newInsert(Attendees.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
        }
        for (added in diff.toInsert) {
            if (added.email.isNullOrBlank()) continue
            val values = buildGuestAttendeeValues(added).apply {
                put(Attendees.EVENT_ID, eventId)
            }
            ops.add(
                android.content.ContentProviderOperation.newInsert(Attendees.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
        }
        contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
    }

    /**
     * Returns an event row's `CALENDAR_ID`, or null when the row is missing or the query fails.
     * [updateEvent] uses it for the tag write and [applyAttendeeDiff] for the owner email.
     */
    private fun calendarIdForEvent(eventId: Long): Long? {
        return try {
            contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events.CALENDAR_ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "calendarIdForEvent($eventId) failed", e)
            null
        }
    }

    /**
     * Reads attendees like [getAttendees], without a `withContext`, for [applyAttendeeDiff],
     * which already runs on the IO dispatcher. Returns empty on any error.
     */
    private fun readAttendeesBlocking(eventId: Long): List<DeviceAttendee> {
        return try {
            contentResolver.query(
                Attendees.CONTENT_URI,
                ATTENDEES_PROJECTION,
                "${Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { cursor ->
                val results = mutableListOf<DeviceAttendee>()
                while (cursor.moveToNext()) {
                    results.add(mapToDeviceAttendee(cursor))
                }
                results
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "readAttendeesBlocking($eventId) failed", e)
            emptyList()
        }
    }

    override suspend fun updateSelfAttendeeStatus(
        eventId: Long,
        attendeeId: Long,
        status: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(Attendees.ATTENDEE_STATUS, status)
            }
            // By the row's own _ID, so no other guest's row changes.
            val rows = contentResolver.update(
                ContentUris.withAppendedId(Attendees.CONTENT_URI, attendeeId),
                values, null, null
            )
            if (rows == 0) {
                return@withContext Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.EventNotFound))
            }
            Log.d(TAG, "Updated self RSVP: event=$eventId, attendee=$attendeeId, status=$status")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied updating self RSVP for event $eventId", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating self RSVP for event $eventId", e)
            Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error")))
        }
    }

    /** One Reminders row as stored: minutes before the event and its METHOD. */
    private data class ReminderRow(val minutes: Int, val method: Int)

    /**
     * Reads an event's reminder rows, METHOD included. It never degrades to "no reminders": a
     * provider error propagates and a null cursor throws, so a write copying them onto a new
     * row fails instead of silently dropping them ([getReminders] catches and shows none).
     * Only METHOD_DEFAULT and METHOD_ALERT fire on the device, but the provider stores the
     * other methods so the sync adapter can send the same reminder back to the server, so they
     * are copied as they are.
     */
    private fun readReminderRows(eventId: Long): List<ReminderRow> {
        val cursor = contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders.MINUTES} ASC"
        ) ?: throw IllegalStateException("Reminders query for event $eventId returned no cursor")
        return cursor.use {
            buildList { while (it.moveToNext()) add(ReminderRow(it.getInt(0), it.getInt(1))) }
        }
    }

    /**
     * Reads an event's attendee rows as insertable values for a new row cut from the series,
     * with every column the provider copies when it creates an exception itself: name, email,
     * relationship, type, status, identity and its namespace. An error or a null cursor throws,
     * as in [readReminderRows], instead of reading as "no guests".
     */
    private fun readAttendeeRowsForCopy(eventId: Long): List<android.content.ContentValues> {
        val columns = arrayOf(
            Attendees.ATTENDEE_NAME,
            Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_RELATIONSHIP,
            Attendees.ATTENDEE_TYPE,
            Attendees.ATTENDEE_STATUS,
            Attendees.ATTENDEE_IDENTITY,
            Attendees.ATTENDEE_ID_NAMESPACE,
        )
        val cursor = contentResolver.query(
            Attendees.CONTENT_URI, columns, "${Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null
        ) ?: throw IllegalStateException("Attendees query for event $eventId returned no cursor")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val row = android.content.ContentValues()
                    columns.forEachIndexed { i, column ->
                        when {
                            it.isNull(i) -> Unit
                            it.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(column, it.getInt(i))
                            else -> row.put(column, it.getString(i))
                        }
                    }
                    add(row)
                }
            }
        }
    }

    /**
     * Reads an event's tag VALUE as stored (foreign content included) for a new row cut from
     * the series, or null when no row decodes to a tag. An error or a null cursor throws
     * instead of reading as "no tags".
     */
    private fun readCategoriesValueForCopy(eventId: Long): String? {
        val cursor = contentResolver.query(
            CalendarContract.ExtendedProperties.CONTENT_URI,
            arrayOf(CalendarContract.ExtendedProperties.VALUE),
            "${CalendarContract.ExtendedProperties.EVENT_ID} = ? AND ${CalendarContract.ExtendedProperties.NAME} = ?",
            arrayOf(eventId.toString(), EXTNAME_CATEGORIES),
            null
        ) ?: throw IllegalStateException("Tag query for event $eventId returned no cursor")
        return cursor.use {
            var found: String? = null
            while (found == null && it.moveToNext()) {
                found = it.getString(0)?.takeIf { value -> decodeCategories(value).isNotEmpty() }
            }
            found
        }
    }

    /** Reminder minutes chosen in the app, stored as pop-up alerts. */
    private fun alertRows(minutes: List<Int>) =
        minutes.map { ReminderRow(it, CalendarContract.Reminders.METHOD_ALERT) }

    /**
     * Returns the reminder rows for a new row cut from a series: the caller's [reminders] as
     * alerts, or when null a copy of the series' rows.
     */
    private fun reminderRowsForNewRow(reminders: List<Int>?, masterEventId: Long) =
        reminders?.let(::alertRows) ?: readReminderRows(masterEventId)

    /**
     * Reads the series' ORGANIZER and HAS_ATTENDEE_DATA for a new row cut from the series. A
     * missing row or null cursor throws, like the other copy reads.
     */
    private fun readOrganizerForCopy(eventId: Long): Pair<String?, Int> {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.ORGANIZER, CalendarContract.Events.HAS_ATTENDEE_DATA),
            null, null, null
        ) ?: throw IllegalStateException("Events query for event $eventId returned no cursor")
        return cursor.use {
            check(it.moveToFirst()) { "Series row $eventId not found" }
            (if (it.isNull(0)) null else it.getString(0)) to (if (it.isNull(1)) 0 else it.getInt(1))
        }
    }

    /**
     * Holds what a new row cut from a series (an exception or the future half of a split)
     * copies from it. Guests and tags live in their own tables keyed by event row, so the new
     * row has none of its own; the provider's own exception path copies them for the same
     * reason.
     */
    private class SeriesCopy(
        val guestRows: List<android.content.ContentValues>,
        val tag: TagCopy?,
        val organizer: String?,
        val hasAttendeeData: Int,
    )

    /** A tag row to write: the stored value and the account it's written under. */
    private class TagCopy(val account: Account, val value: String)

    /**
     * Reads the series' guests, organizer and tags for a new row before anything is written,
     * so a failed read fails the write with nothing written. Non-null [categories] replace the
     * series' tags (an empty list means none); null copies the stored value verbatim. A tag row
     * needs the calendar's account, so an unknown account fails the read when there is a tag
     * to write.
     */
    private fun readSeriesCopy(masterEventId: Long, calendarId: Long, categories: List<String>?): SeriesCopy {
        val guestRows = readAttendeeRowsForCopy(masterEventId)
        val tagValue = if (categories != null) encodeCategories(categories) else readCategoriesValueForCopy(masterEventId)
        val tag = tagValue?.let { value ->
            val account = readCalendarAccount(calendarId)
                ?: throw IllegalStateException("No account for calendar $calendarId to write tags under")
            TagCopy(account, value)
        }
        val (organizer, hasAttendeeData) = readOrganizerForCopy(masterEventId)
        return SeriesCopy(guestRows, tag, organizer, hasAttendeeData)
    }

    /**
     * Puts the series' organizer and its "has a full guest list" flag on the new row, as the
     * provider's own exception path does. Without ORGANIZER the provider would name the
     * calendar owner, so a copy of someone else's meeting would claim the user organizes it.
     */
    private fun SeriesCopy.putOrganizerInto(values: android.content.ContentValues) {
        organizer?.let { values.put(CalendarContract.Events.ORGANIZER, it) }
        values.put(CalendarContract.Events.HAS_ATTENDEE_DATA, hasAttendeeData)
    }

    /**
     * Returns batch inserts for the copied guests and tags, attached to the event inserted at
     * [eventBackReference]. Tags are written in sync-adapter mode, as in [writeCategories], but
     * inside the batch so the new row never exists without them. The batch still counts as an
     * app write (the provider takes that from its first operation), so the change is uploaded
     * as usual.
     */
    private fun SeriesCopy.insertOps(eventBackReference: Int): List<android.content.ContentProviderOperation> {
        val guestOps = guestRows.map { guest ->
            android.content.ContentProviderOperation.newInsert(Attendees.CONTENT_URI)
                .withValues(guest)
                .withValueBackReference(Attendees.EVENT_ID, eventBackReference)
                .build()
        }
        val tagOp = tag?.let {
            android.content.ContentProviderOperation
                .newInsert(syncAdapterExtendedPropertiesUri(it.account.name, it.account.type))
                .withValueBackReference(CalendarContract.ExtendedProperties.EVENT_ID, eventBackReference)
                .withValue(CalendarContract.ExtendedProperties.NAME, EXTNAME_CATEGORIES)
                .withValue(CalendarContract.ExtendedProperties.VALUE, it.value)
                .build()
        }
        return guestOps + listOfNotNull(tagOp)
    }

    /** Returns batch inserts for [rows], attached to the event inserted at [eventBackReference]. */
    private fun reminderInsertOps(rows: List<ReminderRow>, eventBackReference: Int) =
        rows.map { row ->
            android.content.ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventBackReference)
                .withValue(CalendarContract.Reminders.MINUTES, row.minutes)
                .withValue(CalendarContract.Reminders.METHOD, row.method)
                .build()
        }

    override suspend fun getReminders(eventId: Long): List<Int> = withContext(Dispatchers.IO) {
        try {
            readReminderRows(eventId).map { it.minutes }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading reminders", e)
            emptyList()
        }
    }

    /**
     * Returns each event's reminder minutes, sorted, from one query per 500 ids. Returns an
     * empty map on any error.
     */
    override suspend fun getRemindersForEvents(eventIds: Set<Long>): Map<Long, List<Int>> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyMap()

        try {
            val results = mutableMapOf<Long, MutableList<Int>>()

            // Chunked under SQLite's variable limit (default 999).
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

    override suspend fun getCategoriesForEvents(
        eventIds: Set<Long>
    ): Map<Long, List<String>> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyMap()

        try {
            val results = mutableMapOf<Long, List<String>>()

            // Chunked under SQLite's variable limit (default 999); the NAME arg counts too.
            for (chunk in eventIds.toList().chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val selection =
                    "${CalendarContract.ExtendedProperties.NAME} = ? AND " +
                        "${CalendarContract.ExtendedProperties.EVENT_ID} IN ($placeholders)"
                val args = (listOf(EXTNAME_CATEGORIES) + chunk.map { it.toString() }).toTypedArray()

                contentResolver.query(
                    CalendarContract.ExtendedProperties.CONTENT_URI,
                    arrayOf(
                        CalendarContract.ExtendedProperties.EVENT_ID,
                        CalendarContract.ExtendedProperties.VALUE
                    ),
                    selection,
                    args,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val eventId = cursor.getLong(0)
                        val decoded = decodeCategories(cursor.getString(1))
                        if (decoded.isNotEmpty()) results[eventId] = decoded
                    }
                }
            }

            results
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked during categories batch query", e)
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Error batch reading categories", e)
            emptyMap()
        }
    }

    override suspend fun findExceptionEventId(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean
    ): Long? = withContext(Dispatchers.IO) {
        val seriesAllDay = try {
            seriesIsAllDay(masterEventId)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the series' all-day flag", e)
            null
        } ?: isAllDay
        findExceptionRowId(masterEventId, originalInstanceTime, seriesAllDay, includeCancelled = false)
    }

    /**
     * Returns the exception row for one occurrence, skipping rows that are deleted but not yet
     * purged (the platform says a deleted row "should be ignored"), or null if none or the
     * query fails.
     *
     * A live row (not STATUS_CANCELED; a null status counts as live) always wins. Only when
     * there is none and [includeCancelled] is set is a cancelled row returned: like a deleted
     * one it shows as no occurrence, so it is never the row behind the occurrence the user is
     * editing, but a delete may reuse it instead of cancelling the occurrence twice.
     * [seriesAllDay] is the series' own all-day flag, which names the slot.
     */
    private fun findExceptionRowId(
        masterEventId: Long,
        originalInstanceTime: Long,
        seriesAllDay: Boolean,
        includeCancelled: Boolean,
    ): Long? {
        return try {
            val normalizedTime = if (seriesAllDay)
                DateTimeUtils.normalizeToUtcMidnight(originalInstanceTime) else originalInstanceTime
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.STATUS),
                "${CalendarContract.Events.ORIGINAL_ID} = ? AND " +
                    "${CalendarContract.Events.ORIGINAL_INSTANCE_TIME} = ? AND " +
                    "${CalendarContract.Events.DELETED} = 0",
                arrayOf(masterEventId.toString(), normalizedTime.toString()),
                "${CalendarContract.Events._ID} ASC"
            )?.use { cursor ->
                var cancelled: Long? = null
                while (cursor.moveToNext()) {
                    val isCancelled = !cursor.isNull(1) &&
                        cursor.getInt(1) == CalendarContract.Events.STATUS_CANCELED
                    if (!isCancelled) return@use cursor.getLong(0)
                    if (cancelled == null) cancelled = cursor.getLong(0)
                }
                if (includeCancelled) cancelled else null
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
     * Counts the occurrences of [eventId] the provider shows in `[rangeStartMs, rangeEndMs]`
     * (both ends included) for [editThisAndFuture]'s COUNT split. Returns 0 for an empty range
     * and null when the query fails or returns no cursor; a SecurityException is rethrown for
     * the caller to report as a permission error.
     */
    private fun countInstancesInRange(
        eventId: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): Int? {
        if (rangeEndMs < rangeStartMs) return 0
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
            )?.use { it.count }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "countInstancesInRange query failed", e)
            null
        }
    }

    /**
     * Returns the update that ends series [eventId] with [truncatedRrule], or null when the row
     * or its DTSTART is gone.
     *
     * The provider rebuilds a series' occurrences only from the values in the update itself:
     * without DTSTART it keeps the old ones, and without RRULE it treats the row as a one-off.
     * So the update carries the series' own start, length, zone and all-day flag, written back
     * as stored; a column stored as null is left out, so it stays null. A failed read throws,
     * so the caller writes nothing instead of an update the phone would not show.
     */
    private fun seriesEndValues(eventId: Long, truncatedRrule: String): android.content.ContentValues? {
        val columns = arrayOf(
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
        )
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), columns, null, null, null
        ) ?: throw IllegalStateException("Couldn't read series $eventId")
        return cursor.use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return@use null
            android.content.ContentValues().apply {
                put(CalendarContract.Events.DTSTART, c.getLong(0))
                if (!c.isNull(1)) put(CalendarContract.Events.DURATION, c.getString(1))
                if (!c.isNull(2)) put(CalendarContract.Events.EVENT_TIMEZONE, c.getString(2))
                if (!c.isNull(3)) put(CalendarContract.Events.ALL_DAY, c.getInt(3))
                put(CalendarContract.Events.RRULE, truncatedRrule)
            }
        }
    }

    /**
     * Returns whether an event is an exception (ORIGINAL_ID set). [updateEvent] uses it to
     * leave RRULE out of the update.
     */
    private fun isExceptionEvent(eventId: Long): Boolean {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.ORIGINAL_ID),
            null, null, null
        )
        return cursor?.use { if (it.moveToFirst()) !it.isNull(0) else false } ?: false
    }

    /**
     * Returns whether series [eventId] is all-day (a null flag reads as timed), or null when
     * its row is gone. The occurrence an exception replaces is one of the series' slots, so
     * this flag, not the exception's own, decides how its original time is stored and matched.
     */
    private fun seriesIsAllDay(eventId: Long): Boolean? {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.ALL_DAY),
            null, null, null
        )
        return cursor?.use { if (it.moveToFirst()) it.getInt(0) != 0 else null }
    }

    /**
     * Returns an event's _SYNC_ID (set by sync adapters), or null. [createException] and
     * [deleteSingleOccurrence] copy it into a new exception's ORIGINAL_SYNC_ID so sync adapters
     * can match the exception to its master.
     */
    internal fun getMasterSyncId(eventId: Long): String? {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._SYNC_ID),
            null, null, null
        )
        return cursor?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    }

    // ==================== Reminder Operations ====================

    override suspend fun getNextUpcomingReminder(
        enabledCalendarIds: Set<Long>,
        afterMs: Long
    ): UpcomingDeviceReminder? = withContext(Dispatchers.IO) {
        if (enabledCalendarIds.isEmpty()) return@withContext null

        try {
            // The next 30 days.
            val startMs = afterMs
            val endMs = afterMs + (30L * DateUtils.DAY_IN_MILLIS)

            val builder = Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)

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
                            // The event color if set, else the calendar's.
                            calendarColor = eventColorValue ?: calendarColorValue,
                            calendarId = calendarId
                        )
                    )
                }
            }

            if (instancesWithAlarms.isEmpty()) return@withContext null

            var earliest: UpcomingDeviceReminder? = null

            for (instance in instancesWithAlarms) {
                val reminders = getReminders(instance.eventId)
                for (reminderMinutes in reminders) {
                    val triggerTime = calculateReminderTriggerTime(
                        occurrenceStartTs = instance.occurrenceStartTs,
                        reminderMinutes = reminderMinutes,
                        isAllDay = instance.isAllDay
                    )

                    if (triggerTime <= afterMs) continue

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
     * Returns when a reminder fires: the start minus [reminderMinutes] for a timed event, and
     * for an all-day event the event day's local midnight minus [reminderMinutes].
     */
    private fun calculateReminderTriggerTime(
        occurrenceStartTs: Long,
        reminderMinutes: Int,
        isAllDay: Boolean
    ): Long {
        // Reminders.MINUTES is minutes before the start (negative = after); the signed offset is
        // its negation.
        val offsetMs = -reminderMinutes.toLong() * 60 * 1000

        return if (isAllDay) {
            // Same formula as the Room scheduler's calculateAllDayTriggerTime (stored == fired ==
            // synced).
            DateTimeUtils.allDayReminderTriggerTime(occurrenceStartTs, offsetMs)
        } else {
            occurrenceStartTs + offsetMs
        }
    }

    /** An occurrence with alarms, before its reminders are read. */
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
        val startTs = cursor.getLong(5)
        val rawEndTs = if (cursor.isNull(6)) null else cursor.getLong(6)
        // The edit form's date picker reads the all-day end back through
        // DateTimeUtils.utcMidnightToLocalDate; the exclusive DTEND would show the day after
        // the event's last day.
        val endTs = inclusiveEndForDeviceEvent(rawEndTs, startTs, isAllDay)

        return DeviceEvent(
            id = cursor.getLong(0),
            calendarId = cursor.getLong(1),
            title = cursor.getString(2).orEmpty(),
            description = cursor.getString(3),
            location = cursor.getString(4),
            startTs = startTs,
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
 * Converts an Events row's exclusive all-day DTEND (next midnight) to KashCal's inclusive
 * endTs (last ms of the last day), as Room Event.endTs stores it.
 *
 * Must agree with the conversion in mapToInstances, so an event on the grid shows the same end
 * date when reopened in the edit form. Returns null when DTEND is null (a series uses DURATION).
 * A degenerate `dtend <= dtstart` row is returned unchanged.
 */
internal fun inclusiveEndForDeviceEvent(
    dtend: Long?,
    dtstart: Long,
    isAllDay: Boolean
): Long? {
    if (dtend == null) return null
    if (!isAllDay) return dtend
    return if (dtend > dtstart) dtend - 1 else dtend
}

/**
 * Builds the ExtendedProperties URI that identifies the caller as the sync adapter for an
 * account. Writing an ExtendedProperty on a synced calendar silently no-ops unless
 * CALLER_IS_SYNCADAPTER=true is set with the owning calendar's ACCOUNT_NAME and ACCOUNT_TYPE,
 * so every tag write uses it. The account must match the calendar that owns the event.
 */
internal fun syncAdapterExtendedPropertiesUri(accountName: String, accountType: String) =
    CalendarContract.ExtendedProperties.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
        .build()

/**
 * Returns the selection for the upcoming device reminder query. It always hides self-declined
 * events: the alarm pipeline treats a decline as "no", whatever the "Show declined" toggle.
 */
internal fun buildUpcomingReminderSelection(): String =
    "${Instances.HAS_ALARM} = 1 AND " +
        "${Calendars.VISIBLE} = 1 AND " +
        "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED}"

/**
 * Builds the values written when the user ticks a device calendar.
 *
 * Sets both flags because on Xiaomi/MIUI Google calendars ship with SYNC_EVENTS=0 and
 * VISIBLE=0. The Instances queries filter on VISIBLE=1, and events are never downloaded without
 * SYNC_EVENTS=1. At file level so tests can check both keys; a typo in either would silently
 * break MIUI users.
 */
internal fun buildCalendarVisibleValues(): android.content.ContentValues {
    return android.content.ContentValues().apply {
        put(android.provider.CalendarContract.Calendars.SYNC_EVENTS, 1)
        put(android.provider.CalendarContract.Calendars.VISIBLE, 1)
    }
}

/**
 * Returns true when an account type is a LOCAL device calendar, one with no sync adapter.
 * [shouldSkipRequestSync] and [DeviceCalendar.canDeliverInvites] both use it, so they can't
 * drift apart.
 */
internal fun isLocalAccountType(accountType: String): Boolean =
    accountType.equals(android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL, ignoreCase = true)

/**
 * Returns whether to skip `requestSync` for an account: a LOCAL account has no sync adapter to
 * receive it. Callers must pass a non-blank `account.name` and `account.type`;
 * `readCalendarAccount` guarantees that.
 */
internal fun shouldSkipRequestSync(account: android.accounts.Account): Boolean =
    isLocalAccountType(account.type)

/**
 * Holds the guests a device-event edit added ([toInsert]) or removed ([toDelete]). An
 * unchanged guest is in neither, so its provider row and the pulled-down `ATTENDEE_STATUS` on
 * it survive the edit.
 */
internal data class AttendeeDiff(
    val toInsert: List<DeviceAttendee>,
    val toDelete: List<DeviceAttendee>,
)

/**
 * Canonicalizes a device attendee email for comparison: strips a leading `mailto:` and
 * lowercases. Matches the read-side UI mapper, so an unedited open-and-save changes no rows.
 */
internal fun canonicalAttendeeEmail(raw: String): String =
    org.onekash.kashcal.util.AddressNormalizer.canonical(
        "mailto:" + org.onekash.kashcal.util.AddressNormalizer.stripMailto(raw)
    )

/**
 * Computes the [AttendeeDiff] between the event's rows ([existing]) and the guests the user
 * wants ([desired]), keyed on canonical email.
 *
 * The organizer row is never deleted: it's written once, when guests first appear
 * ([ownerRowNeeded]), and a guest edit must never remove it. Rows with a blank or null email
 * can't be keyed, so they are in neither set.
 */
internal fun computeAttendeeDiff(
    existing: List<DeviceAttendee>,
    desired: List<DeviceAttendee>,
): AttendeeDiff {
    fun key(a: DeviceAttendee): String? =
        a.email?.takeUnless { it.isBlank() }?.let { canonicalAttendeeEmail(it) }

    val existingGuests = existing.filter {
        it.relationship != android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
    }
    val existingKeys = existingGuests.mapNotNull { key(it) }.toSet()
    val desiredKeys = desired.mapNotNull { key(it) }.toSet()

    val toDelete = existingGuests.filter { g -> key(g)?.let { it !in desiredKeys } ?: false }
    val toInsert = desired.filter { d -> key(d)?.let { it !in existingKeys } ?: false }
    return AttendeeDiff(toInsert = toInsert, toDelete = toDelete)
}

/**
 * Returns whether [email] is a real address to write as the event's organizer.
 *
 * Excludes blank or null and machine-generated group addresses ending in
 * `calendar.google.com`, such as a shared calendar's `...@group.calendar.google.com`
 * `OWNER_ACCOUNT`. Such an address means nothing as the organizer, so no owner row is written.
 */
internal fun isValidOrganizerEmail(email: String?): Boolean {
    val trimmed = email?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    return !trimmed.endsWith("calendar.google.com", ignoreCase = true)
}

/**
 * Returns the guests to write without the calendar owner, who has the organizer row; a guest
 * with the same canonical address would duplicate it.
 *
 * Removes the owner only when it's a valid organizer address ([isValidOrganizerEmail]). With
 * no owner row there's no duplicate, so the matching guest is kept instead of silently lost.
 */
internal fun guestsExcludingOwner(
    desired: List<DeviceAttendee>,
    ownerEmail: String?,
): List<DeviceAttendee> {
    if (!isValidOrganizerEmail(ownerEmail)) return desired
    val canonicalOwner = canonicalAttendeeEmail(ownerEmail!!)
    return desired.filter { g ->
        g.email?.takeUnless { it.isBlank() }?.let { canonicalAttendeeEmail(it) != canonicalOwner } ?: true
    }
}

/**
 * Returns whether this save writes an owner (organizer) row: only when [desired] has guests,
 * the owner email passes [isValidOrganizerEmail], and [existing] has no organizer row. The
 * last condition serves updates: a solo event gaining a guest gets an owner row, but an event
 * that already has its organizer doesn't get a duplicate.
 */
internal fun ownerRowNeeded(
    existing: List<DeviceAttendee>,
    desired: List<DeviceAttendee>,
    ownerEmail: String?,
): Boolean {
    if (desired.isEmpty()) return false
    if (!isValidOrganizerEmail(ownerEmail)) return false
    val alreadyHasOrganizer = existing.any {
        it.relationship == android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
    }
    return !alreadyHasOrganizer
}

/**
 * Builds the owner's attendee row: `RELATIONSHIP_ORGANIZER`, `TYPE_REQUIRED`,
 * `STATUS_ACCEPTED`, so the guest list shows the host. Written only when [ownerRowNeeded].
 */
internal fun buildOwnerAttendeeValues(ownerEmail: String): android.content.ContentValues =
    android.content.ContentValues().apply {
        put(android.provider.CalendarContract.Attendees.ATTENDEE_EMAIL, ownerEmail)
        put(
            android.provider.CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
        )
        put(
            android.provider.CalendarContract.Attendees.ATTENDEE_TYPE,
            android.provider.CalendarContract.Attendees.TYPE_REQUIRED
        )
        put(
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS,
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        )
    }

/**
 * Builds a guest's attendee row: `RELATIONSHIP_ATTENDEE`, `TYPE_REQUIRED`, `STATUS_NONE` (no
 * response yet). A null display name is left out, not written as null.
 */
internal fun buildGuestAttendeeValues(attendee: DeviceAttendee): android.content.ContentValues =
    android.content.ContentValues().apply {
        attendee.name?.let {
            put(android.provider.CalendarContract.Attendees.ATTENDEE_NAME, it)
        }
        put(android.provider.CalendarContract.Attendees.ATTENDEE_EMAIL, attendee.email)
        put(
            android.provider.CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            android.provider.CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
        )
        put(
            android.provider.CalendarContract.Attendees.ATTENDEE_TYPE,
            android.provider.CalendarContract.Attendees.TYPE_REQUIRED
        )
        put(
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS,
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_NONE
        )
    }

/**
 * Builds the Events row values for an event body.
 *
 * - All-day: zone UTC, and the inclusive end becomes the exclusive next midnight.
 * - Series: RFC 5545 DURATION, DTEND null.
 * - One-off: DTEND, DURATION null.
 *
 * @param startTs epoch ms; UTC midnight for all-day
 * @param endTs inclusive end, KashCal's convention
 * @param rrule null or empty for a one-off
 * @param duration computed from [endTs] when null
 * @param timezone ignored for all-day, which uses UTC
 * @param isException for a one-off, the row is an exception: RRULE is left out of the values,
 *   and RDATE, EXDATE and EXRULE are written as null. Otherwise a one-off writes RRULE as null,
 *   so a series can become a one-off.
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

    val effectiveTimezone = if (isAllDay) "UTC" else timezone
    values.put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, effectiveTimezone)

    val isRecurring = !rrule.isNullOrEmpty()

    if (isRecurring) {
        values.put(android.provider.CalendarContract.Events.RRULE, rrule)

        val effectiveDuration = duration ?: calculateDuration(startTs, endTs, isAllDay)
        values.put(android.provider.CalendarContract.Events.DURATION, effectiveDuration)
        values.putNull(android.provider.CalendarContract.Events.DTEND)
    } else {
        if (!isException) {
            values.putNull(android.provider.CalendarContract.Events.RRULE)
        }
        if (isException) {
            values.putNull(android.provider.CalendarContract.Events.RDATE)
            values.putNull(android.provider.CalendarContract.Events.EXDATE)
            values.putNull(android.provider.CalendarContract.Events.EXRULE)
        }
        values.putNull(android.provider.CalendarContract.Events.DURATION)

        if (isAllDay && endTs != null) {
            // Last ms of the last day, plus 1 ms, rounded down to UTC midnight.
            val endPlusOne = endTs + 1
            val effectiveEndTs = (endPlusOne / 86_400_000) * 86_400_000
            values.put(android.provider.CalendarContract.Events.DTEND, effectiveEndTs)
        } else if (endTs != null) {
            values.put(android.provider.CalendarContract.Events.DTEND, endTs)
        }
    }

    return values
}

/**
 * Returns the RFC 5545 duration from start to end: `P<n>D` (at least 1) for all-day,
 * `PT<n>H<n>M` for timed, and `PT0M` when [endTs] is null.
 */
private fun calculateDuration(startTs: Long, endTs: Long?, isAllDay: Boolean): String {
    if (endTs == null) return "PT0M"

    if (isAllDay) {
        // endTs is inclusive (last ms of the last day), so add 1 ms for the exclusive end.
        val durationMs = (endTs + 1) - startTs
        val days = (durationMs / 86_400_000).toInt().coerceAtLeast(1)
        return "P${days}D"
    } else {
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
 * Parses an RFC 5545 duration to milliseconds: `P<n>W`, `P<n>D`, and time durations such as
 * PT1H30M. A null, empty, unparseable or overflowing value falls back to 1 day for all-day or
 * 1 hour for timed; a W or D count that isn't a number reads as 1.
 */
internal fun parseDurationMs(duration: String?, isAllDay: Boolean): Long {
    val defaultMs = if (isAllDay) 86_400_000L else 3_600_000L
    if (duration.isNullOrEmpty()) return defaultMs
    return try {
        if (duration.startsWith("P") && !duration.contains("T")) {
            val cleaned = duration.removePrefix("P")
            when {
                cleaned.endsWith("W") -> {
                    val weeks = cleaned.removeSuffix("W").toLongOrNull() ?: 1
                    // An absurd count overflows into the catch below (defaultMs), never a
                    // negative DTEND.
                    Math.multiplyExact(weeks, 7 * 86_400_000L)
                }
                cleaned.endsWith("D") -> {
                    val days = cleaned.removeSuffix("D").toLongOrNull() ?: 1
                    Math.multiplyExact(days, 86_400_000L)
                }
                else -> defaultMs
            }
        } else {
            // java.time.Duration parses time durations such as PT1H30M.
            java.time.Duration.parse(duration).toMillis()
        }
    } catch (_: Exception) {
        defaultMs
    }
}

/** Converts a YYYYMMDD day code to local start-of-day epoch ms. */
internal fun dayCodeToStartOfDayMs(dayCode: Int): Long {
    val year = dayCode / 10000
    val month = (dayCode % 10000) / 100
    val day = dayCode % 100
    return LocalDate.of(year, month, day)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant().toEpochMilli()
}


/** Converts a YYYYMMDD day code to local end-of-day epoch ms (23:59:59.999). */
internal fun dayCodeToEndOfDayMs(dayCode: Int): Long {
    val year = dayCode / 10000
    val month = (dayCode % 10000) / 100
    val day = dayCode % 100
    return LocalDate.of(year, month, day)
        .atTime(23, 59, 59, 999_000_000)
        .atZone(ZoneId.systemDefault())
        .toInstant().toEpochMilli()
}

