package org.onekash.kashcal.testutil

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import org.onekash.kashcal.data.db.KashCalDatabase

/**
 * Builds every Room database the instrumented tests use, on [AndroidSQLiteDriver], the driver the
 * app opens its database with, as the unit tests' `TestDatabases` does.
 */
object DeviceTestDatabases {

    /** Returns an in-memory [KashCalDatabase] builder. */
    fun inMemory(context: Context): RoomDatabase.Builder<KashCalDatabase> =
        Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).setDriver(AndroidSQLiteDriver())

    /** Returns a file-backed [KashCalDatabase] builder named [name]. */
    fun file(context: Context, name: String): RoomDatabase.Builder<KashCalDatabase> =
        Room.databaseBuilder(context, KashCalDatabase::class.java, name).setDriver(AndroidSQLiteDriver())
}
