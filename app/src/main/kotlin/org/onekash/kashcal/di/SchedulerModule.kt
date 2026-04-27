package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.sync.scheduler.IcsScheduler
import org.onekash.kashcal.sync.scheduler.WorkManagerIcsScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulerModule {

    @Binds
    @Singleton
    abstract fun bindIcsScheduler(impl: WorkManagerIcsScheduler): IcsScheduler
}
