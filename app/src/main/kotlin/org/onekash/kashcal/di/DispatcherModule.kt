package org.onekash.kashcal.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * Qualifier for IO dispatcher (disk/network operations).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for Default dispatcher (CPU-intensive work).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for Main dispatcher (UI operations).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Hilt module providing coroutine dispatchers.
 *
 * Using qualifiers allows easy testing by replacing with TestDispatcher.
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

    /**
     * Provide IO dispatcher for disk and network operations.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provide Default dispatcher for CPU-intensive work.
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provide Main dispatcher for UI operations.
     */
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
