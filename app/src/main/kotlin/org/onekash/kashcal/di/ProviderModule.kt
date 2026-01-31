package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.sync.discovery.AccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService
import javax.inject.Singleton

/**
 * Hilt module for calendar provider registration.
 *
 * With the AccountProvider enum refactor, provider lookup is now
 * handled directly by ProviderRegistry using the enum.
 * No more @IntoSet pattern needed.
 *
 * @see org.onekash.kashcal.sync.provider.ProviderRegistry
 * @see org.onekash.kashcal.domain.model.AccountProvider
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    /**
     * Bind AccountDiscoveryService interface to iCloud implementation.
     *
     * Currently only iCloud is supported. When more providers are added,
     * this could be changed to use @IntoSet or a discovery registry pattern.
     */
    @Binds
    @Singleton
    abstract fun bindAccountDiscoveryService(impl: ICloudAccountDiscoveryService): AccountDiscoveryService
}
