package org.onekash.kashcal.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.network.dns.AndroidRawDnsChannel
import org.onekash.kashcal.network.dns.RawDnsChannel
import org.onekash.kashcal.network.dns.SrvResolver
import org.onekash.kashcal.network.dns.SrvResolverImpl
import org.onekash.kashcal.network.dns.TxtResolver
import org.onekash.kashcal.network.dns.TxtResolverImpl
import org.onekash.kashcal.sync.carddav.CardDavHostResolver
import javax.inject.Singleton

/**
 * Provides the RFC 6764 DNS discovery seam: the platform [RawDnsChannel] adapter
 * and the pure SRV/TXT resolvers layered over it.
 *
 * The resolver impls take plain (non-`@Inject`) constructors on purpose — they are
 * pure classes unit-tested against a fake channel and carry an injected `rng` /
 * default that Hilt shouldn't have to reason about — so they are wired here with
 * explicit `@Provides` rather than constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object DnsModule {

    @Provides
    @Singleton
    fun provideRawDnsChannel(): RawDnsChannel = AndroidRawDnsChannel()

    @Provides
    @Singleton
    fun provideSrvResolver(channel: RawDnsChannel): SrvResolver = SrvResolverImpl(channel)

    @Provides
    @Singleton
    fun provideTxtResolver(channel: RawDnsChannel): TxtResolver = TxtResolverImpl(channel)

    /**
     * Wired with `@Provides` (not constructor injection) for the same reason as the
     * resolvers above: it takes a defaulted [org.onekash.kashcal.sync.carddav.RegistrableDomainResolver]
     * function-type parameter that Hilt can't reason about, so the production default
     * is supplied here and the class keeps a single test-friendly constructor.
     */
    @Provides
    @Singleton
    fun provideCardDavHostResolver(
        srvResolver: SrvResolver,
        txtResolver: TxtResolver,
    ): CardDavHostResolver = CardDavHostResolver(srvResolver, txtResolver)
}
