package org.onekash.kashcal.data.contacts

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests [ContactEventManager] over a real DataStore and a relaxed [EventCoordinator]: the
 * startup sync per enabled feature, turning both features off when READ_CONTACTS is revoked,
 * and that initialize and the disable calls don't throw. Observer registration isn't asserted.
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
        // READ_CONTACTS is granted unless a test denies it.
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

    // ========== Birthdays and permission revocation ==========

    @Test
    fun `initialize with disabled does not register observer`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // Passes if nothing throws.
    }

    @Test
    fun `onBirthdaysDisabled is safe to call without prior onEnabled`() = runTest(testDispatcher) {
        manager.onBirthdaysDisabled()
        // Passes if nothing throws.
    }

    @Test
    fun `initialize with enabled but no permission does not crash and auto-disables feature`() = runTest(testDispatcher) {
        // Birthdays were enabled, then READ_CONTACTS was revoked in system settings.
        dataStore.setContactBirthdaysEnabled(true)

        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)

        // A fresh manager; the grant is read on every call, not cached.
        manager = ContactEventManager(context, dataStore, eventCoordinator)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Both features are turned off and both calendars removed (the anniversary setting
        // isn't asserted).
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
        // Passes if nothing throws; the observer registration isn't asserted.
    }

    @Test
    fun `initialize enables observer when both enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // Passes if nothing throws; the observer registration isn't asserted.
    }

    @Test
    fun `onAnniversariesDisabled keeps observer if birthdays still enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Birthdays are still on, so the observer stays (not asserted).
        manager.onAnniversariesDisabled()
        testDispatcher.scheduler.advanceUntilIdle()
        // Passes if nothing throws.
    }

    @Test
    fun `onBirthdaysDisabled keeps observer if anniversaries still enabled`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Anniversaries are still on, so the observer stays (not asserted).
        manager.onBirthdaysDisabled()
        testDispatcher.scheduler.advanceUntilIdle()
        // Passes if nothing throws.
    }

    @Test
    fun `onAnniversariesDisabled plus onBirthdaysDisabled unregisters observer`() = runTest(testDispatcher) {
        dataStore.setContactBirthdaysEnabled(true)
        dataStore.setContactAnniversariesEnabled(true)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()

        // Both off: the observer is unregistered and the worker cancelled (not asserted).
        dataStore.setContactBirthdaysEnabled(false)
        dataStore.setContactAnniversariesEnabled(false)
        manager.onBirthdaysDisabled()
        manager.onAnniversariesDisabled()
        testDispatcher.scheduler.advanceUntilIdle()
        // Passes if nothing throws.
    }

    @Test
    fun `onAnniversariesDisabled is safe to call without prior onEnabled`() = runTest(testDispatcher) {
        manager.onAnniversariesDisabled()
        // Passes if nothing throws.
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

        // READ_CONTACTS revoked.
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
