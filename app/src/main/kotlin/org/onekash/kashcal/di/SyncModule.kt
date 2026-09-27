package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.ics.IcsFetcher
import org.onekash.kashcal.data.ics.OkHttpIcsFetcher
import org.onekash.kashcal.sync.carddav.CardDavClientFactory
import org.onekash.kashcal.sync.carddav.OkHttpCardDavClientFactory
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import javax.inject.Singleton

/**
 * Binds the CalDAV and CardDAV client factories, the default [CalDavQuirks] and the ICS fetcher.
 *
 * For provider-specific quirks and credentials, use ProviderRegistry:
 * ```kotlin
 * val quirks = providerRegistry.getQuirksForAccount(account)
 * val credentials = providerRegistry.getCredentialProvider(account.provider)
 * ```
 *
 * @see org.onekash.kashcal.sync.provider.ProviderRegistry
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    /** Binds [CalDavClientFactory], which holds the one-client-per-account rule. */
    @Binds
    @Singleton
    abstract fun bindCalDavClientFactory(impl: OkHttpCalDavClientFactory): CalDavClientFactory

    /**
     * Binds [CardDavClientFactory], the CardDAV sibling of [bindCalDavClientFactory]: each
     * `createClient()` call returns a contact-sync client with its own fixed credentials.
     */
    @Binds
    @Singleton
    abstract fun bindCardDavClientFactory(impl: OkHttpCardDavClientFactory): CardDavClientFactory

    /**
     * Binds [CalDavQuirks] to iCloud's. `PullStrategy` injects it as the fallback when a pull
     * gets no quirks, and `OkHttpCalDavClient`'s `@Inject` constructor (for tests) takes it.
     * Production code picks quirks per provider through `ProviderRegistry`.
     */
    @Binds
    @Singleton
    abstract fun bindCalDavQuirks(impl: ICloudQuirks): CalDavQuirks

    /** Binds [IcsFetcher], which `IcsSubscriptionRepository` fetches ICS feeds through. */
    @Binds
    @Singleton
    abstract fun bindIcsFetcher(impl: OkHttpIcsFetcher): IcsFetcher
}
