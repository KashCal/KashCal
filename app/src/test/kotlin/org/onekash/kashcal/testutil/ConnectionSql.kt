package org.onekash.kashcal.testutil

import android.content.Context
import androidx.room3.migration.Migration
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Raw-SQL helpers for tests that work on a bare [SQLiteConnection], built only from the
 * documented `androidx.sqlite` calls (`prepare`, `bind*`, `step`, `get*`).
 */
object ConnectionSql {

    /**
     * Opens a new, empty database file [name] in the app's database directory through
     * [AndroidSQLiteDriver]. Close it with [close].
     */
    fun open(context: Context, name: String): SQLiteConnection {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        deleteFiles(file)
        return AndroidSQLiteDriver().open(file.path)
    }

    /** Closes [connection] and deletes database file [name] with its journal files. */
    fun close(context: Context, connection: SQLiteConnection, name: String) {
        connection.close()
        deleteFiles(context.getDatabasePath(name))
    }

    private fun deleteFiles(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    /**
     * Runs [migration] inside `BEGIN EXCLUSIVE TRANSACTION`, committed when it returns and rolled
     * back when it throws. Room runs a whole upgrade chain in one such transaction; this commits
     * each migration on its own, so a later step that throws leaves the earlier ones in place.
     */
    fun migrate(connection: SQLiteConnection, migration: Migration) {
        connection.execSQL("BEGIN EXCLUSIVE TRANSACTION")
        try {
            runBlocking { migration.migrate(connection) }
        } catch (e: Throwable) {
            // SQLite may already have rolled back on its own (a full disk, for one); keep the
            // migration's error rather than the ROLLBACK's.
            runCatching { connection.execSQL("ROLLBACK TRANSACTION") }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        connection.execSQL("END TRANSACTION")
    }
}

/**
 * Runs [sql] with [args] bound in order: null as NULL, a String as text, an integral number or
 * Boolean as an integer, a Float or Double as a real and a ByteArray as a blob.
 */
fun SQLiteConnection.execSQL(sql: String, args: Array<out Any?>) {
    prepare(sql).use { statement ->
        statement.bindArgs(args)
        statement.step()
    }
}

/**
 * Inserts one row of [values] (column to value, bound as [execSQL] binds them) into [table] and
 * returns its rowid; a constraint failure throws.
 */
fun SQLiteConnection.insert(table: String, values: Map<String, Any?>): Long {
    val columns = values.keys.joinToString(", ")
    val marks = values.keys.joinToString(", ") { "?" }
    execSQL("INSERT INTO $table ($columns) VALUES ($marks)", values.values.toTypedArray())
    return prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
}

/** Runs the query [sql] with [args] bound as [execSQL] binds them and reads every row up front. */
fun SQLiteConnection.queryRows(sql: String, args: Array<out Any?> = emptyArray()): QueryRows =
    prepare(sql).use { statement ->
        statement.bindArgs(args)
        statement.readRows()
    }

/** Steps through every remaining row of this statement and returns them as [QueryRows]. */
internal fun SQLiteStatement.readRows(): QueryRows {
    val columns = getColumnNames()
    val rows = mutableListOf<List<Cell>>()
    while (step()) {
        rows += columns.indices.map { i -> Cell.read(this, i) }
    }
    return QueryRows(columns, rows)
}

/** Binds [args] in order the way [execSQL] documents. */
internal fun SQLiteStatement.bindArgs(args: Array<out Any?>) {
    args.forEachIndexed { i, arg ->
        val index = i + 1
        when (arg) {
            null -> bindNull(index)
            is String -> bindText(index, arg)
            is Long -> bindLong(index, arg)
            is Int -> bindLong(index, arg.toLong())
            is Short -> bindLong(index, arg.toLong())
            is Byte -> bindLong(index, arg.toLong())
            is Boolean -> bindLong(index, if (arg) 1L else 0L)
            is Double -> bindDouble(index, arg)
            is Float -> bindDouble(index, arg.toDouble())
            is ByteArray -> bindBlob(index, arg)
            else -> throw IllegalArgumentException("unsupported bind type ${arg::class}")
        }
    }
}

/**
 * Holds one result value with the driver's text, integer and real readings of it; a blob is
 * kept as bytes and has no text reading, as a cursor's `getString` refuses one.
 */
class Cell private constructor(
    val isNull: Boolean,
    val text: String?,
    val long: Long,
    val double: Double,
    val blob: ByteArray?,
) {
    companion object {
        fun read(statement: SQLiteStatement, i: Int): Cell = when (statement.getColumnType(i)) {
            SQLITE_DATA_NULL -> Cell(true, null, 0L, 0.0, null)
            SQLITE_DATA_BLOB -> Cell(false, null, 0L, 0.0, statement.getBlob(i))
            else -> Cell(false, statement.getText(i), statement.getLong(i), statement.getDouble(i), null)
        }
    }
}

/**
 * Holds the rows of a [queryRows], read with cursor-style calls (`moveToFirst`, `moveToNext`,
 * `getString`, `getLong`, `isNull`, `count`) so assertions written against a cursor read the same.
 * Columns are 0-based.
 */
class QueryRows(private val columns: List<String>, private val rows: List<List<Cell>>) : AutoCloseable {
    private var position = -1

    val count: Int get() = rows.size
    val columnCount: Int get() = columns.size

    fun moveToFirst(): Boolean {
        position = 0
        return rows.isNotEmpty()
    }

    fun moveToNext(): Boolean {
        if (position < rows.size) position++
        return position < rows.size
    }

    /** Returns the index of column [name], or -1 if the result has none. */
    fun getColumnIndex(name: String): Int = columns.indexOf(name)

    fun getColumnIndexOrThrow(name: String): Int =
        getColumnIndex(name).also { require(it >= 0) { "no column $name in $columns" } }

    fun isNull(i: Int): Boolean = cell(i).isNull
    fun getString(i: Int): String? =
        cell(i).let { check(it.blob == null) { "column $i holds a blob" }; it.text }
    fun getLong(i: Int): Long = cell(i).long
    fun getInt(i: Int): Int = cell(i).long.toInt()
    fun getDouble(i: Int): Double = cell(i).double
    fun getBlob(i: Int): ByteArray? = cell(i).blob

    private fun cell(i: Int): Cell {
        check(position in rows.indices) { "no current row (position $position of ${rows.size})" }
        return rows[position][i]
    }

    override fun close() {}
}
