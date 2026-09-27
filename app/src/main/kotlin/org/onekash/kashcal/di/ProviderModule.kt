package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService
import javax.inject.Singleton

/**
 * Binds the account discovery service. Provider-specific quirks and credentials aren't Hilt
 * bindings: [org.onekash.kashcal.sync.provider.ProviderRegistry] looks them up by
 * [org.onekash.kashcal.domain.model.AccountProvider], and generic CalDAV quirks by the
 * account's server URL.
 *
 * @see org.onekash.kashcal.sync.provider.ProviderRegistry
 * @see org.onekash.kashcal.domain.model.AccountProvider
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    /**
     * Binds [AccountDiscoveryService] to the iCloud implementation. Generic CalDAV discovery,
     * [org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService], doesn't
     * implement the interface and is injected by its class.
     */
    @Binds
    @Singleton
    abstract fun bindAccountDiscoveryService(impl: ICloudAccountDiscoveryService): AccountDiscoveryService
}
