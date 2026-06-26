package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Locks in the DataStore invariant that "insights" cannot be persisted as
 * the user's default calendar view. This is the precondition for
 * [HomeViewModelInsightsBackStackTest] — if a user could ever have their
 * persisted default set to "insights", the back-from-Insights seed at
 * VM init would itself be INSIGHTS, and back from Insights as initial
 * view would loop forever instead of returning to a real view.
 *
 * VALID_VIEWS in [KashCalDataStore] is the source of truth; this test
 * guards against future drift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class KashCalDataStoreInvariantTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(
            context.filesDir,
            "test_prefs_invariant_${System.nanoTime()}.preferences_pb"
        )
        val backing = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, backing)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setDefaultCalendarView rejects insights`() = runTest {
        dataStore.setDefaultCalendarView("insights")
    }

    @Test
    fun `setDefaultCalendarView accepts all real view keys`() = runTest {
        // Each real key is accepted (require() doesn't fire) AND persisted.
        for (view in listOf("month", "agenda", "day", "three_days", "week", "month_full", "year")) {
            dataStore.setDefaultCalendarView(view)
            assertEquals(view, dataStore.getDefaultCalendarView())
        }
    }
}
