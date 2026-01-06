package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService
import org.onekash.kashcal.sync.provider.CalendarProvider
import org.onekash.kashcal.sync.provider.ProviderRegistry
import org.onekash.kashcal.sync.provider.ProviderRegistryImpl
import org.onekash.kashcal.sync.provider.icloud.ICloudProvider
import org.onekash.kashcal.sync.provider.local.LocalProvider
import javax.inject.Singleton

/**
 * Hilt module for calendar provider registration.
 *
 * Uses @IntoSet to build a Set<CalendarProvider> that is injected into
 * ProviderRegistryImpl. This pattern allows:
 * - Easy addition of new providers (just add @Provides @IntoSet method)
 * - No changes needed to ProviderRegistry when adding providers
 * - Compile-time verification of provider registration
 *
 * Registered providers:
 * - ICloudProvider: iCloud CalDAV sync
 * - LocalProvider: Offline-only local calendars
 *
 * To add a new provider:
 * 1. Create implementation in sync/provider/{name}/{Name}Provider.kt
 * 2. Add @Provides @IntoSet method below
 * 3. Done! ProviderRegistry will automatically include it
 *
 * @see CalendarProvider for provider interface
 * @see ProviderRegistry for runtime lookup
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    /**
     * Bind ProviderRegistry interface to implementation.
     *
     * ProviderRegistryImpl receives Set<CalendarProvider> via @IntoSet
     * injection and provides O(1) provider lookup.
     */
    @Binds
    @Singleton
    abstract fun bindProviderRegistry(impl: ProviderRegistryImpl): ProviderRegistry

    /**
     * Bind AccountDiscoveryService interface to iCloud implementation.
     *
     * Currently only iCloud is supported. When more providers are added,
     * this could be changed to use @IntoSet or a discovery registry pattern.
     */
    @Binds
    @Singleton
    abstract fun bindAccountDiscoveryService(impl: ICloudAccountDiscoveryService): AccountDiscoveryService

    companion object {
        /**
         * Register iCloud provider.
         *
         * Provides iCloud CalDAV sync with:
         * - ICloudQuirks for XML parsing
         * - ICloudCredentialProvider for authentication
         */
        @Provides
        @Singleton
        @IntoSet
        fun provideICloudProvider(provider: ICloudProvider): CalendarProvider = provider

        /**
         * Register local provider.
         *
         * Provides offline-only calendar storage without network sync.
         */
        @Provides
        @Singleton
        @IntoSet
        fun provideLocalProvider(provider: LocalProvider): CalendarProvider = provider
    }
}
