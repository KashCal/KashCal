package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for the user-initials DataStore preference backing the avatar hub.
 *
 * Covers: default (empty when absent), round-trip, set-then-clear reverts to
 * empty (so the avatar falls back to its generic glyph), and a stable key
 * identifier (so an upgrade doesn't silently reset the user's initials).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class UserInitialsPreferenceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_user_initials_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    @Test
    fun `userInitials defaults to empty when key absent`() = runTest {
        dataStore.userInitials.test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userInitials round-trips`() = runTest {
        dataStore.setUserInitials("AB")
        assertEquals("AB", dataStore.userInitials.first())
    }

    @Test
    fun `userInitials set then clear reverts to empty`() = runTest {
        dataStore.setUserInitials("AB")
        assertEquals("AB", dataStore.userInitials.first())

        dataStore.setUserInitials("")
        assertEquals("", dataStore.userInitials.first())
    }

    @Test
    fun `userInitials uses a stable key identifier`() = runTest {
        dataStore.setUserInitials("KC")
        val prefs = dataStore.dataStore.data.first()
        assertEquals("KC", prefs[stringPreferencesKey("user_initials")])
    }
}
