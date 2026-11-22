package org.onekash.kashcal.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import org.onekash.kashcal.calendarbackup.BackupFolder
import org.onekash.kashcal.calendarbackup.CalendarBackupAlerts
import org.onekash.kashcal.calendarbackup.CalendarBackupNotifications
import org.onekash.kashcal.calendarbackup.EventCoordinatorOfflineCalendarSource
import org.onekash.kashcal.calendarbackup.OfflineCalendarSource
import org.onekash.kashcal.calendarbackup.SafBackupFolder
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarBackupModule {

    @Binds
    abstract fun bindCalendarBackupAlerts(impl: CalendarBackupNotifications): CalendarBackupAlerts

    @Binds
    abstract fun bindOfflineCalendarSource(impl: EventCoordinatorOfflineCalendarSource): OfflineCalendarSource

    companion object {
        @Provides
        @Singleton
        fun provideBackupFolder(
            @ApplicationContext context: Context,
            @IoDispatcher io: CoroutineDispatcher,
        ): BackupFolder = SafBackupFolder.forResolver(context, io)
    }
}
