package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HomeViewModel.createOfflineCalendar], the event form picker's create, over real Room and the
 * real coordinator: it adds an offline calendar and checks names against the built-in calendar's
 * translated label (here "Lokal"), not its stored English name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelOfflineCalendarCreateTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: KashCalDatabase
    private lateinit var coordinator: EventCoordinator
    private lateinit var reader: EventReader
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        reader = EventReader(db)
        coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = reader,
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            // Strict: creating a calendar never reads subscriptions or contact calendars.
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = AccountRepositoryImpl(
                accountsDao = db.accountsDao(),
                addressBookDao = db.addressBookDao(),
                calendarsDao = db.calendarsDao(),
                eventsDao = db.eventsDao(),
                pendingOperationsDao = db.pendingOperationsDao(),
                credentialManager = mockk(),
                reminderScheduler = mockk(relaxed = true),
                workManager = mockk(relaxed = true),
                contactSystemAccountRegistrar = mockk(relaxed = true),
                contactsProviderRepository = mockk(),
            ),
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = TestDataStoreFactory.createDefault(),
        )
        val device = FakeCalendarProviderRepository()
        val factory = DeviceHomeViewModelTestFactory(eventCoordinator = coordinator, eventReader = reader)
        // The translated built-in name, as a German phone has it.
        every { factory.context.getString(R.string.calendar_local) } returns "Lokal"
        viewModel = factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun refusalOf(result: Result<*>) = (result.exceptionOrNull() as? LocalCalendarException)?.refusal

    @Test
    fun `creating adds an offline calendar and returns its id`() = runTest(dispatcher) {
        val id = viewModel.createOfflineCalendar("Trips", 0).getOrThrow()
        val calendar = db.calendarsDao().getById(id)!!
        assertEquals("Trips", calendar.displayName)
        assertEquals(AccountProvider.LOCAL, db.accountsDao().getById(calendar.accountId)!!.provider)
    }

    @Test
    fun `names are checked against the translated built-in name`() = runTest(dispatcher) {
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(viewModel.createOfflineCalendar("Lokal", 0)))
        assertTrue(viewModel.createOfflineCalendar("Local", 0).isSuccess)
    }
}
