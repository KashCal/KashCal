package org.onekash.kashcal.data.preferences

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing preferences-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun provideKashCalDataStore(
        @ApplicationContext context: Context
    ): KashCalDataStore {
        return KashCalDataStore(context)
    }
}
