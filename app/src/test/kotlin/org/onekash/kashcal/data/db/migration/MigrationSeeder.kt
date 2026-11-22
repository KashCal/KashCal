package org.onekash.kashcal.data.db.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Holds one seeded row as column -> SQL literal or expression; absent columns use defaults. */
private typealias Row = Map<String, String>

/**
 * Seeds a database at an old schema version with rows a real user's database of that version
 * could hold, so a migration test exercises the data paths, not empty tables.
 *
 * The statements come from the exported Room schema of that version (`app/schemas/...`): rows in
 * every table in foreign-key order, values the app's converters accept (enum names, JSON arrays
 * and objects), non-ASCII text, a row with every nullable column NULL, an exception linked to
 * its master and a 1.5 MB `raw_ical` that still fits the cursor window. On top come the index and
 * triggers a database of that version has (the partial master-uid index up to v6, the master-uid
 * triggers from v7, the pending-operations trigger from v27, the FTS content triggers while
 * `events_fts` exists) and one scenario per migration branch that only runs on matching data
 * ([branches]). The cases `MIGRATION_28_29` decides on (a series sharing its ICS with its changed
 * occurrences, a pull placeholder, a differing imported occurrence, an empty ICS and one holding
 * U+0000) are seeded at v28 only, so the reference dumps of older versions keep their rows. A
 * 2.5 MB ICS, larger than the cursor window, is added only on request (`oversized`): a test that
 * reads cells whole can't hold it.
 *
 * [seed] tries each statement and keeps the ones the schema accepts; the kept list, being plain
 * SQL with literal values, seeds the same state through any connection (or sqlite3 on a device).
 */
object MigrationSeeder {

    /** Holds the instant every seeded timestamp lies before (2021-01-01T00:00:00Z). */
    const val SEED_TIME_LIMIT = 1_609_459_200_000L

    private const val BASE_TS = 1_500_000_000_000L
    private const val ROWS = 3
    private const val LARGE_RAW_ICAL_PAD_BYTES = 750_000

    /** Pairs a migration branch that only runs on certain rows with a query counting those rows. */
    data class Branch(val fromVersion: Int, val description: String, val countSql: String)

    /** Lists the data-dependent migration branches the seed must reach, by source version. */
    val branches = listOf(
        Branch(9, "duplicate occurrences removed", "SELECT COUNT(*) FROM occurrences WHERE id NOT IN (SELECT MIN(id) FROM occurrences GROUP BY event_id, start_ts)"),
        Branch(10, "lifetime_reset_at backfilled", "SELECT COUNT(*) FROM pending_operations"),
        Branch(11, "in-flight moves failed", "SELECT COUNT(*) FROM pending_operations WHERE operation = 'MOVE' AND move_phase = 0 AND status != 'FAILED'"),
        Branch(13, "master duplicating an exception removed", "SELECT COUNT(*) FROM events e1 INNER JOIN events e2 ON e1.calendar_id = e2.calendar_id AND e1.uid = e2.uid AND e1.original_instance_time = e2.original_instance_time WHERE e1.original_event_id IS NULL AND e2.original_event_id IS NOT NULL AND e1.original_instance_time IS NOT NULL"),
        Branch(13, "duplicate exceptions removed", "SELECT COUNT(*) FROM events WHERE original_event_id IS NOT NULL AND original_instance_time IS NOT NULL AND id NOT IN (SELECT MAX(id) FROM events WHERE original_instance_time IS NOT NULL GROUP BY calendar_id, uid, original_instance_time)"),
        Branch(14, "cross-account move pairs counted", "SELECT COUNT(DISTINCT po1.event_id) FROM pending_operations po1 INNER JOIN pending_operations po2 ON po1.event_id = po2.event_id WHERE po1.operation = 'CREATE' AND po2.operation = 'DELETE' AND po1.status = 'PENDING' AND po2.status = 'PENDING'"),
        Branch(21, "tags backfilled from events", "SELECT COUNT(*) FROM events WHERE categories IS NOT NULL AND categories != ''"),
        Branch(26, "operations of missing events", "SELECT COUNT(*) FROM pending_operations WHERE NOT EXISTS (SELECT 1 FROM events e WHERE e.id = pending_operations.event_id)"),
        Branch(27, "search index triggers dropped", "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND instr(sql, 'events_fts') > 0"),
        Branch(28, "pull placeholder takes an occurrence's ICS", "SELECT COUNT(*) FROM events m WHERE m.original_event_id IS NULL AND m.caldav_url IS NULL AND m.raw_ical IS NULL AND m.extra_properties LIKE '%\"X-KASHCAL-SYNTHETIC-MASTER\":\"true\"%' AND EXISTS (SELECT 1 FROM events o WHERE o.original_event_id = m.id AND o.raw_ical IS NOT NULL)"),
        Branch(28, "occurrence ICS differing from its series'", "SELECT COUNT(*) FROM events e JOIN events s ON s.id = e.original_event_id WHERE s.raw_ical IS NOT NULL AND e.raw_ical IS NOT NULL AND e.raw_ical != s.raw_ical"),
        Branch(28, "occurrences sharing their series' ICS", "SELECT COUNT(*) FROM events e JOIN events s ON s.id = e.original_event_id WHERE e.raw_ical = s.raw_ical"),
        Branch(28, "ICS holding U+0000", "SELECT COUNT(*) FROM events WHERE instr(CAST(raw_ical AS BLOB), x'00') > 0"),
        Branch(28, "empty ICS", "SELECT COUNT(*) FROM events WHERE raw_ical = ''"),
    )

    /** Names what a seed statement adds; only [SCENARIO] rows may be refused by a schema. */
    enum class Kind { TRIGGER, ROW, SCENARIO }

    /** Holds one seed statement and what it adds. */
    data class Statement(val sql: String, val kind: Kind)

    /** Holds the outcome of [seed]: statements the schema took and statements it refused. */
    data class Seeded(val kept: List<Statement>, val refused: List<Statement>)

    /**
     * Runs the seed for [version] through [execute]. A refused statement is skipped: a scenario
     * row is refused where that version's unique indexes or triggers forbid the state it builds,
     * which no real database of that version can hold; `MigrationDifferentialTest` requires
     * every [Kind.TRIGGER] and [Kind.ROW] statement to take.
     */
    fun seed(version: Int, oversized: Boolean = false, execute: (String) -> Unit): Seeded {
        val kept = mutableListOf<Statement>()
        val refused = mutableListOf<Statement>()
        for (statement in statements(version, oversized)) {
            try {
                execute(statement.sql)
                kept += statement
            } catch (_: Exception) {
                refused += statement
            }
        }
        return Seeded(kept, refused)
    }

    /** Returns every candidate statement for [version], in the order [seed] runs them. */
    fun statements(version: Int, oversized: Boolean = false): List<Statement> {
        val schema = Schema.load(version)
        val out = mutableListOf<Statement>()
        triggers(version, schema).forEach { out += Statement(it, Kind.TRIGGER) }
        for (table in schema.tablesInForeignKeyOrder()) {
            if (table.isFts) continue
            val rows = if (table.name == "events") eventRows(table) else genericRows(table)
            rows.forEach { out += Statement(insert(table, it), Kind.ROW) }
        }
        scenarios(schema).forEach { out += Statement(it, Kind.SCENARIO) }
        if (version == 28) icsScenarios(schema.table("events")!!, oversized).forEach { out += Statement(it, Kind.SCENARIO) }
        return out
    }

    // ---------- schema ----------

    class Column(val name: String, val affinity: String, val notNull: Boolean)

    class Table(
        val name: String,
        val columns: List<Column>,
        val primaryKey: List<String>,
        val foreignKeys: Map<String, String>,
        val isFts: Boolean,
        val contentSyncTriggers: List<String>,
    ) {
        fun has(vararg names: String) = names.all { n -> columns.any { it.name == n } }
    }

    class Schema(val tables: List<Table>) {
        fun table(name: String): Table? = tables.firstOrNull { it.name == name }

        fun tablesInForeignKeyOrder(): List<Table> {
            val done = linkedSetOf<String>()
            fun visit(t: Table) {
                if (t.name in done) return
                t.foreignKeys.values.filter { it != t.name }.forEach { parent -> table(parent)?.let(::visit) }
                done += t.name
            }
            tables.sortedBy { it.name }.forEach(::visit)
            return done.map { table(it)!! }
        }

        companion object {
            fun load(version: Int): Schema {
                val relative = "schemas/org.onekash.kashcal.data.db.KashCalDatabase/$version.json"
                val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
                    ?: error("Schema $version not found from '${File(".").absolutePath}'")
                val db = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
                val tables = db["entities"]!!.jsonArray.map { it.jsonObject.toTable() }
                return Schema(tables)
            }

            private fun JsonObject.toTable(): Table = Table(
                name = this["tableName"]!!.jsonPrimitive.content,
                columns = this["fields"]!!.jsonArray.map {
                    val f = it.jsonObject
                    Column(
                        name = f["columnName"]!!.jsonPrimitive.content,
                        affinity = f["affinity"]!!.jsonPrimitive.content,
                        notNull = f["notNull"]?.jsonPrimitive?.boolean ?: false,
                    )
                },
                primaryKey = this["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content },
                foreignKeys = (this["foreignKeys"]?.jsonArray ?: emptyList()).flatMap { fk ->
                    val o = fk.jsonObject
                    val parent = o["table"]!!.jsonPrimitive.content
                    o["columns"]!!.jsonArray.map { it.jsonPrimitive.content to parent }
                }.toMap(),
                isFts = containsKey("ftsVersion"),
                contentSyncTriggers = (this["contentSyncTriggers"]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content },
            )
        }
    }

    // ---------- rows ----------

    private fun genericRows(table: Table): List<Row> =
        (1..ROWS).map { r -> table.columns.associate { it.name to value(table, it, r) } } +
            listOf(nullRow(table, ROWS + 1))

    /** Masters 1-3, a master with nullable columns NULL (4), an exception of master 1 (5). */
    private fun eventRows(table: Table): List<Row> {
        val masters = (1..ROWS).map { r -> table.columns.associate { it.name to value(table, it, r) } }
        val exception = masters[0].toMutableMap().apply {
            put("id", "5")
            if (table.has("original_event_id")) put("original_event_id", "1")
            if (table.has("original_instance_time")) put("original_instance_time", "${BASE_TS + 7 * 86_400_000L}")
            if (table.has("title")) put("title", "'Äpfel moved'")
            if (table.has("caldav_url")) put("caldav_url", "NULL")
            if (table.has("raw_ical")) put("raw_ical", "NULL")
            // A blob the app's decoder can't parse; the 21 -> 22 backfill skips it.
            if (table.has("categories")) put("categories", "'[broken'")
        }
        return masters + listOf(nullRow(table, ROWS + 1), exception)
    }

    private fun nullRow(table: Table, r: Int): Row = table.columns.associate { c ->
        c.name to if (!c.notNull && c.name !in table.primaryKey) "NULL" else value(table, c, r, nullRow = true)
    }

    private fun value(table: Table, c: Column, r: Int, nullRow: Boolean = false): String {
        val name = c.name
        // One piece per stored ICS: pieces of an event run from 0.
        if (name == "part_index") return "0"
        if (name in table.primaryKey && c.affinity == "INTEGER") return "$r"
        // Self links (an exception's master, an occurrence's exception row) start empty: rows are
        // masters and plain occurrences unless a row below links them.
        if (name == "original_event_id" || name == "exception_event_id") return "NULL"
        // Only exceptions carry an instance time; a master with one looks like an orphan exception.
        if (name == "original_instance_time") return "NULL"
        // The all-NULL row points at its parent's all-NULL row, so a unique foreign key
        // (ics_subscriptions.calendar_id) doesn't collide with row 1.
        table.foreignKeys[name]?.let { return if (nullRow) "${ROWS + 1}" else "${((r - 1) % ROWS) + 1}" }
        return when (c.affinity) {
            "INTEGER" -> integerValue(table.name, name, r)
            "REAL" -> "${r * 1.5}"
            "BLOB" -> "X'0${r}0${r}'"
            else -> textValue(table.name, name, r)
        }
    }

    private fun integerValue(table: String, name: String, r: Int): String = when {
        name.endsWith("_day") -> "${20200101 + r}"
        name == "dtstamp" || name.endsWith("_ts") || name.endsWith("_at") || "time" in name ||
            name == "last_sync" || name.endsWith("_date") -> "${BASE_TS + r * 3_600_000L}"
        name.startsWith("is_") || name.startsWith("has_") || name.endsWith("_enabled") ||
            name in setOf("visible", "muted", "read_only", "enabled", "deleted") -> "${r % 2}"
        "color" in name -> "${-16_777_216 + r}"
        name == "move_phase" -> "0"
        else -> "$r"
    }

    private fun textValue(table: String, name: String, r: Int): String = when (name) {
        "sync_status" -> listOf("'SYNCED'", "'PENDING_UPDATE'", "'PENDING_CREATE'")[(r - 1) % 3]
        // The app stores providers lowercase (Converters.fromAccountProvider).
        "provider" -> listOf("'local'", "'icloud'", "'caldav'")[(r - 1) % 3]
        "operation" -> listOf("'CREATE'", "'UPDATE'", "'DELETE'")[(r - 1) % 3]
        "status" -> when (table) {
            "pending_operations" -> "'PENDING'"
            "scheduled_reminders" -> listOf("'PENDING'", "'FIRED'", "'DISMISSED'")[(r - 1) % 3]
            "events" -> "'CONFIRMED'"
            else -> "'ok-$r'"
        }
        "change_type" -> listOf("'NEW'", "'MODIFIED'", "'DELETED'")[(r - 1) % 3]
        "changed_fields" -> "'TIME,TITLE'"
        // Row 3 repeats row 1's tag in another case, which the 21 -> 22 backfill folds together.
        "categories" -> if (r == 3) "'[\"work\",\"Café\"]'" else "'[\"Work\",\"Äpfel $r\"]'"
        "reminders" -> "'[\"-PT15M\"]'"
        "calendar_user_addresses" -> "'[\"mailto:user$r@example.test\"]'"
        "extra_properties" -> "'{\"X-TEST-PROP\":\"value $r\"}'"
        "partstat", "partstat_target" -> "'NEEDS-ACTION'"
        "role" -> "'REQ-PARTICIPANT'"
        "cutype" -> "'INDIVIDUAL'"
        "transp" -> "'OPAQUE'"
        "classification" -> "'PUBLIC'"
        "rrule" -> if (r == 1) "'FREQ=WEEKLY;COUNT=5'" else "NULL"
        "exdate", "rdate", "linked_move_id", "duration", "last_error", "last_sync_error" -> "NULL"
        "timezone", "end_timezone" -> "'Europe/Berlin'"
        "vcard_version" -> "'3.0'"
        "uid", "event_uid" -> "'uid-$r@example.test'"
        "email", "address", "organizer_email", "username" -> "'user$r@example.test'"
        "title", "event_title" -> listOf("'Äpfel kaufen'", "'Москва trip'", "'Café $r'")[(r - 1) % 3]
        "raw_ical" -> if (table == "events" && r == 2) {
            "'BEGIN:VCALENDAR' || char(13, 10) || 'X-PAD:' || hex(zeroblob($LARGE_RAW_ICAL_PAD_BYTES)) || char(13, 10) || 'END:VCALENDAR'"
        } else {
            "'BEGIN:VCALENDAR' || char(13, 10) || 'UID:uid-$r@example.test' || char(13, 10) || 'END:VCALENDAR'"
        }
        else -> if (name.endsWith("url")) "'https://dav.example.test/$table/$r/'" else "'$name-$table-$r'"
    }

    private fun insert(table: Table, row: Row): String {
        val cols = row.keys.filter { k -> table.columns.any { it.name == k } }
        return "INSERT INTO `${table.name}` (${cols.joinToString(", ") { "`$it`" }}) VALUES (${cols.joinToString(", ") { row.getValue(it) }})"
    }

    // ---------- triggers and branch scenarios ----------

    private fun triggers(version: Int, schema: Schema): List<String> = buildList {
        schema.tables.filter { it.isFts }.forEach { addAll(it.contentSyncTriggers) }
        if (version < 7) add(MASTER_UID_INDEX_BEFORE_V7)
        if (version >= 7) {
            add(MASTER_UNIQUE_INSERT_TRIGGER)
            add(MASTER_UNIQUE_UPDATE_TRIGGER)
        }
        if (version >= 27) add(PENDING_OPERATIONS_TRIGGER_V27)
    }

    // The SQL text below is what real databases store in sqlite_master: the startup index the
    // first releases created up to v6, and the triggers as MIGRATION_6_7 and MIGRATION_26_27
    // create them.

    private val MASTER_UID_INDEX_BEFORE_V7 = """
        CREATE UNIQUE INDEX IF NOT EXISTS index_events_uid_calendar_master
        ON events (uid, calendar_id)
        WHERE original_event_id IS NULL
    """.trimIndent()

    private val MASTER_UNIQUE_INSERT_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS trigger_master_event_unique_insert
        BEFORE INSERT ON events
        WHEN NEW.original_event_id IS NULL
        BEGIN
            SELECT RAISE(ABORT, 'UNIQUE constraint failed: duplicate master event uid in calendar')
            WHERE EXISTS (
                SELECT 1 FROM events
                WHERE uid = NEW.uid
                AND calendar_id = NEW.calendar_id
                AND original_event_id IS NULL
            );
        END
    """.trimIndent()

    private val MASTER_UNIQUE_UPDATE_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS trigger_master_event_unique_update
        BEFORE UPDATE ON events
        WHEN NEW.original_event_id IS NULL
        BEGIN
            SELECT RAISE(ABORT, 'UNIQUE constraint failed: duplicate master event uid in calendar')
            WHERE EXISTS (
                SELECT 1 FROM events
                WHERE uid = NEW.uid
                AND calendar_id = NEW.calendar_id
                AND original_event_id IS NULL
                AND id != NEW.id
            );
        END
    """.trimIndent()

    private val PENDING_OPERATIONS_TRIGGER_V27 = """
        CREATE TRIGGER trigger_pending_operations_follow_event_delete
        AFTER DELETE ON events
        BEGIN
            DELETE FROM pending_operations
            WHERE event_id = OLD.id
              AND operation = 'DELETE'
              AND linked_move_id IS NOT NULL
              AND linked_move_id IN (
                  SELECT linked_move_id FROM pending_operations
                  WHERE event_id = OLD.id AND operation = 'CREATE' AND linked_move_id IS NOT NULL
              );
            DELETE FROM pending_operations
            WHERE event_id = OLD.id
              AND NOT (operation = 'DELETE' AND target_url IS NOT NULL AND source_calendar_id IS NOT NULL);
        END
    """.trimIndent()

    private fun scenarios(schema: Schema): List<String> = buildList {
        val events = schema.table("events")
        val occurrences = schema.table("occurrences")
        val ops = schema.table("pending_operations")
        val accounts = schema.table("accounts")

        // A second occurrence row for the same event and start (removed by 9 -> 10).
        occurrences?.let { add(copyRow(it, "id = 1", 100)) }

        // A CalDAV account without a home set (12 -> 13 logs a count of these; its query compares
        // with 'CALDAV' while the app stores 'caldav', so the count is always 0).
        accounts?.let { a ->
            val row = genericRows(a)[0].toMutableMap()
            row["id"] = "100"
            row["provider"] = "'caldav'"
            if (a.has("home_set_url")) row["home_set_url"] = "NULL"
            if (a.has("email")) row["email"] = "'nohomeset@example.test'"
            add(insert(a, row))
        }

        if (events != null && events.has("original_instance_time", "original_event_id")) {
            // A master duplicating its own exception, and a duplicated exception (13 -> 14).
            val master = genericRows(events)[2].toMutableMap()
            master["id"] = "101"
            master["uid"] = "'orphan-uid@example.test'"
            master["original_instance_time"] = "${BASE_TS + 9 * 86_400_000L}"
            if (events.has("caldav_url")) master["caldav_url"] = "NULL"
            add(insert(events, master))
            val exception = master.toMutableMap().apply { put("id", "102"); put("original_event_id", "101") }
            add(insert(events, exception))
            // Exception 5 again under master 2: same calendar, uid and instance, so the generic
            // step of 13 -> 14 removes one of the pair (the orphan step leaves both alone).
            add(copyRow(events, "id = 5", 103, mapOf("original_event_id" to "2")))
        }

        ops?.let { o ->
            fun op(id: Int, eventId: Int, operation: String, extra: Map<String, String> = emptyMap()) {
                val row = genericRows(o)[0].toMutableMap()
                row["id"] = "$id"
                row["event_id"] = "$eventId"
                row["operation"] = "'$operation'"
                if (o.has("status")) row["status"] = "'PENDING'"
                extra.forEach { (k, v) -> if (o.has(k)) row[k] = v }
                add(insert(o, row))
            }
            // An in-flight move (11 -> 12).
            op(100, 1, "MOVE", mapOf("move_phase" to "0", "source_calendar_id" to "NULL"))
            // A cross-account move pair without link ids (14 -> 15).
            op(101, 2, "CREATE", mapOf("linked_move_id" to "NULL"))
            op(102, 2, "DELETE", mapOf("linked_move_id" to "NULL"))
            // Operations whose event row is gone (26 -> 27): a plain one, a self-contained
            // DELETE, and a cross-account pair that lost its CREATE row.
            op(103, 999, "UPDATE")
            op(104, 998, "DELETE", mapOf("target_url" to "'https://dav.example.test/gone/'", "source_calendar_id" to "1"))
            op(105, 997, "DELETE", mapOf("linked_move_id" to "'move-1'"))
            op(106, 996, "CREATE", mapOf("linked_move_id" to "'move-1'"))
        }
    }

    /** Returns the v28 rows `MIGRATION_28_29` sorts into owners; see the class doc. */
    private fun icsScenarios(events: Table, oversized: Boolean): List<String> = buildList {
        fun row(id: Int, uid: String, rawIcal: String, series: Int? = null, url: String? = "'https://dav.example.test/ics/$id.ics'", extra: String? = null) {
            val r = genericRows(events)[1].toMutableMap()
            r["id"] = "$id"
            r["uid"] = "'$uid'"
            r["import_id"] = "'$uid'"
            r["raw_ical"] = rawIcal
            r["caldav_url"] = url ?: "NULL"
            r["original_event_id"] = series?.toString() ?: "NULL"
            r["original_instance_time"] = series?.let { "${BASE_TS + id * 86_400_000L}" } ?: "NULL"
            r["rrule"] = if (series == null) "'FREQ=DAILY;COUNT=30'" else "NULL"
            extra?.let { r["extra_properties"] = it }
            add(insert(events, r))
        }
        fun ics(text: String) = "'BEGIN:VCALENDAR' || char(13, 10) || '$text' || char(13, 10) || 'END:VCALENDAR'"
        // A series whose four changed occurrences share its resource.
        row(200, "shared-uid@example.test", ics("UID:shared-uid@example.test"))
        (201..204).forEach { row(it, "shared-uid@example.test", ics("UID:shared-uid@example.test"), series = 200) }
        // An imported occurrence a newer file changed: no URL, its own text.
        row(205, "shared-uid@example.test", ics("UID:shared-uid@example.test X-CHANGED"), series = 200, url = null)
        // A pull placeholder and two orphan occurrences from different resources.
        row(210, "orphan-pair@example.test", "NULL", url = null, extra = "'{\"X-KASHCAL-SYNTHETIC-MASTER\":\"true\"}'")
        row(211, "orphan-pair@example.test", ics("ORPHAN A"), series = 210)
        row(212, "orphan-pair@example.test", ics("ORPHAN B"), series = 210)
        // An empty ICS and one holding U+0000, both stored as none.
        row(220, "empty-ics@example.test", "''")
        row(221, "nul-ics@example.test", "'BEGIN' || char(0) || 'END'")
        if (oversized) {
            // 2.5 MB, larger than the cursor window, shared by one changed occurrence.
            val big = "'BEGIN:VCALENDAR' || char(13, 10) || 'X-PAD:' || replace(hex(zeroblob(1250000)), '00', 'äb') || char(13, 10) || 'END:VCALENDAR'"
            row(230, "oversized@example.test", big)
            row(231, "oversized@example.test", big, series = 230)
        }
    }

    /**
     * Returns an INSERT copying the row matching [where] under a new primary key [newId], with
     * [overrides] (column -> SQL literal) replacing copied values.
     */
    private fun copyRow(table: Table, where: String, newId: Int, overrides: Map<String, String> = emptyMap()): String {
        val cols = table.columns.map { it.name }
        val select = cols.joinToString(", ") { if (it == "id") "$newId" else overrides[it] ?: "`$it`" }
        return "INSERT INTO `${table.name}` (${cols.joinToString(", ") { "`$it`" }}) SELECT $select FROM `${table.name}` WHERE $where"
    }
}
