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
 * Qualifier for the process-lifetime CoroutineScope. Use for fire-and-forget
 * work that must outlive the ViewModel that initiated it (e.g., the deferred
 * commit of a delete-with-undo flow when the user exits the Activity before
 * the snackbar times out — see issue #133).
 *
 * Unlike viewModelScope, this scope is NOT cancelled when an Activity or
 * ViewModel is destroyed. It dies only with the process.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

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

    /**
     * Provide the process-lifetime CoroutineScope.
     *
     * Used for fire-and-forget startup migrations and for the deferred
     * commit of delete-with-undo flows that must outlive their Activity
     * (issue #133).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
