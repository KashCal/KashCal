package org.onekash.kashcal.data.contacts

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.coVerify
import io.mockk.mockk
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for ContactEventManager.
 *
 * Tests lifecycle methods, permission revocation handling, and
 * combined birthday + anniversary observer management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactEventManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File
    private lateinit var eventCoordinator: EventCoordinator
    private lateinit var manager: ContactEventManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Grant READ_CONTACTS by default so lifecycle tests work
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        eventCoordinator = mockk(relaxed = true)
        manager = ContactEventManager(context, dataStore, eventCoordinator)
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    // ========== Original Birthday Tests ==========

    @Test
    fun `initialize with disabled does not register observer`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success
    }

    @Test
    fun `onBirthdaysDisabled is safe to call without prior onEnabled`() = runTest(testDispatcher) {
        manager.onBirthdaysDisabled()
        // No crash = success
    }

    @Test
    fun `initialize with enabled but no permission does not crash and auto-disables feature`() = runTest(testDispatcher) {
        // Simulate: user enabled contact birthdays, then revoked READ_CONTACTS in system settings
        dataStore.setContactBirthdaysEnabled(true)

        // Deny READ_CONTACTS permission (simulating revocation)
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)

        // Recreate manager so it picks up the denied permission state
        manager = ContactEventManager(context, dataStore, eventCoordinator)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Feature should be auto-disabled and calendar cleaned up
        assertFalse(
            "Feature should be auto-disabled when permission is revoked",
            dataStore.contactBirthdaysEnabled.first()
        )
        coVerify { eventCoordinator.disableContactBirthdays() }
        coVerify { eventCoordinator.disableContactAnniversaries() }
    }

    // ========== Anniversary Observer Tests ==========

    @Test
    fun `initialize enables observer when only anniversaries enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success; observer is registered because anniversaries are enabled
    }

    @Test
    fun `initialize enables observer when both enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success; observer is registered
    }

    @Test
    fun `onAnniversariesDisabled keeps observer if birthdays still enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Disable anniversaries - observer should remain because birthdays is still on
        manager.onAnniversariesDisabled()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success
    }

    @Test
    fun `onBirthdaysDisabled keeps observer if anniversaries still enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Disable birthdays - observer should remain because anniversaries is still on
        manager.onBirthdaysDisabled()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success
    }

    @Test
    fun `onAnniversariesDisabled plus onBirthdaysDisabled unregisters observer`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Disable both - observer should be unregistered and work cancelled
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(false)
        manager.onBirthdaysDisabled()
        manager.onAnniversariesDisabled()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success
    }

    @Test
    fun `onAnniversariesDisabled is safe to call without prior onEnabled`() = runTest(testDispatcher) {
        manager.onAnniversariesDisabled()
        // No crash = success
    }

    // ========== Startup Sync Tests ==========

    @Test
    fun `initialize with birthdays enabled calls syncContactBirthdays`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { eventCoordinator.syncContactBirthdays() }
    }

    @Test
    fun `initialize with anniversaries enabled calls syncContactAnniversaries`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { eventCoordinator.syncContactAnniversaries() }
    }

    @Test
    fun `initialize with both disabled does not call sync`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { eventCoordinator.syncContactBirthdays() }
        coVerify(exactly = 0) { eventCoordinator.syncContactAnniversaries() }
    }

    @Test
    fun `initialize with anniversaries enabled but no permission auto-disables`() = runTest(testDispatcher) {
        dataStore.setContactAnniversariesEnabled(true)

        // Deny READ_CONTACTS permission
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)

        manager = ContactEventManager(context, dataStore, eventCoordinator)
        manager.initialize()
        repeat(10) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(50)
        }

        assertFalse(
            "Anniversaries should be auto-disabled when permission is revoked",
            dataStore.contactAnniversariesEnabled.first()
        )
        coVerify { eventCoordinator.disableContactAnniversaries() }
        coVerify { eventCoordinator.disableContactBirthdays() }
    }
}
