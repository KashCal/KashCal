package org.onekash.kashcal.di

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AddressBookDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.CategoryDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.PendingCancelsDao
import org.onekash.kashcal.data.db.dao.PendingOperationsDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.Category
import org.onekash.kashcal.data.db.migration.Migrations
import javax.inject.Singleton

/**
 * Provides the Room database, every DAO, the app [ContentResolver] and [WorkManager].
 *
 * Inject DAOs below the UI layer, never into ViewModels.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val TAG = "DatabaseModule"

    /**
     * Creates the master-uniqueness triggers and seeds the starter tags on a fresh install.
     * Upgrades get both from [Migrations.ALL_MIGRATIONS] instead.
     *
     * Triggers stand in for a partial unique index: Room can't declare one in `@Index`, so its
     * schema validation fails on it. They enforce one master per UID in a calendar (RFC 4791
     * §4.1), which stops duplicate masters during iCloud sync (multiple servers may send the
     * same data). Exceptions share the master's UID (`original_event_id IS NOT NULL`) and pass.
     * [KashCalDatabase.testCallback] carries a copy for tests; change both.
     */
    private val databaseCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Log.d(TAG, "Creating triggers for master event deduplication")
            createMasterEventUniqueTriggers(db)
            seedDefaultCategories(db)
        }
    }

    /**
     * Seeds the curated starter tags so a new user gets the same [Category.DEFAULT_SEEDS] an
     * upgrading user gets from `MIGRATION_21_22`. `INSERT OR IGNORE` keeps it idempotent and
     * leaves an existing row for the same name alone.
     */
    private fun seedDefaultCategories(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        for ((name, color) in Category.DEFAULT_SEEDS) {
            db.execSQL(
                "INSERT OR IGNORE INTO categories (name, color, last_used_at) VALUES (?, ?, ?)",
                arrayOf<Any?>(name, color, now)
            )
        }
    }

    /** Creates the triggers that keep (uid, calendar_id) unique among masters. */
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
            // WAL mode, for write performance.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .addCallback(databaseCallback)
            .build()
    }

    @Provides
    @Singleton
    fun provideAccountsDao(database: KashCalDatabase): AccountsDao {
        return database.accountsDao()
    }

    @Provides
    @Singleton
    fun provideAddressBookDao(database: KashCalDatabase): AddressBookDao {
        return database.addressBookDao()
    }

    @Provides
    @Singleton
    fun provideCalendarsDao(database: KashCalDatabase): CalendarsDao {
        return database.calendarsDao()
    }

    @Provides
    @Singleton
    fun provideEventsDao(database: KashCalDatabase): EventsDao {
        return database.eventsDao()
    }

    @Provides
    @Singleton
    fun provideOccurrencesDao(database: KashCalDatabase): OccurrencesDao {
        return database.occurrencesDao()
    }

    @Provides
    @Singleton
    fun provideCategoryDao(database: KashCalDatabase): CategoryDao {
        return database.categoryDao()
    }

    @Provides
    @Singleton
    fun provideAttendeesDao(database: KashCalDatabase): AttendeesDao {
        return database.attendeesDao()
    }

    @Provides
    @Singleton
    fun providePendingOperationsDao(database: KashCalDatabase): PendingOperationsDao {
        return database.pendingOperationsDao()
    }

    @Provides
    @Singleton
    fun providePendingCancelsDao(database: KashCalDatabase): PendingCancelsDao {
        return database.pendingCancelsDao()
    }

    @Provides
    @Singleton
    fun provideSyncLogsDao(database: KashCalDatabase): SyncLogsDao {
        return database.syncLogsDao()
    }

    @Provides
    @Singleton
    fun provideIcsSubscriptionsDao(database: KashCalDatabase): IcsSubscriptionsDao {
        return database.icsSubscriptionsDao()
    }

    @Provides
    @Singleton
    fun provideScheduledRemindersDao(database: KashCalDatabase): ScheduledRemindersDao {
        return database.scheduledRemindersDao()
    }

    /** Provides the app [ContentResolver] for Contacts and Calendar provider access. */
    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context
    ): ContentResolver {
        return context.contentResolver
    }

    /** Provides [WorkManager]; `AccountRepositoryImpl.deleteAccount` cancels sync jobs with it. */
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager {
        return WorkManager.getInstance(context)
    }
}
