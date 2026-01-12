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
     * Database callback to create partial unique index for duplicate prevention.
     *
     * This creates a unique index on (uid, calendar_id) for master events only.
     * Exception events share the master's UID and are excluded via WHERE clause.
     * This is RFC 5545 compliant: master events must have unique UIDs within a calendar.
     */
    private val databaseCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Log.d(TAG, "Creating partial unique index for master event deduplication")
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_events_uid_calendar_master
                ON events (uid, calendar_id)
                WHERE original_event_id IS NULL
            """.trimIndent())
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // Ensure index exists even for existing databases (fresh install covers onCreate,
            // but onOpen handles any edge cases where index might be missing)
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_events_uid_calendar_master
                ON events (uid, calendar_id)
                WHERE original_event_id IS NULL
            """.trimIndent())
        }
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
            // Add callback for partial unique index creation
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
