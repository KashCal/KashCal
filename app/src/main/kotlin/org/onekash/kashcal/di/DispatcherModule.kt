package org.onekash.kashcal.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the IO dispatcher, for disk and network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Qualifies the Default dispatcher, for CPU-bound work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Qualifies the Main dispatcher, for UI work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Qualifies the process-lifetime [CoroutineScope], for fire-and-forget work that must outlive
 * whatever started it: startup work launched from [org.onekash.kashcal.KashCalApplication], and
 * ViewModel work that must survive its screen closing, such as the deferred commit of a
 * delete-with-undo when the user leaves before the snackbar times out (#133).
 *
 * It isn't cancelled when an Activity or ViewModel is destroyed; it dies only with the process.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Provides the qualified coroutine dispatchers and the [ApplicationScope] scope. The qualifiers
 * let a test swap in a TestDispatcher.
 *
 * Usage in ViewModels:
 * ```
 * @HiltViewModel
 * class MyViewModel @Inject constructor(
 *     @IoDispatcher private val ioDispatcher: CoroutineDispatcher
 * ) : ViewModel()
 * ```
 *
 * Usage in tests:
 * ```
 * @BindValue
 * @IoDispatcher
 * val testDispatcher: CoroutineDispatcher = StandardTestDispatcher()
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * Provides the [ApplicationScope] scope on [Dispatchers.IO]. A [SupervisorJob] keeps one
     * failed launch from cancelling the others.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
