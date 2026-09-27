package org.onekash.kashcal.data.calendar_provider

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.robolectric.Robolectric

/**
 * A small CalendarProvider stand-in for Robolectric tests, backed by an
 * in-memory SQLite database, so [AndroidCalendarProviderRepository]'s real
 * queries, selections and batches run against real SQL.
 *
 * It models what the repository relies on, following the platform's own
 * provider code:
 * - Calendars, Events, Reminders, Attendees and ExtendedProperties tables,
 *   with the CalendarContract column names. Writing a column the table
 *   doesn't have throws, so a misspelt column fails the test instead of
 *   being stored silently.
 * - `table` and `table/#` URIs for query, insert, update and delete, and a
 *   transactional applyBatch: an operation that throws rolls back the batch.
 * - Writes the platform refuses are refused the same way, with its messages
 *   (checked against the platform provider's source and on an Android 17
 *   device): a selection together with a `table/#` URI; a bulk update or
 *   delete of events, attendees or reminders without a selection (calendars
 *   may be updated in bulk); any caller writing, through the Events URI, a
 *   column that belongs to the calendar (its colour, name, visibility, access
 *   level and the like); an app writing a column only a sync adapter may
 *   write (an event's or any row's sync id and dirty flag, an event's sync
 *   data, a calendar's owner, access level, reminder limits and so on). The
 *   presence of the key is what counts, even with a null value.
 * - An attendee, reminder or extended property must name an event. One naming
 *   an event that isn't there is dropped with no URI, so a batch holding it
 *   fails; a deleted but not yet purged event still counts as there.
 * - On top of what the platform enforces, every app write through the
 *   resolver is held to the documented contract, which is stricter: only the
 *   documented app-writable columns of Events, Attendees, Reminders and
 *   Calendars; the fields an insert must carry; an event with an end time
 *   when one-off and a duration when recurring; no recurrence on an
 *   exception; all-day rows in UTC on midnight boundaries; an attendee's
 *   identity and namespace written together; and RFC 5545 grammar for the
 *   RRULE and DURATION values it writes. A breach throws
 *   [CalendarContractViolation]. STATUS and HAS_ATTENDEE_DATA are allowed by
 *   name. Sync adapter writes and the seeding helpers are exempt, a value an
 *   update writes back unchanged is not re-checked (an RRULE is, when the
 *   update changes ALL_DAY), and an update of a row stored off the contract
 *   is checked only for what it writes. Only writes the tests exercise are
 *   checked.
 * - An app update that changes a series' rule, exclusions or times without
 *   carrying its DTSTART and RRULE throws [CalendarContractViolation] too.
 *   The platform accepts it but rebuilds the series' occurrences only from
 *   the update's own values, so the phone would keep showing the old ones;
 *   this view, recomputed per query, could not show that.
 * - App (non-sync-adapter) Events inserts are validated like the platform's:
 *   calendar, timezone and start required, exactly one of DTEND and DURATION,
 *   an RRULE that names a FREQ. App updates validate the merged row when the
 *   stored row was valid, and may not set SELF_ATTENDEE_STATUS. Sync-adapter
 *   writes are not validated.
 * - Events inserts are rewritten like the platform's: an exception carrying
 *   only ORIGINAL_ID gets the master's sync id and vice versa, a synced
 *   master links earlier sync-id-only exceptions to itself, a missing
 *   ORGANIZER becomes the calendar owner, HAS_ALARM in the values is ignored,
 *   and an app's insert or update marks the row DIRTY.
 *   When a row's `_SYNC_ID` is set later (a sync adapter's upload), every
 *   exception pointing at it by ORIGINAL_ID gets it as ORIGINAL_SYNC_ID, as the
 *   platform's trigger does; no other update backfills anything.
 * - SELF_ATTENDEE_STATUS is kept on the Events row as the platform does:
 *   written when an attendee row whose email equals the calendar's
 *   OWNER_ACCOUNT exactly (case included) is inserted or updated; an organizer
 *   row without a status counts as accepted.
 * - ExtendedProperties can only be written in sync-adapter mode (the URI
 *   carries CALLER_IS_SYNCADAPTER plus the account); any other write throws.
 * - Deletes follow the platform: a sync-adapter delete, or an app delete of a
 *   row that was never synced (no `_SYNC_ID`), removes the row and its
 *   reminders, attendees and extended properties (and, for a never-synced
 *   series, its exceptions). An app delete of a synced row marks it
 *   `DELETED = 1`, removes its never-synced exceptions, reminders and extended
 *   properties, and keeps its attendees.
 * - The Instances view (`instances/when/{begin}/{end}`) and its search variant
 *   (`instances/search/{begin}/{end}/{query}`) are computed per query:
 *   - only calendars with SYNC_EVENTS on are expanded, joined with their
 *     Calendars row (name, colour, access level, visibility);
 *   - a series (non-empty RRULE) is expanded with the app's recurrence
 *     engine, each occurrence lasting its DURATION (parsed the way the
 *     platform parses it, seconds-only forms included); all-day rows and rows
 *     without a timezone expand in UTC; a UTC UNTIL is applied here as an
 *     instant for every row, as the platform does, because the app's engine
 *     reads it on the phone's clock for all-day rows;
 *   - an exception whose ORIGINAL_SYNC_ID names a series in the same calendar
 *     replaces that series' occurrence at ORIGINAL_INSTANCE_TIME, or just
 *     removes it when cancelled or deleted; an exception without a sync id
 *     is a plain event to the view, so the series keeps its occurrence;
 *   - deleted rows and deleted or cancelled series yield nothing;
 *   - the window keeps occurrences with BEGIN <= end AND END >= begin, both
 *     ends inclusive, as the platform does;
 *   - search splits the query into words (quoted phrases kept whole) and
 *     keeps occurrences where every word matches the title, description,
 *     location, or a guest's email or name, folding case for ASCII letters
 *     only, as SQLite LIKE does;
 *   - HAS_ALARM is 1 when the event has reminder rows.
 *
 * Not modelled: RDATE and EXDATE (a series with only an RDATE shows as a
 * one-off at its DTSTART, and RDATE/EXDATE values are not applied), the
 * platform's own recurrence expander (so differences between it and the
 * app's engine are invisible here), the platform's RRULE parser (it rejects
 * unknown or repeated parts), the refusal of SELF_ATTENDEE_STATUS writes from
 * sync adapters too, the account a sync adapter must name on URIs other than
 * extended properties, the refusal of a bulk extended-property update, the
 * all-day rewrite of start, end and a `P<n>S` duration (which also throws on
 * the `PT<n>S` form), the dirty flag an app's attendee or reminder write sets
 * on its event, stable Instances ids, and the events a calendar delete
 * removes. The view is recomputed from the rows on
 * every query; the platform maintains its Instances table incrementally, so
 * right after a write it can briefly differ from what a full re-expansion
 * (this view) shows.
 */
class SqliteCalendarProvider : ContentProvider() {

    /** How a query against one table should misbehave. */
    enum class QueryFailure { THROW, NULL_CURSOR }

    /**
     * Per-table query failures, keyed by table name (for example "Reminders")
     * or [INSTANCES] for the Instances view. Checked before the projection
     * failures below.
     */
    val queryFailures = mutableMapOf<String, QueryFailure>()

    /**
     * Query failures keyed by a projected column: a table query fails when its
     * projection names the column. Lets a test fail one read of a table
     * without failing every other read of it. Instances queries don't check
     * this or [exactProjectionFailures].
     */
    val projectionFailures = mutableMapOf<String, QueryFailure>()

    /**
     * Query failures keyed by an exact projection (column set): fails one read
     * whose columns other reads of the table share.
     */
    val exactProjectionFailures = mutableMapOf<Set<String>, QueryFailure>()

    /** One app update of Events: the rows it matched and the columns it carried. */
    data class EventUpdate(val rowIds: List<Long>, val columns: Set<String>)

    /** Every app (non-sync-adapter) update of Events that passed the checks, in order. */
    val appEventUpdates = mutableListOf<EventUpdate>()

    lateinit var db: SQLiteDatabase
        private set

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        for ((table, columns) in TABLES) {
            val defs = columns.joinToString(", ") { col ->
                when (col) {
                    BaseColumns._ID -> "${BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT"
                    Events.DELETED, Events.HAS_ATTENDEE_DATA, Reminders.METHOD, Events.DIRTY -> "$col INTEGER DEFAULT 0"
                    Calendars.VISIBLE -> "$col INTEGER DEFAULT 1"
                    Calendars.SYNC_EVENTS -> "$col INTEGER DEFAULT 0"
                    // Column affinity matters: selection args arrive as strings, and
                    // only an INTEGER column converts them before comparing, as the
                    // platform's tables do.
                    in TEXT_COLUMNS -> "$col TEXT"
                    else -> "$col INTEGER"
                }
            }
            db.execSQL("CREATE TABLE $table ($defs)")
        }
        // The platform's trigger: once a series gets its sync id, the exceptions
        // linked to it by id carry that id too.
        db.execSQL(
            "CREATE TRIGGER original_sync_update UPDATE OF ${Events._SYNC_ID} ON $EVENTS BEGIN " +
                "UPDATE $EVENTS SET ${Events.ORIGINAL_SYNC_ID} = new.${Events._SYNC_ID} " +
                "WHERE ${Events.ORIGINAL_ID} = old.${Events._ID}; END"
        )
        return true
    }

    // ---- ContentProvider ----

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.pathSegments.firstOrNull() == "instances") {
            when (queryFailures[INSTANCES]) {
                QueryFailure.THROW -> throw IllegalStateException("query on $INSTANCES failed")
                QueryFailure.NULL_CURSOR -> return null
                null -> Unit
            }
            return queryInstances(uri, projection, selection, selectionArgs, sortOrder)
        }
        val (table, id) = resolve(uri)
        val failure = queryFailures[table]
            ?: projection?.let { exactProjectionFailures[it.toSet()] }
            ?: projection?.firstNotNullOfOrNull { projectionFailures[it] }
        when (failure) {
            QueryFailure.THROW -> throw IllegalStateException("query on $table failed")
            QueryFailure.NULL_CURSOR -> return null
            null -> Unit
        }
        val (sel, args) = withId(id, selection, selectionArgs)
        return db.query(table, projection, sel, args, null, null, sortOrder)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val (table, id) = resolve(uri)
        val v = ContentValues(values ?: ContentValues())
        val syncAdapter = isSyncAdapter(uri)
        verifyWriteAllowed(Write.INSERT, uri, table, id, v, selection = null, syncAdapter)
        checkColumns(table, v)
        if (table == EVENTS && !syncAdapter) requireValid(v)
        val eventId = requiredEventId(table, v)
        // v is still exactly what the caller sent: the rewrites happen in insertEvent.
        if (!syncAdapter) {
            checkAppWriteColumns(table, v)
            checkAppWriteRow(table, v, stored = null, isInsert = true)
        }
        // An attendee, reminder or extended property naming an event that isn't there is
        // dropped: the platform returns no URI, which fails a batch.
        if (eventId != null && !eventExists(eventId)) return null
        val newId = when (table) {
            EVENTS -> insertEvent(v, syncAdapter)
            ATTENDEES -> db.insertOrThrow(table, null, v).also { updateSelfAttendeeStatus(v) }
            else -> db.insertOrThrow(table, null, v)
        }
        return ContentUris.withAppendedId(uri.buildUpon().clearQuery().build(), newId)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val (table, id) = resolve(uri)
        val v = ContentValues(values ?: ContentValues())
        val syncAdapter = isSyncAdapter(uri)
        verifyWriteAllowed(Write.UPDATE, uri, table, id, v, selection, syncAdapter)
        checkColumns(table, v)
        // Checked once, whatever rows match, so an update that matches nothing is still checked.
        // Events check theirs after the platform's own update rules, which come first on a phone.
        if (!syncAdapter && table != EVENTS) checkAppWriteColumns(table, v)
        val (sel, args) = withId(id, selection, selectionArgs)
        return when (table) {
            EVENTS -> updateEvents(v, sel, args, syncAdapter)
            ATTENDEES -> {
                val ids = idsOf(ATTENDEES, sel, args)
                if (!syncAdapter) {
                    ids.forEach { rowId -> checkAppWriteRow(ATTENDEES, v, rowValues(ATTENDEES, rowId), isInsert = false) }
                }
                val count = db.update(table, v, sel, args)
                ids.forEach { rowId -> rowValues(ATTENDEES, rowId)?.let(::updateSelfAttendeeStatus) }
                count
            }
            else -> db.update(table, v, sel, args)
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val (table, id) = resolve(uri)
        val syncAdapter = isSyncAdapter(uri)
        verifyWriteAllowed(Write.DELETE, uri, table, id, values = null, selection, syncAdapter)
        val (sel, args) = withId(id, selection, selectionArgs)
        if (table != EVENTS) return db.delete(table, sel, args)

        var count = 0
        db.query(EVENTS, arrayOf(Events._ID, Events._SYNC_ID, Events.RRULE, Events.RDATE), sel, args, null, null, null).use { c ->
            while (c.moveToNext()) {
                val rowId = c.getLong(0)
                val synced = !c.getString(1).isNullOrEmpty()
                val recurring = !c.getString(2).isNullOrEmpty() || !c.getString(3).isNullOrEmpty()
                if (syncAdapter || !synced) {
                    hardDeleteEvent(rowId)
                    if (recurring && !synced) {
                        idsOf(EVENTS, "${Events.ORIGINAL_ID} = ?", arrayOf(rowId.toString())).forEach(::hardDeleteEvent)
                    }
                } else {
                    db.update(
                        EVENTS,
                        ContentValues().apply { put(Events.DELETED, 1); put(Events.DIRTY, 1) },
                        "${Events._ID} = ?",
                        arrayOf(rowId.toString()),
                    )
                    idsOf(EVENTS, "${Events.ORIGINAL_ID} = ? AND ${Events._SYNC_ID} IS NULL", arrayOf(rowId.toString()))
                        .forEach(::hardDeleteEvent)
                    db.delete(REMINDERS, "${Reminders.EVENT_ID} = ?", arrayOf(rowId.toString()))
                    db.delete(EXTENDED_PROPERTIES, "${ExtendedProperties.EVENT_ID} = ?", arrayOf(rowId.toString()))
                }
                count++
            }
        }
        return count
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        db.beginTransaction()
        try {
            val results = super.applyBatch(operations)
            db.setTransactionSuccessful()
            return results
        } finally {
            db.endTransaction()
        }
    }

    override fun getType(uri: Uri): String? = null

    // ---- Events writes ----

    private fun insertEvent(v: ContentValues, syncAdapter: Boolean): Long {
        v.remove(Events.HAS_ALARM)
        pairOriginalIds(v)
        if (!v.containsKey(Events.ORGANIZER)) {
            v.getAsLong(Events.CALENDAR_ID)?.let(::ownerOf)?.let { v.put(Events.ORGANIZER, it) }
        }
        if (!syncAdapter) v.put(Events.DIRTY, 1)
        val id = db.insertOrThrow(EVENTS, null, v)
        backfillExceptionOriginalIds(id, v)
        return id
    }

    private fun updateEvents(v: ContentValues, sel: String?, args: Array<String>?, syncAdapter: Boolean): Int {
        val sent = ContentValues(v)
        v.remove(Events.HAS_ALARM)
        if (!syncAdapter) {
            require(!v.containsKey(Events.SELF_ATTENDEE_STATUS)) { "Only the provider may write selfAttendeeStatus" }
            val storedRows = idsOf(EVENTS, sel, args).mapNotNull { rowValues(EVENTS, it) }
            // The platform's own validation of every row comes before the stricter contract.
            for (stored in storedRows) {
                if (validationError(stored) == null) requireValid(merged(stored, v))
            }
            checkAppWriteColumns(EVENTS, sent)
            for (stored in storedRows) checkAppWriteRow(EVENTS, sent, stored, isInsert = false)
            for (stored in storedRows) checkSeriesReexpands(sent, stored)
            appEventUpdates.add(EventUpdate(storedRows.map { it.getAsLong(Events._ID) }, sent.keySet().toSet()))
            v.put(Events.DIRTY, 1)
        }
        return db.update(EVENTS, v, sel, args)
    }

    /**
     * The platform pairs ORIGINAL_ID and ORIGINAL_SYNC_ID on every Events
     * insert: whichever is missing is looked up from the other.
     */
    private fun pairOriginalIds(v: ContentValues) {
        val calendarId = v.getAsString(Events.CALENDAR_ID)
        val originalSyncId = v.getAsString(Events.ORIGINAL_SYNC_ID)
        val originalId = v.getAsLong(Events.ORIGINAL_ID)
        if (!originalSyncId.isNullOrEmpty() && originalId == null && calendarId != null) {
            db.query(
                EVENTS, arrayOf(Events._ID),
                "${Events._SYNC_ID} = ? AND ${Events.CALENDAR_ID} = ?", arrayOf(originalSyncId, calendarId),
                null, null, null,
            ).use { if (it.moveToFirst()) v.put(Events.ORIGINAL_ID, it.getLong(0)) }
        } else if (originalSyncId.isNullOrEmpty() && originalId != null) {
            db.query(EVENTS, arrayOf(Events._SYNC_ID), "${Events._ID} = ?", arrayOf(originalId.toString()), null, null, null)
                .use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotEmpty() }?.let { v.put(Events.ORIGINAL_SYNC_ID, it) } }
        }
    }

    /**
     * A newly inserted synced series claims the exceptions in its calendar
     * that already name its sync id.
     */
    private fun backfillExceptionOriginalIds(id: Long, v: ContentValues) {
        val syncId = v.getAsString(Events._SYNC_ID)
        val recurring = !v.getAsString(Events.RRULE).isNullOrEmpty() || !v.getAsString(Events.RDATE).isNullOrEmpty()
        val calendarId = v.getAsString(Events.CALENDAR_ID)
        if (syncId.isNullOrEmpty() || !recurring || calendarId == null) return
        db.update(
            EVENTS,
            ContentValues().apply { put(Events.ORIGINAL_ID, id) },
            "${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.CALENDAR_ID} = ?",
            arrayOf(syncId, calendarId),
        )
    }

    private fun requireValid(v: ContentValues) {
        validationError(v)?.let { throw IllegalArgumentException(it) }
    }

    private fun validationError(v: ContentValues): String? {
        if (v.getAsString(Events.CALENDAR_ID).isNullOrEmpty()) return "Event values must include a calendar_id"
        if (v.getAsString(Events.EVENT_TIMEZONE).isNullOrEmpty()) return "Event values must include an eventTimezone"
        val rrule = v.getAsString(Events.RRULE)
        if (!rrule.isNullOrEmpty() && !rrule.uppercase().contains("FREQ=")) return "Invalid recurrence rule: $rrule"
        if (v.getAsLong(Events.DTSTART) == null) return "DTSTART cannot be empty."
        val hasDtend = v.getAsLong(Events.DTEND) != null
        val hasDuration = !v.getAsString(Events.DURATION).isNullOrEmpty()
        if (!hasDtend && !hasDuration) return "DTEND and DURATION cannot both be null for an event."
        if (hasDtend && hasDuration) return "Cannot have both DTEND and DURATION in an event"
        return null
    }

    private fun hardDeleteEvent(rowId: Long) {
        val arg = arrayOf(rowId.toString())
        db.delete(REMINDERS, "${Reminders.EVENT_ID} = ?", arg)
        db.delete(ATTENDEES, "${Attendees.EVENT_ID} = ?", arg)
        db.delete(EXTENDED_PROPERTIES, "${ExtendedProperties.EVENT_ID} = ?", arg)
        db.delete(EVENTS, "${Events._ID} = ?", arg)
    }

    /** The platform's rule for the Events row's own copy of the user's response. */
    private fun updateSelfAttendeeStatus(attendee: ContentValues) {
        val eventId = attendee.getAsLong(Attendees.EVENT_ID) ?: return
        val calendarId = db.query(EVENTS, arrayOf(Events.CALENDAR_ID), "${Events._ID} = ?", arrayOf(eventId.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.getLong(0) else null } ?: return
        val owner = ownerOf(calendarId) ?: return
        if (owner != attendee.getAsString(Attendees.ATTENDEE_EMAIL)) return
        var status = Attendees.ATTENDEE_STATUS_NONE
        if (attendee.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP) == Attendees.RELATIONSHIP_ORGANIZER) {
            status = Attendees.ATTENDEE_STATUS_ACCEPTED
        }
        attendee.getAsInteger(Attendees.ATTENDEE_STATUS)?.let { status = it }
        db.update(
            EVENTS,
            ContentValues().apply { put(Events.SELF_ATTENDEE_STATUS, status) },
            "${Events._ID} = ?",
            arrayOf(eventId.toString()),
        )
    }

    private fun ownerOf(calendarId: Long): String? =
        db.query(CALENDARS, arrayOf(Calendars.OWNER_ACCOUNT), "${Calendars._ID} = ?", arrayOf(calendarId.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    // ---- the app's write contract ----

    /**
     * Checks an app write's columns against the documented app-writable lists.
     *
     * With [checkAppWriteRow], this holds an app write to the documented
     * calendar contract (the CalendarContract reference) and to RFC 5545 for
     * the recurrence rule and duration it carries. This is stricter than the
     * platform, which accepts more, so the app stays inside what is promised
     * rather than what happens to work today. Only app writes through the
     * resolver are checked: sync adapter writes and the seeding helpers stand
     * in for data other apps wrote, which the app has to read however it
     * looks. The app writes as a sync adapter only for extended properties,
     * so the exemption hides none of its event, attendee or reminder writes.
     *
     * The column lists don't depend on a row, so they are checked once per
     * write, [values] being exactly what the caller sent.
     */
    private fun checkAppWriteColumns(table: String, values: ContentValues) {
        when (table) {
            EVENTS -> onlyColumns(values, EVENTS_APP_COLUMNS, "CalendarContract.Events: Writing to Events")
            ATTENDEES -> onlyColumns(values, ATTENDEES_APP_WRITABLE, "CalendarContract.Attendees")
            REMINDERS -> onlyColumns(values, REMINDERS_APP_WRITABLE, "CalendarContract.Reminders")
            CALENDARS -> onlyColumns(values, CALENDARS_APP_WRITABLE, "CalendarContract.Calendars: Calendar Columns")
        }
    }

    /**
     * The contract rules that depend on the row: [stored] is the row an update
     * changes, or null for an insert.
     */
    private fun checkAppWriteRow(table: String, values: ContentValues, stored: ContentValues?, isInsert: Boolean) {
        when (table) {
            EVENTS -> checkEventRow(values, stored)
            ATTENDEES -> checkAttendeeRow(values, stored, isInsert)
            REMINDERS -> if (isInsert) {
                requireColumns(values, REMINDERS_APP_WRITABLE, "CalendarContract.Reminders: every field must be included when inserting a reminder")
            }
        }
    }

    private fun onlyColumns(values: ContentValues, allowed: Collection<String>, source: String) {
        values.keySet().firstOrNull { it !in allowed }?.let { violation("$source: an app may not write $it") }
    }

    private fun requireColumns(values: ContentValues, required: Collection<String>, source: String) {
        required.firstOrNull { values.get(it) == null }?.let { violation("$source (missing $it)") }
    }

    private fun merged(stored: ContentValues?, values: ContentValues): ContentValues =
        if (stored == null) values else ContentValues(stored).apply { putAll(values) }

    private fun isAllDay(row: ContentValues) = (row.getAsInteger(Events.ALL_DAY) ?: 0) != 0

    private fun checkEventRow(values: ContentValues, stored: ContentValues?) {
        val row = merged(stored, values)
        val allDay = isAllDay(row)
        for ((column, section) in listOf(Events.RRULE to "3.3.10", Events.DURATION to "3.3.6")) {
            val value = values.getAsString(column)
            if (value.isNullOrEmpty()) continue
            // A value written back unchanged is another app's data being carried, not the app's
            // own, unless the write changes whether the event is all-day, which a rule depends on.
            val carried = stored != null && stored.getAsString(column) == value &&
                (column != Events.RRULE || isAllDay(stored) == allDay)
            if (carried) continue
            val problem = try {
                if (column == Events.RRULE) rruleProblem(value, allDay) else durationProblem(value)
            } catch (e: Exception) {
                "could not be read (${e.javaClass.simpleName})"
            }
            problem?.let { violation("RFC 5545 section $section: $column '$value' $it") }
        }
        // An update of a row another app stored off the contract is checked only for its writes.
        if (stored != null && eventShapeProblem(stored) != null) return
        eventShapeProblem(row)?.let { violation("CalendarContract.Events: $it") }
    }

    /**
     * The platform rebuilds a series' occurrences from the values in the
     * update itself, not from the merged row: an update without DTSTART
     * leaves the occurrences as they were, and one with DTSTART but no RRULE
     * is expanded as a one-off. So an app update that changes a series' rule,
     * exclusions or times must carry both, or the phone keeps showing the old
     * occurrences. This view is recomputed on every query and can't show
     * that, so the write is refused instead. Simplified: the platform reads
     * the update's RRULE or RDATE value, this checks for the RRULE key, which
     * matches every series update the app writes (it writes RDATE only as
     * null, on exceptions).
     */
    private fun checkSeriesReexpands(values: ContentValues, stored: ContentValues) {
        val isSeries = !stored.getAsString(Events.RRULE).isNullOrEmpty() || !stored.getAsString(Events.RDATE).isNullOrEmpty()
        val isException = stored.get(Events.ORIGINAL_ID) != null || !stored.getAsString(Events.ORIGINAL_SYNC_ID).isNullOrEmpty()
        if (!isSeries || isException) return
        if (values.keySet().none { it in SERIES_SHAPE_COLUMNS }) return
        if (values.containsKey(Events.DTSTART) && values.containsKey(Events.RRULE)) return
        violation("$STALE_SERIES_UPDATE (wrote ${values.keySet().filter { it in SERIES_SHAPE_COLUMNS }.sorted()})")
    }

    private fun checkAttendeeRow(values: ContentValues, stored: ContentValues?, isInsert: Boolean) {
        val source = "CalendarContract.Attendees"
        if (isInsert) {
            requireColumns(values, ATTENDEES_REQUIRED_ON_INSERT, "$source: every field but the name must be included when inserting an attendee")
        }
        // On an update the pair is the app's to keep only when it writes one half of it.
        val writesPair = isInsert || values.containsKey(Attendees.ATTENDEE_IDENTITY) || values.containsKey(Attendees.ATTENDEE_ID_NAMESPACE)
        val row = merged(stored, values)
        if (writesPair && (row.get(Attendees.ATTENDEE_IDENTITY) != null) != (row.get(Attendees.ATTENDEE_ID_NAMESPACE) != null)) {
            violation("$source: attendeeIdentity and attendeeIdNamespace are each required when the other is present")
        }
    }

    /**
     * Why [row] breaks the documented Events rules, or null. The presence checks
     * repeat the platform's own, which normally fire first, so the contract
     * stands on its own.
     */
    private fun eventShapeProblem(row: ContentValues): String? {
        if (row.getAsString(Events.CALENDAR_ID).isNullOrEmpty()) return "an event needs a calendar_id"
        val dtstart = row.getAsLong(Events.DTSTART) ?: return "an event needs a dtstart"
        val timezone = row.getAsString(Events.EVENT_TIMEZONE)
        if (timezone.isNullOrEmpty()) return "an event needs an eventTimezone"
        val recurring = !row.getAsString(Events.RRULE).isNullOrEmpty() || !row.getAsString(Events.RDATE).isNullOrEmpty()
        val dtend = row.getAsLong(Events.DTEND)
        val hasDuration = !row.getAsString(Events.DURATION).isNullOrEmpty()
        if (recurring) {
            if (!hasDuration) return "a recurring event needs a duration"
            if (dtend != null) return "a recurring event takes a duration, not a dtend"
            if (row.get(Events.ORIGINAL_ID) != null || !row.getAsString(Events.ORIGINAL_SYNC_ID).isNullOrEmpty()) {
                return "exceptions are not allowed to recur: a row with an rrule or rdate must have empty original_id and original_sync_id"
            }
        } else {
            if (dtend == null) return "a non-recurring event needs a dtend"
            if (hasDuration) return "a non-recurring event takes a dtend, not a duration"
        }
        if ((row.getAsInteger(Events.ALL_DAY) ?: 0) != 0) {
            if (timezone != "UTC") return "an all-day event's eventTimezone must be UTC"
            if (Math.floorMod(dtstart, DAY_MS) != 0L || (dtend != null && Math.floorMod(dtend, DAY_MS) != 0L)) {
                return "an all-day event's times must fall on midnight boundaries"
            }
        }
        return null
    }

    // ---- Instances view ----

    private class Occurrence(val row: Map<String, Any?>, val begin: Long, val end: Long)

    private fun queryInstances(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val segments = uri.pathSegments
        val isSearch = segments.getOrNull(1) == "search"
        val begin = segments[2].toLong()
        val end = segments[3].toLong()
        val query = if (isSearch) segments.getOrNull(4).orEmpty() else null

        val tokens = query?.let(::tokenizeSearchQuery)
        val guestsByEvent = mutableMapOf<Long, List<Pair<String?, String?>>>()
        val occurrences = expandWindow(begin, end)
            .filter { it.begin <= end && it.end >= begin }
            .filter { tokens == null || matchesSearch(it.row, tokens, guestsByEvent) }

        synchronized(this) {
            db.execSQL("DROP TABLE IF EXISTS $INSTANCES_VIEW")
            db.execSQL("CREATE TEMP TABLE $INSTANCES_VIEW (${INSTANCE_COLUMNS.joinToString(", ") { instanceColumnDef(it) }})")
            try {
                occurrences.forEachIndexed { i, o ->
                    val cv = ContentValues()
                    cv.put(Instances._ID, i + 1L)
                    cv.put(Instances.EVENT_ID, o.row[Events._ID] as Long)
                    cv.put(Instances.BEGIN, o.begin)
                    cv.put(Instances.END, o.end)
                    for (col in INSTANCE_COLUMNS) {
                        if (col in cv.keySet()) continue
                        when (val value = o.row[col]) {
                            null -> cv.putNull(col)
                            is Long -> cv.put(col, value)
                            is String -> cv.put(col, value)
                            else -> cv.put(col, value.toString())
                        }
                    }
                    db.insertOrThrow(INSTANCES_VIEW, null, cv)
                }
                db.query(INSTANCES_VIEW, projection, selection, selectionArgs?.map { it }?.toTypedArray(), null, null, sortOrder)
                    .use { c ->
                        val copy = MatrixCursor(c.columnNames)
                        while (c.moveToNext()) {
                            copy.addRow(Array<Any?>(c.columnCount) { i -> c.valueAt(i) })
                        }
                        return copy
                    }
            } finally {
                db.execSQL("DROP TABLE IF EXISTS $INSTANCES_VIEW")
            }
        }
    }

    /** Every occurrence of every expandable row, overrides applied, before the window filter. */
    private fun expandWindow(begin: Long, end: Long): List<Occurrence> {
        val rows = joinedEventRows()
        val byKey = mutableMapOf<String, MutableList<Occurrence>>()
        val overrides = mutableListOf<Pair<String, Long>>()
        val plain = mutableListOf<Occurrence>()

        for (row in rows) {
            val deleted = (row[Events.DELETED] as Long? ?: 0L) != 0L
            val cancelled = (row[Events.STATUS] as Long?)?.toInt() == Events.STATUS_CANCELED
            val allDay = (row[Events.ALL_DAY] as Long? ?: 0L) != 0L
            val dtstart = row[Events.DTSTART] as Long? ?: continue
            val calendarId = row[Events.CALENDAR_ID] as Long
            val rrule = row[Events.RRULE] as String?
            if (!rrule.isNullOrEmpty()) {
                if (deleted || cancelled) continue
                val duration = recurringDurationMs(row, allDay, dtstart)
                val tz = (row[Events.EVENT_TIMEZONE] as String?).takeUnless { allDay || it.isNullOrEmpty() } ?: "UTC"
                // The platform compares a UTC UNTIL as an instant (RFC 5545
                // section 3.3.10) for every row. The app's engine does too for
                // timed series but reads it on the device clock for all-day ones,
                // so the rule goes to it without UNTIL and the bound is applied here.
                val utcUntil = UTC_UNTIL.find(rrule)?.groupValues?.get(1)?.let(::parseUtcUntil)
                val ruleForEngine = if (utcUntil != null) rrule.replace(UTC_UNTIL_PART, "").trim(';') else rrule
                val starts = IcalDavRRuleEngine.expandToTimestamps(
                    rrule = ruleForEngine,
                    dtstartMs = dtstart,
                    rangeStartMs = begin - maxOf(duration, 0L),
                    rangeEndMs = end + 1,
                    timezone = tz,
                    isAllDay = allDay,
                    rdateStrings = null,
                    exdateStrings = null,
                ).filter { utcUntil == null || it <= utcUntil }
                val key = "$calendarId:${row[Events._SYNC_ID]}"
                byKey.getOrPut(key) { mutableListOf() } += starts.map { Occurrence(row, it, it + duration) }
            } else {
                val originalSyncId = row[Events.ORIGINAL_SYNC_ID] as String?
                val originalTime = row[Events.ORIGINAL_INSTANCE_TIME] as Long?
                val stop = oneOffEnd(row, dtstart)
                if (originalSyncId != null && originalTime != null) {
                    overrides += "$calendarId:$originalSyncId" to originalTime
                    if (!deleted && !cancelled) plain += Occurrence(row, dtstart, stop)
                } else if (!deleted) {
                    plain += Occurrence(row, dtstart, stop)
                }
            }
        }
        for ((key, time) in overrides) byKey[key]?.removeAll { it.begin == time }
        return byKey.values.flatten() + plain
    }

    /** Events rows joined with their Calendars row, for calendars that sync events. */
    private fun joinedEventRows(): List<Map<String, Any?>> {
        val sql = """
            SELECT e.*, c.${Calendars.CALENDAR_DISPLAY_NAME} AS ${Instances.CALENDAR_DISPLAY_NAME},
                   c.${Calendars.CALENDAR_COLOR} AS cal_color,
                   c.${Calendars.CALENDAR_ACCESS_LEVEL} AS ${Instances.CALENDAR_ACCESS_LEVEL},
                   c.${Calendars.VISIBLE} AS ${Calendars.VISIBLE},
                   (SELECT COUNT(*) FROM $REMINDERS r WHERE r.${Reminders.EVENT_ID} = e.${Events._ID}) AS reminder_count
            FROM $EVENTS e JOIN $CALENDARS c ON c.${Calendars._ID} = e.${Events.CALENDAR_ID}
            WHERE c.${Calendars.SYNC_EVENTS} != 0
        """.trimIndent()
        return db.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val row = mutableMapOf<String, Any?>()
                    for (i in 0 until c.columnCount) row[c.getColumnName(i)] = c.valueAt(i)
                    // The view's calendar colour is the calendar's, not a copy on the event.
                    row[Instances.CALENDAR_COLOR] = row.remove("cal_color")
                    row[Instances.HAS_ALARM] = if ((row.remove("reminder_count") as Long) > 0) 1L else 0L
                    if (row[Events.SELF_ATTENDEE_STATUS] == null) row[Events.SELF_ATTENDEE_STATUS] = Attendees.ATTENDEE_STATUS_NONE.toLong()
                    add(row)
                }
            }
        }
    }

    private fun recurringDurationMs(row: Map<String, Any?>, allDay: Boolean, dtstart: Long): Long {
        val duration = row[Events.DURATION] as String?
        if (duration != null) return parsePlatformDuration(duration) ?: 0L
        if (allDay) return DAY_MS
        val dtend = row[Events.DTEND] as Long?
        return if (dtend != null) dtend - dtstart else 0L
    }

    private fun oneOffEnd(row: Map<String, Any?>, dtstart: Long): Long {
        val duration = row[Events.DURATION] as String?
        if (duration != null) return dtstart + (parsePlatformDuration(duration) ?: 0L)
        return row[Events.DTEND] as Long? ?: dtstart
    }

    private fun matchesSearch(
        row: Map<String, Any?>,
        tokens: List<String>,
        guestsByEvent: MutableMap<Long, List<Pair<String?, String?>>>,
    ): Boolean {
        if (tokens.isEmpty()) return true
        val eventId = row[Events._ID] as Long
        val guests = guestsByEvent.getOrPut(eventId) {
            db.query(
                ATTENDEES, arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME),
                "${Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null, null, null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        }
        val columns = listOf(
            row[Events.TITLE] as String?,
            row[Events.DESCRIPTION] as String?,
            row[Events.EVENT_LOCATION] as String?,
            guests.mapNotNull { it.first }.joinToString(",").ifEmpty { null },
            guests.mapNotNull { it.second }.joinToString(",").ifEmpty { null },
        )
        return tokens.all { token -> columns.any { it != null && asciiLower(it).contains(asciiLower(token)) } }
    }

    // ---- test helpers: seed and read rows directly ----

    /**
     * Inserts a row directly, as it would be stored, skipping the write rules
     * (refusals, validation, rewrites). An attendee row still updates its
     * event's SELF_ATTENDEE_STATUS, as the platform does for every attendee
     * write.
     */
    fun insertRow(table: String, vararg pairs: Pair<String, Any?>): Long {
        val v = valuesOf(pairs.toList())
        checkColumns(table, v)
        return db.insertOrThrow(table, null, v).also { if (table == ATTENDEES) updateSelfAttendeeStatus(v) }
    }

    /**
     * Sets columns on an existing row directly, skipping the write rules; the
     * platform's trigger (the sync-id carry-over) still runs.
     */
    fun updateRow(table: String, id: Long, vararg pairs: Pair<String, Any?>) {
        val v = valuesOf(pairs.toList())
        checkColumns(table, v)
        db.update(table, v, "${BaseColumns._ID} = ?", arrayOf(id.toString()))
    }

    /** Rows of [table] matching [selection] in `_id` order, each as column name to string value. */
    fun rows(table: String, selection: String? = null, vararg args: String): List<Map<String, String?>> =
        db.query(table, null, selection, args.takeIf { it.isNotEmpty() }?.let { arrayOf(*it) }, null, null, BaseColumns._ID).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.columnNames.associateWith { name -> c.getString(c.getColumnIndexOrThrow(name)) })
                }
            }
        }

    /** One row of [table] by id, or null. */
    fun row(table: String, id: Long): Map<String, String?>? = rows(table, "${BaseColumns._ID} = ?", id.toString()).singleOrNull()

    /** An event's reminder rows as (minutes, method), sorted by minutes. */
    fun reminderRows(eventId: Long): List<Pair<Int, Int>> =
        rows(REMINDERS, "${Reminders.EVENT_ID} = ?", eventId.toString())
            .map { it.getValue(Reminders.MINUTES)!!.toInt() to it.getValue(Reminders.METHOD)!!.toInt() }
            .sortedBy { it.first }

    /**
     * Inserts a Calendars row owned by [accountName], the account its tag
     * writes go under; by default it syncs events and is visible.
     */
    fun seedCalendar(
        id: Long,
        accountName: String = "me@example.test",
        accountType: String = "org.example.sync",
        displayName: String = "Calendar $id",
        color: Int = 0xFF3366CC.toInt(),
        syncEvents: Boolean = true,
        visible: Boolean = true,
        accessLevel: Int = Calendars.CAL_ACCESS_OWNER,
    ) {
        insertRow(
            CALENDARS,
            Calendars._ID to id,
            Calendars.ACCOUNT_NAME to accountName,
            Calendars.ACCOUNT_TYPE to accountType,
            Calendars.OWNER_ACCOUNT to accountName,
            Calendars.CALENDAR_DISPLAY_NAME to displayName,
            Calendars.CALENDAR_COLOR to color,
            Calendars.SYNC_EVENTS to if (syncEvents) 1 else 0,
            Calendars.VISIBLE to if (visible) 1 else 0,
            Calendars.CALENDAR_ACCESS_LEVEL to accessLevel,
            Calendars.MAX_REMINDERS to 5,
        )
    }

    private fun isSyncAdapter(uri: Uri) = uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"

    private enum class Write { INSERT, UPDATE, DELETE }

    /**
     * The checks the platform runs on every write before touching a table, in
     * its order: selection rules, the sync-adapter-only table, columns only the
     * provider may write, then a sync adapter's account or, for an app, columns
     * only a sync adapter may write. Presence of a key is what counts, even with
     * a null value.
     */
    private fun verifyWriteAllowed(
        write: Write,
        uri: Uri,
        table: String,
        id: Long?,
        values: ContentValues?,
        selection: String?,
        syncAdapter: Boolean,
    ) {
        if (write != Write.INSERT) {
            // A blank-but-not-empty selection counts as a selection, as it does on the platform.
            if (!selection.isNullOrEmpty()) {
                require(id == null) { "Selection not permitted for $uri" }
            } else {
                require(id != null || table !in SELECTION_REQUIRED) { "Selection must be specified for $uri" }
            }
        }
        if (table == EXTENDED_PROPERTIES) require(syncAdapter) { "Only sync adapters may write using $uri" }
        if (values != null && table == EVENTS) {
            EVENTS_PROVIDER_ONLY.firstOrNull(values::containsKey)?.let {
                throw IllegalArgumentException("Only the provider may write to $it")
            }
        }
        // The platform wants every sync adapter write to name its account; only extended
        // properties are modelled, the one table the app writes as a sync adapter.
        if (syncAdapter && table == EXTENDED_PROPERTIES) {
            require(
                !uri.getQueryParameter(Calendars.ACCOUNT_NAME).isNullOrEmpty() &&
                    !uri.getQueryParameter(Calendars.ACCOUNT_TYPE).isNullOrEmpty()
            ) { "Sync adapters must specify an account and account type: $uri" }
        }
        if (values != null && !syncAdapter) {
            val syncOnly = when (table) {
                EVENTS -> EVENTS_SYNC_ONLY
                CALENDARS -> CALENDARS_SYNC_ONLY
                else -> DEFAULT_SYNC_ONLY
            }
            syncOnly.firstOrNull(values::containsKey)?.let {
                throw IllegalArgumentException("Only sync adapters may write to $it")
            }
        }
    }

    /**
     * The event an attendee, reminder or extended property insert names, or null
     * for other tables. Throws the platform's refusal when there is none; an
     * attendee's event id that isn't a number fails as the platform's unboxing does.
     */
    private fun requiredEventId(table: String, v: ContentValues): Long? = when (table) {
        ATTENDEES -> {
            require(v.containsKey(Attendees.EVENT_ID)) { "Attendees values must contain an event_id" }
            v.getAsLong(Attendees.EVENT_ID) ?: throw NullPointerException("Attendees event_id is not a number")
        }
        REMINDERS -> v.getAsLong(Reminders.EVENT_ID)
            ?: throw IllegalArgumentException("Reminders values must contain a numeric event_id")
        EXTENDED_PROPERTIES -> v.getAsLong(ExtendedProperties.EVENT_ID)
            ?: throw IllegalArgumentException("ExtendedProperties values must contain a numeric event_id")
        else -> null
    }

    /**
     * Whether an Events row has this id; a deleted but not yet purged event
     * counts, as on the platform.
     */
    private fun eventExists(eventId: Long): Boolean =
        idsOf(EVENTS, "${Events._ID} = ?", arrayOf(eventId.toString())).isNotEmpty()

    private fun idsOf(table: String, sel: String?, args: Array<String>?): List<Long> =
        db.query(table, arrayOf(BaseColumns._ID), sel, args, null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun rowValues(table: String, id: Long): ContentValues? =
        db.query(table, null, "${BaseColumns._ID} = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (!c.moveToFirst()) return@use null
            valuesOf((0 until c.columnCount).map { i -> c.getColumnName(i) to c.valueAt(i) })
        }

    private fun resolve(uri: Uri): Pair<String, Long?> {
        val segments = uri.pathSegments
        val table = PATHS[segments.firstOrNull()] ?: error("unsupported uri $uri")
        val id = segments.getOrNull(1)?.toLongOrNull()
        return table to id
    }

    private fun withId(id: Long?, selection: String?, args: Array<out String>?): Pair<String?, Array<String>?> {
        val baseArgs = args?.map { it }?.toTypedArray()
        if (id == null) return selection to baseArgs
        val sel = if (selection.isNullOrBlank()) "${BaseColumns._ID} = ?" else "${BaseColumns._ID} = ? AND ($selection)"
        return sel to (arrayOf(id.toString()) + (baseArgs ?: emptyArray()))
    }

    private fun checkColumns(table: String, values: ContentValues) {
        val known = TABLES.getValue(table)
        val unknown = values.keySet().filterNot { it in known }
        require(unknown.isEmpty()) { "$table has no column(s) $unknown" }
    }

    companion object {
        const val CALENDARS = "Calendars"
        const val EVENTS = "Events"
        const val REMINDERS = "Reminders"
        const val ATTENDEES = "Attendees"
        const val EXTENDED_PROPERTIES = "ExtendedProperties"
        /** The [queryFailures] key for the Instances view and its search. */
        const val INSTANCES = "Instances"

        private const val INSTANCES_VIEW = "instances_view"
        private const val DAY_MS = 86_400_000L

        private val PATHS = mapOf(
            "calendars" to CALENDARS,
            "events" to EVENTS,
            "reminders" to REMINDERS,
            "attendees" to ATTENDEES,
            "extendedproperties" to EXTENDED_PROPERTIES,
        )

        /** Tables whose bulk update or delete must carry a selection. */
        private val SELECTION_REQUIRED = setOf(EVENTS, ATTENDEES, REMINDERS)

        // The platform's own lists are hidden from apps, so they are spelled out
        // here from the public constants, in the platform's order.

        /** Calendar columns the Events URI shows but only the provider writes, for every caller. */
        private val EVENTS_PROVIDER_ONLY = listOf(
            Events.ACCOUNT_NAME, Events.ACCOUNT_TYPE,
            Events.CAL_SYNC1, Events.CAL_SYNC2, Events.CAL_SYNC3, Events.CAL_SYNC4, Events.CAL_SYNC5,
            Events.CAL_SYNC6, Events.CAL_SYNC7, Events.CAL_SYNC8, Events.CAL_SYNC9, Events.CAL_SYNC10,
            Events.ALLOWED_REMINDERS, Events.ALLOWED_ATTENDEE_TYPES, Events.ALLOWED_AVAILABILITY,
            Events.CALENDAR_ACCESS_LEVEL, Events.CALENDAR_COLOR, Events.CALENDAR_TIME_ZONE,
            Events.CAN_MODIFY_TIME_ZONE, Events.CAN_ORGANIZER_RESPOND, Events.CALENDAR_DISPLAY_NAME,
            Events.CAN_PARTIALLY_UPDATE, Events.SYNC_EVENTS, Events.VISIBLE,
        )

        /** Events columns only a sync adapter may write. */
        private val EVENTS_SYNC_ONLY = listOf(
            Events._SYNC_ID, Events.DIRTY, Events.MUTATORS,
            Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10,
        )

        /** Calendars columns only a sync adapter may write. */
        private val CALENDARS_SYNC_ONLY = listOf(
            Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars._SYNC_ID, Calendars.DIRTY, Calendars.MUTATORS,
            Calendars.OWNER_ACCOUNT, Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS,
            Calendars.CAN_MODIFY_TIME_ZONE, Calendars.CAN_ORGANIZER_RESPOND, Calendars.CAN_PARTIALLY_UPDATE,
            Calendars.CALENDAR_LOCATION, Calendars.CALENDAR_TIME_ZONE, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.DELETED,
            Calendars.CAL_SYNC1, Calendars.CAL_SYNC2, Calendars.CAL_SYNC3, Calendars.CAL_SYNC4, Calendars.CAL_SYNC5,
            Calendars.CAL_SYNC6, Calendars.CAL_SYNC7, Calendars.CAL_SYNC8, Calendars.CAL_SYNC9, Calendars.CAL_SYNC10,
        )

        /**
         * Columns only a sync adapter may write on attendees, reminders and extended
         * properties.
         */
        private val DEFAULT_SYNC_ONLY = listOf(Calendars.DIRTY, Calendars._SYNC_ID)

        // The documented app-writable columns (CalendarContract reference), which the
        // contract check holds app writes to.

        private val EVENTS_APP_WRITABLE = setOf(
            Events.CALENDAR_ID, Events.ORGANIZER, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION,
            Events.EVENT_COLOR, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.DURATION, Events.ALL_DAY, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.ACCESS_LEVEL, Events.AVAILABILITY, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
            Events.GUESTS_CAN_SEE_GUESTS, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.UID_2445,
        )

        /**
         * Events columns the app writes although the documented list leaves them
         * out. The platform accepts both. STATUS marks a new exception row
         * confirmed, or cancelled to delete a single occurrence;
         * HAS_ATTENDEE_DATA is set on an event saved with guests and copied from
         * the series onto a row cut from it. Whether to cancel through the
         * series' EXDATE instead, and whether to stop writing HAS_ATTENDEE_DATA,
         * is an open follow-up.
         */
        private val EVENTS_APP_ALLOWANCES = setOf(Events.STATUS, Events.HAS_ATTENDEE_DATA)

        private val EVENTS_APP_COLUMNS = EVENTS_APP_WRITABLE + EVENTS_APP_ALLOWANCES

        private val ATTENDEES_APP_WRITABLE = setOf(
            Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_RELATIONSHIP,
            Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_IDENTITY, Attendees.ATTENDEE_ID_NAMESPACE,
        )

        private val ATTENDEES_REQUIRED_ON_INSERT =
            ATTENDEES_APP_WRITABLE - setOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_IDENTITY, Attendees.ATTENDEE_ID_NAMESPACE)

        private val REMINDERS_APP_WRITABLE = setOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD)

        private val CALENDARS_APP_WRITABLE = setOf(Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.VISIBLE, Calendars.SYNC_EVENTS)

        private fun violation(message: String): Nothing = throw CalendarContractViolation(message)

        /** The message of a series update the platform would not re-expand. */
        const val STALE_SERIES_UPDATE =
            "an update that changes a series' rule or times must carry its DTSTART and RRULE, or the platform keeps showing its old occurrences"

        private val SERIES_SHAPE_COLUMNS = setOf(
            Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DTSTART, Events.DTEND,
            Events.DURATION, Events.EVENT_TIMEZONE, Events.ALL_DAY,
        )

        // RFC 5545 section 3.3.10 RECUR. Names and values are matched after
        // upper-casing, since the RFC makes them case-insensitive.

        private val RRULE_PARTS = listOf(
            "FREQ", "UNTIL", "COUNT", "INTERVAL", "BYSECOND", "BYMINUTE", "BYHOUR", "BYDAY",
            "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYMONTH", "BYSETPOS", "WKST",
        )
        private val FREQS = setOf("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
        private val WEEKDAYS = setOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
        private val WEEKDAY_NUM = Regex("([+-]?)(\\d{1,2})?([A-Z]{2})")
        private val FREQS_WITHOUT_BYYEARDAY = setOf("DAILY", "WEEKLY", "MONTHLY")
        private val TIME_OF_DAY_PARTS = setOf("BYSECOND", "BYMINUTE", "BYHOUR")
        // DIGIT in RFC 5545 is ASCII 0-9 (and so is \d in a Java regex).
        private val ASCII_NUMBER = Regex("[0-9]+")
        private val NUMBER_ITEM = Regex("([+-]?)([0-9]+)")
        private val UNTIL_DATE = Regex("\\d{8}")
        private val UNTIL_UTC = Regex("(\\d{8})T(\\d{2})(\\d{2})(\\d{2})Z")

        /** Why [rrule] is not an RFC 5545 RECUR value for an all-day (or timed) row, or null. */
        private fun rruleProblem(rrule: String, allDay: Boolean): String? {
            val text = rrule.uppercase(java.util.Locale.ROOT)
            if (text.startsWith("RRULE:")) return "must not carry the RRULE: property name"
            val parts = linkedMapOf<String, String>()
            for (part in text.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0 || eq == part.length - 1) return "has a malformed rule part '$part'"
                val name = part.substring(0, eq)
                if (name !in RRULE_PARTS) return "has a rule part RFC 5545 does not define: $name"
                if (parts.put(name, part.substring(eq + 1)) != null) return "repeats the $name rule part"
            }
            val freq = parts["FREQ"] ?: return "has no FREQ"
            if (parts.keys.first() != "FREQ") return "must name FREQ first"
            if (freq !in FREQS) return "has an unknown FREQ $freq"
            if ("UNTIL" in parts && "COUNT" in parts) return "has both UNTIL and COUNT"
            parts["COUNT"]?.let {
                if (positive(it) == null) return "has a COUNT that is not a positive number (DTSTART always counts as the first occurrence)"
            }
            parts["INTERVAL"]?.let { if (positive(it) == null) return "has an INTERVAL that is not a positive number" }
            numberListProblem(parts, "BYSECOND", 0, 60, signed = false, digits = 2)?.let { return it }
            numberListProblem(parts, "BYMINUTE", 0, 59, signed = false, digits = 2)?.let { return it }
            numberListProblem(parts, "BYHOUR", 0, 23, signed = false, digits = 2)?.let { return it }
            numberListProblem(parts, "BYMONTHDAY", 1, 31, signed = true, digits = 2)?.let { return it }
            numberListProblem(parts, "BYYEARDAY", 1, 366, signed = true, digits = 3)?.let { return it }
            numberListProblem(parts, "BYWEEKNO", 1, 53, signed = true, digits = 2)?.let { return it }
            numberListProblem(parts, "BYMONTH", 1, 12, signed = false, digits = 2)?.let { return it }
            numberListProblem(parts, "BYSETPOS", 1, 366, signed = true, digits = 3)?.let { return it }
            parts["BYDAY"]?.split(',')?.forEach { day ->
                val m = WEEKDAY_NUM.matchEntire(day) ?: return "has a malformed BYDAY value '$day'"
                val (sign, number, weekday) = m.destructured
                if (weekday !in WEEKDAYS) return "has an unknown weekday '$day'"
                if (sign.isNotEmpty() && number.isEmpty()) return "has a signed BYDAY value without a number '$day'"
                if (number.isNotEmpty()) {
                    if (number.toInt() !in 1..53) return "has a BYDAY ordinal outside 1 to 53 '$day'"
                    if (freq != "MONTHLY" && freq != "YEARLY") return "uses a numbered BYDAY outside a MONTHLY or YEARLY rule"
                    if (freq == "YEARLY" && "BYWEEKNO" in parts) return "uses a numbered BYDAY with BYWEEKNO"
                }
            }
            parts["WKST"]?.let { if (it !in WEEKDAYS) return "has an unknown WKST $it" }
            if ("BYMONTHDAY" in parts && freq == "WEEKLY") return "uses BYMONTHDAY in a WEEKLY rule"
            if ("BYYEARDAY" in parts && freq in FREQS_WITHOUT_BYYEARDAY) return "uses BYYEARDAY in a $freq rule"
            if ("BYWEEKNO" in parts && freq != "YEARLY") return "uses BYWEEKNO outside a YEARLY rule"
            if ("BYSETPOS" in parts && parts.keys.none { it.startsWith("BY") && it != "BYSETPOS" }) {
                return "uses BYSETPOS without another BYxxx rule part"
            }
            if (allDay && parts.keys.any { it in TIME_OF_DAY_PARTS }) {
                return "uses BYSECOND, BYMINUTE or BYHOUR on an all-day (DATE) event"
            }
            parts["UNTIL"]?.let { until -> untilProblem(until, allDay)?.let { return it } }
            return null
        }

        private fun positive(value: String): Int? =
            if (ASCII_NUMBER.matches(value)) value.toIntOrNull()?.takeIf { it >= 1 } else null

        private fun numberListProblem(parts: Map<String, String>, name: String, min: Int, max: Int, signed: Boolean, digits: Int): String? {
            val list = parts[name] ?: return null
            for (value in list.split(',')) {
                val m = NUMBER_ITEM.matchEntire(value)
                if (m == null || (!signed && m.groupValues[1].isNotEmpty()) || m.groupValues[2].length > digits) {
                    return "has a malformed $name value '$value'"
                }
                if (m.groupValues[2].toInt() !in min..max) return "has a $name value outside $min to $max: $value"
            }
            return null
        }

        /**
         * UNTIL takes DTSTART's value type: a DATE on an all-day row, a UTC
         * DATE-TIME on a timed one.
         */
        private fun untilProblem(until: String, allDay: Boolean): String? {
            if (allDay) {
                if (!UNTIL_DATE.matches(until)) return "needs an UNTIL date (yyyymmdd) on an all-day event"
                return if (isDate(until)) null else "has an UNTIL that is not a real date"
            }
            val m = UNTIL_UTC.matchEntire(until) ?: return "needs an UNTIL in UTC (yyyymmddThhmmssZ) on a timed event"
            val (date, h, min, s) = m.destructured
            if (!isDate(date) || h.toInt() > 23 || min.toInt() > 59 || s.toInt() > 60) return "has an UNTIL that is not a real time"
            return null
        }

        private fun isDate(yyyymmdd: String): Boolean =
            try {
                java.time.LocalDate.parse(yyyymmdd, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                true
            } catch (e: java.time.format.DateTimeParseException) {
                false
            }

        // RFC 5545 section 3.3.6 dur-value: hours, minutes and seconds only in that
        // order and without gaps (an hour and a second need the minutes between).
        private const val DUR_TIME = "T(\\d+H(\\d+M(\\d+S)?)?|\\d+M(\\d+S)?|\\d+S)"
        private val RFC_DURATION = Regex("[+-]?P(\\d+W|\\d+D($DUR_TIME)?|$DUR_TIME)", RegexOption.IGNORE_CASE)

        /** Why [duration] is not an RFC 5545 dur-value, or null. */
        private fun durationProblem(duration: String): String? =
            if (RFC_DURATION.matches(duration)) null else "is not a dur-value"

        private val TEXT_COLUMNS = setOf(
            Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_DISPLAY_NAME,
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DURATION, Events.RRULE, Events.RDATE,
            Events.EXDATE, Events.EXRULE, Events.EVENT_TIMEZONE, Events.ORIGINAL_SYNC_ID, Events._SYNC_ID,
            Events.ORGANIZER,
            Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_IDENTITY,
            Attendees.ATTENDEE_ID_NAMESPACE, ExtendedProperties.NAME, ExtendedProperties.VALUE,
        )

        private val TABLES: Map<String, List<String>> = mapOf(
            CALENDARS to listOf(
                Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT,
                Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.VISIBLE,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.SYNC_EVENTS, Calendars.MAX_REMINDERS,
            ),
            EVENTS to listOf(
                Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
                Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.RRULE, Events.RDATE,
                Events.EXDATE, Events.EXRULE, Events.EVENT_TIMEZONE, Events.ORIGINAL_ID,
                Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_ALL_DAY, Events.STATUS,
                Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.CALENDAR_COLOR, Events.EVENT_COLOR,
                Events._SYNC_ID, Events.DELETED, Events.HAS_ATTENDEE_DATA, Events.ORGANIZER,
                Events.SELF_ATTENDEE_STATUS, Events.HAS_ALARM, Events.DIRTY,
            ),
            REMINDERS to listOf(Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
            ATTENDEES to listOf(
                Attendees._ID, Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL,
                Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS,
                Attendees.ATTENDEE_IDENTITY, Attendees.ATTENDEE_ID_NAMESPACE,
            ),
            EXTENDED_PROPERTIES to listOf(
                ExtendedProperties._ID, ExtendedProperties.EVENT_ID, ExtendedProperties.NAME, ExtendedProperties.VALUE,
            ),
        )

        /** Columns of the computed Instances view. */
        private val INSTANCE_COLUMNS = listOf(
            Instances._ID, Instances.EVENT_ID, Instances.BEGIN, Instances.END,
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY, Events.RRULE, Events.CALENDAR_ID,
            Instances.CALENDAR_DISPLAY_NAME, Events.STATUS, Events.AVAILABILITY, Instances.HAS_ALARM,
            Events.SELF_ATTENDEE_STATUS, Instances.CALENDAR_ACCESS_LEVEL, Events.ORIGINAL_ID,
            Events.ORIGINAL_INSTANCE_TIME, Events.EVENT_TIMEZONE, Instances.CALENDAR_COLOR, Events.EVENT_COLOR,
            Events.DTSTART, Calendars.VISIBLE,
        )

        private fun instanceColumnDef(col: String) = if (col in TEXT_COLUMNS) "$col TEXT" else "$col INTEGER"

        /** A cursor cell as the Kotlin value it holds (numbers as Long). */
        private fun Cursor.valueAt(i: Int): Any? = when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            else -> getString(i)
        }

        /** Column/value pairs as ContentValues (Boolean as 1 or 0). */
        fun valuesOf(pairs: List<Pair<String, Any?>>): ContentValues = ContentValues().apply {
            for ((k, value) in pairs) {
                when (value) {
                    null -> putNull(k)
                    is Int -> put(k, value)
                    is Long -> put(k, value)
                    is String -> put(k, value)
                    is Boolean -> put(k, if (value) 1 else 0)
                    else -> error("unsupported value type for $k")
                }
            }
        }

        /** Lower-case ASCII letters only, the way SQLite LIKE folds case. */
        private fun asciiLower(value: String): String =
            buildString(value.length) { value.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }

        private val SEARCH_TOKEN = Regex("[^\\s\"'.?!,]+|\"([^\"]*)\"")

        private val UTC_UNTIL = Regex("UNTIL=(\\d{8}T\\d{6}Z)", RegexOption.IGNORE_CASE)
        private val UTC_UNTIL_PART = Regex(";?UNTIL=\\d{8}T\\d{6}Z", RegexOption.IGNORE_CASE)

        private fun parseUtcUntil(value: String): Long =
            java.time.LocalDateTime.parse(value.uppercase(), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        /** The platform's search tokeniser: words, with double-quoted phrases kept whole. */
        fun tokenizeSearchQuery(query: String): List<String> =
            SEARCH_TOKEN.findAll(query).map { m -> m.groups[1]?.value ?: m.value }.toList()

        /**
         * The platform's DURATION parser: optional sign, `P`, then numbers
         * each followed by W, D, H, M or S (a `T` anywhere is ignored), so
         * seconds-only forms such as `P3600S` parse. An empty value is 0;
         * null when malformed.
         */
        fun parsePlatformDuration(value: String): Long? {
            if (value.isEmpty()) return 0L
            var i = 0
            var sign = 1L
            if (value[0] == '-') { sign = -1L; i++ } else if (value[0] == '+') i++
            if (i >= value.length || value[i] != 'P') return null
            i++
            var n = 0L
            var total = 0L
            while (i < value.length) {
                val c = value[i]
                when {
                    c.isDigit() -> n = n * 10 + (c - '0')
                    c == 'W' -> { total += n * 7 * DAY_MS; n = 0 }
                    c == 'D' -> { total += n * DAY_MS; n = 0 }
                    c == 'H' -> { total += n * 3_600_000L; n = 0 }
                    c == 'M' -> { total += n * 60_000L; n = 0 }
                    c == 'S' -> { total += n * 1_000L; n = 0 }
                    c == 'T' -> Unit
                    else -> return null
                }
                i++
            }
            return sign * total
        }

        /** Register a fresh provider for the calendar authority and return it. */
        fun install(): SqliteCalendarProvider =
            Robolectric.setupContentProvider(SqliteCalendarProvider::class.java, CalendarContract.AUTHORITY)

        /** The real repository over the registered provider. */
        fun repository(context: Context): AndroidCalendarProviderRepository =
            AndroidCalendarProviderRepository(context.contentResolver)
    }
}

/**
 * An app write that breaks the documented calendar contract or RFC 5545,
 * even though the platform might accept it. An `AssertionError`, not an
 * Exception, so the app's `catch (e: Exception)` handlers can't turn it into
 * a quiet failure result and hide it from the test.
 */
class CalendarContractViolation(message: String) : AssertionError(message)
