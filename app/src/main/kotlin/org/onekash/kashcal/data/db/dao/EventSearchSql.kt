package org.onekash.kashcal.data.db.dao

import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.json.Json
import org.onekash.kashcal.domain.search.SearchQuery

/**
 * Builds the app-calendar search SQL on the platform's calendar search rule, so the same words
 * find the same events in app and device calendars.
 *
 * An event matches when every word of the [SearchQuery] appears (`LIKE '%word%' ESCAPE '#'`,
 * case folded by SQLite) in its title, location or description, in an attendee's display name,
 * or in an attendee address that [SearchQuery.emailShapedAddress] accepts, with any `mailto:`
 * removed, and every tag of the query is one of its tags. The platform matches guests through
 * one comma-joined string of all names and one of all emails, so a quoted phrase that spans two
 * guests can match there and not here.
 *
 * A tag is matched against the JSON array in `events.categories` ([tagPatterns]), not with
 * JSON functions: the phone's SQLite on Android 12 has no `json_each`.
 *
 * Every word and tag is a bound argument; user text never enters the SQL. Each value is bound
 * once as a numbered parameter and reused by the columns it is matched against.
 */
object EventSearchSql {

    /** Most rows one search returns, per source. */
    const val LIMIT = 1000

    private const val NOT_PENDING_DELETE = "e.sync_status != 'PENDING_DELETE'"

    /**
     * A changed occurrence whose series is being deleted is not matched: deleting a synced series
     * marks only the master PENDING_DELETE and removes its rows, leaving the exception rows.
     */
    private const val SERIES_NOT_PENDING_DELETE =
        "NOT EXISTS (SELECT 1 FROM events m WHERE m.id = e.original_event_id AND m.sync_status = 'PENDING_DELETE')"

    private const val NOT_SYNTHETIC_MASTER =
        "(e.extra_properties IS NULL OR e.extra_properties NOT LIKE '%X-KASHCAL-SYNTHETIC-MASTER%')"

    /** An attendee address without a leading `mailto:` (any case). */
    private const val BARE_ADDRESS =
        "(CASE WHEN lower(substr(a.address, 1, 7)) = 'mailto:' THEN substr(a.address, 8) ELSE a.address END)"

    /** [SearchQuery.emailShapedAddress] in SQL: an `@` and no `:` or `/`. */
    private const val EMAIL_SHAPED =
        "instr($BARE_ADDRESS, '@') > 0 AND instr($BARE_ADDRESS, ':') = 0 AND instr($BARE_ADDRESS, '/') = 0"

    /** Collects bound values and hands out their numbered parameters. */
    private class Args {
        private val binders = mutableListOf<(SQLiteStatement, Int) -> Unit>()

        fun bind(value: String): String = add { statement, index -> statement.bindText(index, value) }
        fun bind(value: Long): String = add { statement, index -> statement.bindLong(index, value) }
        fun bind(value: Int): String = bind(value.toLong())

        /** Binds each value to its numbered parameter, in the order [bind] handed them out. */
        fun bindAll(statement: SQLiteStatement) {
            binders.forEachIndexed { i, binder -> binder(statement, i + 1) }
        }

        private fun add(binder: (SQLiteStatement, Int) -> Unit): String {
            binders += binder
            return "?${binders.size}"
        }
    }

    /**
     * Returns the two LIKE patterns that find the tag [name] as a whole element of the
     * `events.categories` JSON array: the element first (`[` before it) or later (`,` before
     * it). The name is encoded by the same default [Json] that stores the column
     * ([org.onekash.kashcal.data.db.converter.Converters.fromStringList]), so quotes and
     * backslashes are escaped as stored; inside a JSON string every quote is escaped, so `["`
     * and `,"` occur only where an element starts, and `#work` never matches `Teamwork` or a
     * tag like `x"work`. Case folds as LIKE folds it.
     */
    private fun tagPatterns(name: String): Pair<String, String> {
        val element = SearchQuery.escapeLike(Json.encodeToString(name))
        return "%[$element%" to "%,$element%"
    }

    /**
     * Returns the WHERE condition that matches every word and every tag of [query] for events
     * aliased `e`, binding the patterns through [args].
     */
    private fun matchCondition(query: SearchQuery, args: Args): String {
        require(!query.isEmpty) { "a search needs a word or a tag" }
        val escape = "ESCAPE '${SearchQuery.LIKE_ESCAPE}'"
        val words = query.likePatterns.map { pattern ->
            val p = args.bind(pattern)
            "(e.title LIKE $p $escape OR e.location LIKE $p $escape OR e.description LIKE $p $escape" +
                " OR EXISTS (SELECT 1 FROM attendees a WHERE a.event_id = e.id" +
                " AND (a.display_name LIKE $p $escape OR ($EMAIL_SHAPED AND $BARE_ADDRESS LIKE $p $escape))))"
        }
        val tags = query.tags.map { name ->
            val (first, later) = tagPatterns(name)
            "(e.categories LIKE ${args.bind(first)} $escape OR e.categories LIKE ${args.bind(later)} $escape)"
        }
        return (words + tags).joinToString(" AND ")
    }

    /**
     * The rows of `occurrences o` that date event `e`: a master's or one-off's own rows not
     * linked to a changed occurrence, or for a changed occurrence the master's row linked to
     * it. A row linked to an exception counts only for that exception, so it is never a
     * master's Next or Last, and an exception's own rows, if any, are not read.
     */
    private const val OWN_ROWS =
        "o.event_id = COALESCE(e.original_event_id, e.id)" +
            " AND ((e.original_event_id IS NULL AND o.exception_event_id IS NULL) OR o.exception_event_id = e.id)"

    /**
     * Builds the query for [EventsDao.searchMatches] and [EventsDao.observeSearchMatches]: every
     * event matching [query], not PENDING_DELETE, not a synthetic master and not a changed
     * occurrence of a series being deleted, with three occurrence ids. At most [LIMIT] rows:
     * those with a next row first, earliest start first, then the rest newest first, so the cap
     * drops the oldest past matches.
     *
     * - next: the earliest non-cancelled row not yet ended;
     * - last: the latest non-cancelled row that ended;
     * - in range: with [range], the earliest non-cancelled row overlapping it (inclusive).
     *
     * A row has ended as [org.onekash.kashcal.util.DateTimeUtils.isEventPast] says: a timed one
     * when its end is before [now], an all-day one when its end day is before [todayDayCode].
     * `has_occurrences` is whether any row, cancelled or linked, belongs to the event, so a
     * match with none can still be dated by its own start.
     */
    fun nextAndLast(query: SearchQuery, now: Long, todayDayCode: Int, range: LongRange? = null): RoomRawQuery {
        val args = Args()
        val match = matchCondition(query, args)
        val nowArg = args.bind(now)
        val todayArg = args.bind(todayDayCode)
        val notEnded = "(CASE WHEN e.is_all_day = 1 THEN o.end_day >= $todayArg ELSE o.end_ts >= $nowArg END)"
        val inRange = if (range == null) {
            "NULL"
        } else {
            val start = args.bind(range.first)
            val end = args.bind(range.last)
            "(SELECT o.id FROM occurrences o WHERE $OWN_ROWS AND o.is_cancelled = 0" +
                " AND o.start_ts <= $end AND o.end_ts >= $start ORDER BY o.start_ts ASC LIMIT 1)"
        }
        val sql = """
            SELECT e.*,
              (SELECT o.id FROM occurrences o WHERE $OWN_ROWS AND o.is_cancelled = 0 AND $notEnded
               ORDER BY o.start_ts ASC LIMIT 1) AS next_occurrence_id,
              (SELECT o.id FROM occurrences o WHERE $OWN_ROWS AND o.is_cancelled = 0 AND NOT $notEnded
               ORDER BY o.start_ts DESC LIMIT 1) AS last_occurrence_id,
              $inRange AS range_occurrence_id,
              (EXISTS (SELECT 1 FROM occurrences o WHERE o.event_id = e.id)
               OR EXISTS (SELECT 1 FROM occurrences o WHERE o.exception_event_id = e.id)) AS has_occurrences
            FROM events e
            WHERE $match AND $NOT_PENDING_DELETE AND $NOT_SYNTHETIC_MASTER AND $SERIES_NOT_PENDING_DELETE
            ORDER BY (next_occurrence_id IS NULL) ASC,
              CASE WHEN next_occurrence_id IS NOT NULL THEN e.start_ts ELSE -e.start_ts END ASC
            LIMIT $LIMIT
        """
        return RoomRawQuery(sql.trimIndent()) { statement -> args.bindAll(statement) }
    }
}
