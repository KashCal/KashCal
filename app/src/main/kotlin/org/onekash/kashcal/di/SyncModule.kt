package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.ics.IcsFetcher
import org.onekash.kashcal.data.ics.OkHttpIcsFetcher
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import javax.inject.Singleton

/**
 * Hilt module providing sync layer dependencies.
 *
 * Binds interfaces to their implementations for:
 * - CalDAV HTTP client and factory
 * - ICS fetcher
 *
 * For provider-specific services (quirks, credentials), use ProviderRegistry:
 * ```kotlin
 * val quirks = providerRegistry.getQuirks(account.provider)
 * val credentials = providerRegistry.getCredentialProvider(account.provider)
 * ```
 *
 * @see org.onekash.kashcal.sync.provider.ProviderRegistry
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    /**
     * Bind CalDavClientFactory interface to OkHttp implementation.
     *
     * Use this factory for multi-account sync scenarios. Each call to
     * createClient() returns an isolated client with immutable credentials,
     * preventing credential race conditions during concurrent sync.
     */
    @Binds
    @Singleton
    abstract fun bindCalDavClientFactory(impl: OkHttpCalDavClientFactory): CalDavClientFactory

    /**
     * Bind CalDavQuirks interface to iCloud implementation.
     *
     * Required for OkHttpCalDavClient's @Inject constructor (test compatibility).
     * Production code should use ProviderRegistry.getQuirks(provider) for multi-provider support.
     */
    @Binds
    @Singleton
    abstract fun bindCalDavQuirks(impl: ICloudQuirks): CalDavQuirks

    /**
     * Bind IcsFetcher interface to OkHttp implementation.
     *
     * Used by IcsSubscriptionRepository for fetching ICS calendar feeds.
     */
    @Binds
    @Singleton
    abstract fun bindIcsFetcher(impl: OkHttpIcsFetcher): IcsFetcher
}
