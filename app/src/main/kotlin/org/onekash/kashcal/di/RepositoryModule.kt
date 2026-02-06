package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.credential.UnifiedCredentialManager
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import javax.inject.Singleton

/**
 * Hilt module for repository layer bindings.
 *
 * Provides unified credential manager and repository implementations.
 *
 * Pattern #33 (CLAUDE.md): Use repositories, not DAOs, for Account and Calendar operations.
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
}
