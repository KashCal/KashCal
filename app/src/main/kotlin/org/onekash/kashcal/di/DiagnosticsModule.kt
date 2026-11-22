package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.domain.diagnostics.AndroidDebugReportPlatform
import org.onekash.kashcal.domain.diagnostics.DebugReportPlatform

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    abstract fun bindDebugReportPlatform(impl: AndroidDebugReportPlatform): DebugReportPlatform
}
