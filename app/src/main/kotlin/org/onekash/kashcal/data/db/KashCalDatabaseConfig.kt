package org.onekash.kashcal.data.db

import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import org.onekash.kashcal.data.db.migration.Migrations

/**
 * Applies the configuration the app opens its database with: [AndroidSQLiteDriver], which keeps
 * the phone's own SQLite (its Unicode-aware `LIKE` case folding, which search relies on, and the
 * framework's 2 MB cursor window), WAL journal mode, every [Migrations.ALL_MIGRATIONS] step and
 * the [DatabaseCreateCallback]. `DatabaseModule` builds production with it, and the test helper
 * that builds a production-configured database uses the same call, so a setting added here
 * reaches both.
 */
fun RoomDatabase.Builder<KashCalDatabase>.kashCalProductionConfig(): RoomDatabase.Builder<KashCalDatabase> =
    setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(*Migrations.ALL_MIGRATIONS)
        .addCallback(DatabaseCreateCallback)
