package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.ics.IcsFetcher
import org.onekash.kashcal.data.ics.OkHttpIcsFetcher
import org.onekash.kashcal.sync.auth.CredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import javax.inject.Singleton

/**
 * Hilt module providing sync layer dependencies.
 *
 * Binds interfaces to their implementations for:
 * - CalDAV HTTP client
 * - ICS fetcher
 *
 * NOTE: CalDavQuirks and CredentialProvider bindings are deprecated.
 * New code should use ProviderRegistry to get provider-specific implementations:
 *
 * ```kotlin
 * val provider = providerRegistry.getProviderForAccount(account)
 * val quirks = provider?.getQuirks()
 * val credentials = provider?.getCredentialProvider()?.getCredentials(accountId)
 * ```
 *
 * The legacy bindings remain for backward compatibility during migration.
 * They will be removed once all consumers use ProviderRegistry.
 *
 * @see org.onekash.kashcal.sync.provider.ProviderRegistry
 * @see org.onekash.kashcal.di.ProviderModule
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    /**
     * Bind CalDavClient interface to OkHttp implementation.
     *
     * @deprecated For multi-account sync, use CalDavClientFactory instead.
     * This singleton binding is kept for backward compatibility with tests
     * and single-account scenarios.
     *
     * @see CalDavClientFactory
     */
    @Deprecated(
        message = "Use CalDavClientFactory for multi-account sync to avoid credential race conditions",
        replaceWith = ReplaceWith("calDavClientFactory.createClient(credentials, quirks)")
    )
    @Binds
    @Singleton
    abstract fun bindCalDavClient(impl: OkHttpCalDavClient): CalDavClient

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
     * @deprecated Use ProviderRegistry.getProviderForAccount(account)?.getQuirks() instead.
     * This binding will be removed once all consumers migrate to ProviderRegistry.
     *
     * @see org.onekash.kashcal.sync.provider.ProviderRegistry
     */
    @Deprecated(
        message = "Use ProviderRegistry.getProviderForAccount().getQuirks() instead",
        replaceWith = ReplaceWith("providerRegistry.getProviderForAccount(account)?.getQuirks()")
    )
    @Binds
    @Singleton
    abstract fun bindCalDavQuirks(impl: ICloudQuirks): CalDavQuirks

    /**
     * Bind CredentialProvider interface to iCloud implementation.
     *
     * @deprecated Use ProviderRegistry.getProviderForAccount(account)?.getCredentialProvider() instead.
     * This binding will be removed once all consumers migrate to ProviderRegistry.
     *
     * @see org.onekash.kashcal.sync.provider.ProviderRegistry
     */
    @Deprecated(
        message = "Use ProviderRegistry.getProviderForAccount().getCredentialProvider() instead",
        replaceWith = ReplaceWith("providerRegistry.getProviderForAccount(account)?.getCredentialProvider()")
    )
    @Binds
    @Singleton
    abstract fun bindCredentialProvider(impl: ICloudCredentialProvider): CredentialProvider

    /**
     * Bind IcsFetcher interface to OkHttp implementation.
     *
     * Used by IcsSubscriptionRepository for fetching ICS calendar feeds.
     */
    @Binds
    @Singleton
    abstract fun bindIcsFetcher(impl: OkHttpIcsFetcher): IcsFetcher
}
