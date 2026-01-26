package org.onekash.kashcal.di

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.migration.Migrations
import javax.inject.Singleton

/**
 * Hilt module providing database dependencies.
 *
 * Provides singleton instances of the database and all DAOs.
 * DAOs should be injected into repositories, not ViewModels directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val TAG = "DatabaseModule"

    /**
     * Database callback to create triggers for master event duplicate prevention.
     *
     * Uses triggers instead of partial unique index because Room doesn't support
     * partial indexes in @Index annotations, causing schema validation failures.
     *
     * The triggers enforce RFC 5545: master events must have unique UIDs within a calendar.
     * This prevents duplicates during iCloud sync (multiple servers may send same data).
     * Exception events share the master's UID and are allowed (original_event_id IS NOT NULL).
     */
    private val databaseCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Log.d(TAG, "Creating triggers for master event deduplication")
            createMasterEventUniqueTriggers(db)
        }
    }

    /**
     * Creates triggers to enforce unique (uid, calendar_id) for master events.
     */
    private fun createMasterEventUniqueTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""
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
        """.trimIndent())

        db.execSQL("""
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
        """.trimIndent())
    }

    /**
     * Provide singleton database instance.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): KashCalDatabase {
        return Room.databaseBuilder(
            context,
            KashCalDatabase::class.java,
            KashCalDatabase.DATABASE_NAME
        )
            // Enable WAL mode for better write performance
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Add migrations
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            // Add callback for trigger creation on fresh install
            .addCallback(databaseCallback)
            .build()
    }

    /**
     * Provide AccountsDao.
     */
    @Provides
    @Singleton
    fun provideAccountsDao(database: KashCalDatabase): AccountsDao {
        return database.accountsDao()
    }

    /**
     * Provide CalendarsDao.
     */
    @Provides
    @Singleton
    fun provideCalendarsDao(database: KashCalDatabase): CalendarsDao {
        return database.calendarsDao()
    }

    /**
     * Provide EventsDao.
     */
    @Provides
    @Singleton
    fun provideEventsDao(database: KashCalDatabase): EventsDao {
        return database.eventsDao()
    }

    /**
     * Provide OccurrencesDao.
     */
    @Provides
    @Singleton
    fun provideOccurrencesDao(database: KashCalDatabase): OccurrencesDao {
        return database.occurrencesDao()
    }

    /**
     * Provide PendingOperationsDao.
     */
    @Provides
    @Singleton
    fun providePendingOperationsDao(database: KashCalDatabase): PendingOperationsDao {
        return database.pendingOperationsDao()
    }

    /**
     * Provide SyncLogsDao.
     */
    @Provides
    @Singleton
    fun provideSyncLogsDao(database: KashCalDatabase): SyncLogsDao {
        return database.syncLogsDao()
    }

    /**
     * Provide IcsSubscriptionsDao.
     */
    @Provides
    @Singleton
    fun provideIcsSubscriptionsDao(database: KashCalDatabase): IcsSubscriptionsDao {
        return database.icsSubscriptionsDao()
    }

    /**
     * Provide ScheduledRemindersDao.
     */
    @Provides
    @Singleton
    fun provideScheduledRemindersDao(database: KashCalDatabase): ScheduledRemindersDao {
        return database.scheduledRemindersDao()
    }

    /**
     * Provide ContentResolver for contact queries.
     */
    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context
    ): ContentResolver {
        return context.contentResolver
    }
}
