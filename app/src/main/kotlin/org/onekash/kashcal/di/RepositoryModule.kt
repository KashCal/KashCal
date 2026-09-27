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

/** Binds the credential manager and the account, calendar and device-provider repositories. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCredentialManager(impl: UnifiedCredentialManager): CredentialManager

    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds
    @Singleton
    abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository

    /** Binds [CalendarProviderRepository]: device-calendar reads and writes via ContentResolver. */
    @Binds
    @Singleton
    abstract fun bindCalendarProviderRepository(
        impl: AndroidCalendarProviderRepository
    ): CalendarProviderRepository

    /**
     * Binds [ContactsProviderRepository], the only surface that writes CardDAV-synced contacts to
     * the Contacts Provider.
     */
    @Binds
    @Singleton
    abstract fun bindContactsProviderRepository(
        impl: AndroidContactsProviderRepository
    ): ContactsProviderRepository
}
