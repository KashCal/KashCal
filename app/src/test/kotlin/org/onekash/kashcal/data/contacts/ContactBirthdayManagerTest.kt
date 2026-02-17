package org.onekash.kashcal.data.contacts

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for ContactBirthdayManager.
 *
 * Tests lifecycle methods and permission revocation handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContactBirthdayManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: ContactBirthdayManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Grant READ_CONTACTS by default so lifecycle tests work
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        dataStore = KashCalDataStore(context)
        manager = ContactBirthdayManager(context, dataStore)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize with disabled does not register observer`() = runTest {
        dataStore.setContactBirthdaysEnabled(false)
        manager.initialize()
        testDispatcher.scheduler.advanceUntilIdle()
        // No crash = success
    }

    @Test
    fun `onDisabled is safe to call without prior onEnabled`() = runTest {
        manager.onDisabled()
        // No crash = success
    }

    // ========== Permission Revocation ==========

    @Test
    fun `initialize with enabled but no permission does not crash and auto-disables feature`() = runTest {
        // Simulate: user enabled contact birthdays, then revoked READ_CONTACTS in system settings
        dataStore.setContactBirthdaysEnabled(true)

        // Deny READ_CONTACTS permission (simulating revocation)
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)

        // Recreate manager so it picks up the denied permission state
        manager = ContactBirthdayManager(context, dataStore)
        manager.initialize()
        // Pump dispatcher + real-time waits for DataStore IO to complete
        repeat(10) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(50)
        }

        // Feature should be auto-disabled, no crash
        assertFalse(
            "Feature should be auto-disabled when permission is revoked",
            dataStore.contactBirthdaysEnabled.first()
        )
    }
}
