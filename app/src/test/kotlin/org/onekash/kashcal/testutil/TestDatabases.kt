package org.onekash.kashcal.testutil

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.kashCalProductionConfig

/**
 * Builds every Room database the unit tests use, on [AndroidSQLiteDriver], the driver the app
 * opens its database with; [production] adds the app's whole configuration.
 *
 * Builders are returned unbuilt, so each test keeps adding its own callbacks, migrations and
 * options. `TestDatabasesGuardTest` fails on a Room builder created anywhere else.
 */
object TestDatabases {

    /** Returns an in-memory [KashCalDatabase] builder. */
    fun inMemory(context: Context): RoomDatabase.Builder<KashCalDatabase> =
        Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).setDriver(AndroidSQLiteDriver())

    /**
     * Returns an in-memory [KashCalDatabase] builder that reports each SQL statement to
     * [onStatement] on the thread that runs it, as each statement is prepared.
     */
    fun inMemoryObserving(context: Context, onStatement: (String) -> Unit): RoomDatabase.Builder<KashCalDatabase> =
        Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .setDriver(StatementReportingDriver(AndroidSQLiteDriver(), onStatement))

    /** Reports each statement its connections prepare to [onStatement]; see [inMemoryObserving]. */
    private class StatementReportingDriver(
        private val delegate: SQLiteDriver,
        private val onStatement: (String) -> Unit,
    ) : SQLiteDriver by delegate {
        override fun open(fileName: String): SQLiteConnection = Connection(delegate.open(fileName))

        private inner class Connection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                onStatement(sql)
                return connection.prepare(sql)
            }
        }
    }

    /** Returns a file-backed [KashCalDatabase] builder named [name]. */
    fun file(context: Context, name: String): RoomDatabase.Builder<KashCalDatabase> =
        Room.databaseBuilder(context, KashCalDatabase::class.java, name).setDriver(AndroidSQLiteDriver())

    /** Holds the current schema version: the newest schema the build exported. */
    val exportedSchemaVersion: Int by lazy {
        val dir = "schemas/org.onekash.kashcal.data.db.KashCalDatabase"
        listOf(java.io.File(dir), java.io.File("app/$dir")).first { it.isDirectory }
            .listFiles { f -> f.extension == "json" }!!.maxOf { it.nameWithoutExtension.toInt() }
    }

    /**
     * Returns an in-memory [KashCalDatabase] builder with the production configuration
     * ([kashCalProductionConfig]): its driver, migrations, journal mode and create callback.
     */
    fun production(context: Context): RoomDatabase.Builder<KashCalDatabase> =
        inMemory(context).kashCalProductionConfig()

    /**
     * Runs the write [sql] on [database]'s writer connection with [args] bound in order as
     * [execSQL] binds them. Blocks, so call it outside a transaction.
     */
    fun execute(database: RoomDatabase, sql: String, vararg args: Any?) {
        runBlocking {
            database.useWriterConnection { connection ->
                connection.usePrepared(sql) { statement ->
                    statement.bindArgs(args)
                    statement.step()
                }
            }
        }
    }

    /**
     * Runs the read [sql] on [database] with [args] bound as [execute] binds them and returns
     * each row mapped by [row]. Blocks, so call it outside a transaction.
     */
    fun <T> query(database: RoomDatabase, sql: String, vararg args: Any?, row: (SQLiteStatement) -> T): List<T> =
        runBlocking {
            database.useReaderConnection { connection ->
                connection.usePrepared(sql) { statement ->
                    statement.bindArgs(args)
                    buildList { while (statement.step()) add(row(statement)) }
                }
            }
        }

    /** Runs the read [sql] like [query] and returns every row read up front, cursor style. */
    fun queryRows(database: RoomDatabase, sql: String, vararg args: Any?): QueryRows =
        runBlocking {
            database.useReaderConnection { connection ->
                connection.usePrepared(sql) { statement ->
                    statement.bindArgs(args)
                    statement.readRows()
                }
            }
        }

    /**
     * Returns raw SQL access to [database] with `execSQL` and `query` calls, so a test keeps its
     * statements while the access goes through Room's connections. Both run on
     * the writer connection. On an in-memory database, which every caller uses, that is one
     * connection, so a read sees its state after a write (`last_insert_rowid()`, `changes()`,
     * TEMP tables); on a WAL file outside a transaction a read may use another connection.
     */
    fun raw(database: RoomDatabase): RawSql = RawSql(database)

    /** Gives a test raw `execSQL` and `query` on one database's writer connection; see [raw]. */
    class RawSql(private val database: RoomDatabase) {
        fun execSQL(sql: String, args: Array<out Any?> = emptyArray()) = execute(database, sql, *args)

        fun query(sql: String, args: Array<out Any?>? = null): QueryRows = runBlocking {
            database.useWriterConnection { connection ->
                connection.usePrepared(sql) { statement ->
                    statement.bindArgs(args ?: emptyArray())
                    statement.readRows()
                }
            }
        }
    }

    /** Returns whether [database]'s `sqlite_master` holds the trigger [name]. */
    fun triggerExists(database: RoomDatabase, name: String): Boolean =
        query(database, "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?", name) { true }.isNotEmpty()
}
