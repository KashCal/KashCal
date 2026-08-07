package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.credential.UnifiedCredentialManager
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.sync.contacts.AndroidContactsProviderRepository
import org.onekash.kashcal.sync.contacts.ContactsProviderRepository
import javax.inject.Singleton

/**
 * Hilt module for repository layer bindings.
 *
 * Provides unified credential manager and repository implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Bind CredentialManager interface to unified implementation.
     */
    @Binds
    @Singleton
    abstract fun bindCredentialManager(impl: UnifiedCredentialManager): CredentialManager

    /**
     * Bind AccountRepository interface to implementation.
     * Single source of truth for account operations.
     */
    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    /**
     * Bind CalendarRepository interface to implementation.
     * Single source of truth for calendar operations.
     */
    @Binds
    @Singleton
    abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository

    /**
     * Bind CalendarProviderRepository interface to Android implementation.
     * Provides read access to device calendars via ContentResolver.
     */
    @Binds
    @Singleton
    abstract fun bindCalendarProviderRepository(
        impl: AndroidCalendarProviderRepository
    ): CalendarProviderRepository

    /**
     * Bind ContactsProviderRepository to the Android implementation — the only
     * surface that writes CardDAV-synced contacts to the Contacts Provider.
     */
    @Binds
    @Singleton
    abstract fun bindContactsProviderRepository(
        impl: AndroidContactsProviderRepository
    ): ContactsProviderRepository
}
