package org.onekash.kashcal.data.db.migration

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import java.security.MessageDigest

/**
 * Writes a database's whole state as text, so two upgrades of the same seed can be compared
 * line by line: `PRAGMA user_version`, every schema object in `sqlite_master` (tables, indexes,
 * triggers, with their SQL) and every row of every table in rowid order.
 *
 * Each value is written with its SQLite storage class (`integer:5`, `text:...`), so a value
 * stored as the wrong type differs even where column affinity would make it compare equal.
 * Text longer than [LARGE_VALUE_CHARS] and every blob are written as their length and SHA-256,
 * which keeps committed dumps small while still pinning every byte. Schema SQL and text are
 * escaped (backslash, CR, LF, `|`), so every schema object and row is exactly one line and a
 * reordering inside a trigger changes the text. Two dumps match only when their whole text is
 * equal; [differences] is for the failure message. Integers inside [timeWindow] are
 * written as `<NOW>`: migrations stamp some rows with the clock, and a seed never uses such
 * values (MigrationSeeder keeps every seeded timestamp before [MigrationSeeder.SEED_TIME_LIMIT]).
 */
object DatabaseDump {

    const val LARGE_VALUE_CHARS = 1024

    /** Returns the dump of the database file at [file], opened with [AndroidSQLiteDriver]. */
    fun of(file: File, timeWindow: LongRange? = null): String {
        val connection = AndroidSQLiteDriver().open(file.path)
        return try {
            of(connection, timeWindow)
        } finally {
            connection.close()
        }
    }

    /** Returns the dump of the database behind [connection]. */
    fun of(connection: SQLiteConnection, timeWindow: LongRange? = null): String = buildString {
        appendLine("user_version=" + connection.single("PRAGMA user_version"))
        val tables = mutableListOf<String>()
        var hasSequenceTable = false
        connection.prepare(
            "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_autoindex_%' ORDER BY type, name"
        ).use { st ->
            while (st.step()) {
                val type = st.getText(0)
                val name = st.getText(1)
                val sql = if (st.isNull(3)) "" else escape(st.getText(3))
                appendLine("schema|$type|$name|${st.getText(2)}|$sql")
                if (type == "table" && name == "sqlite_sequence") hasSequenceTable = true
                if (type == "table" && name != "sqlite_sequence") tables += name
            }
        }
        if (hasSequenceTable) {
            connection.prepare("SELECT name, seq FROM sqlite_sequence ORDER BY name").use { st ->
                while (st.step()) appendLine("sequence|${st.getText(0)}|${st.getLong(1)}")
            }
        }
        for (table in tables) {
            connection.prepare("SELECT * FROM `$table` ORDER BY rowid").use { st ->
                val columns = st.getColumnNames()
                var row = 0
                while (st.step()) {
                    val values = columns.indices.joinToString("|") { i ->
                        "${columns[i]}=${render(st, i, timeWindow)}"
                    }
                    appendLine("row|$table|${row++}|$values")
                }
            }
        }
    }

    /**
     * Describes how [actual] differs from [expected] for a failure message: lines only in one of
     * them ("-" expected, "+" actual), or the first line where the order differs when both hold
     * the same lines. Returns an empty list only when the two texts are equal.
     */
    fun differences(expected: String, actual: String, limit: Int = 40): List<String> {
        if (expected == actual) return emptyList()
        val a = expected.lines()
        val b = actual.lines()
        val onlyExpected = a.toSet() - b.toSet()
        val onlyActual = b.toSet() - a.toSet()
        val lines = a.filter { it in onlyExpected }.map { "- $it" } +
            b.filter { it in onlyActual }.map { "+ $it" }
        if (lines.isNotEmpty()) return lines.take(limit)
        val first = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
        return listOf("same lines, different order or count from line ${first + 1}: " +
            "expected '${a.getOrNull(first)}', actual '${b.getOrNull(first)}'")
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("|", "\\|")

    private fun render(st: SQLiteStatement, index: Int, timeWindow: LongRange?): String =
        when (st.getColumnType(index)) {
            SQLITE_DATA_NULL -> "null"
            SQLITE_DATA_INTEGER -> {
                val value = st.getLong(index)
                if (timeWindow != null && value in timeWindow) "integer:<NOW>" else "integer:$value"
            }
            SQLITE_DATA_FLOAT -> "real:${st.getDouble(index)}"
            SQLITE_DATA_TEXT -> {
                val text = st.getText(index)
                if (text.length > LARGE_VALUE_CHARS) {
                    "text:len=${text.length},sha256=${sha256(text.toByteArray(Charsets.UTF_8))}"
                } else {
                    "text:" + escape(text)
                }
            }
            SQLITE_DATA_BLOB -> st.getBlob(index).let { "blob:len=${it.size},sha256=${sha256(it)}" }
            else -> error("unknown column type ${st.getColumnType(index)}")
        }

    private fun SQLiteConnection.single(sql: String): Long =
        prepare(sql).use { st -> st.step(); st.getLong(0) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
