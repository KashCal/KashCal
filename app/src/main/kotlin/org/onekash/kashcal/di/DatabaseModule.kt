package org.onekash.kashcal.di

import android.content.ContentResolver
import android.content.Context
import androidx.room3.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.kashCalProductionConfig
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
import javax.inject.Singleton

/**
 * Provides the Room database, every DAO, the app [ContentResolver] and [WorkManager].
 *
 * Inject DAOs below the UI layer, never into ViewModels.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

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
            .kashCalProductionConfig()
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
