package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudAccountDiscoveryService
import org.onekash.kashcal.sync.provider.icloud.ICloudCredentialProvider
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Quick Add after the default calendar stops being writable (#388). The user picks a synced
 * calendar as the default in Settings; later the calendar row goes away or turns read-only, and
 * Quick Add must still save into a calendar that exists and accepts events.
 *
 * Each case reaches that state through the code that does it in the app: signing out
 * ([AccountRepositoryImpl.deleteAccount]) and a calendar refresh
 * ([CalDavAccountDiscoveryService.refreshCalendars],
 * [ICloudAccountDiscoveryService.refreshCalendars]) after the calendar is deleted on the server
 * or the user loses write access to it. Server replies and credentials are stubbed and the
 * side-effect collaborators are mocks; Room (with foreign keys), the repositories, the
 * coordinator, the writer and the preferences store are real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QuickAddStaleDefaultCalendarTest {

    private lateinit var db: KashCalDatabase
    private lateinit var dataStore: KashCalDataStore
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var calendars: CalendarRepositoryImpl
    private lateinit var viewModel: QuickAddViewModel
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File

    private val server: CalDavClient = mockk()
    private val clientFactory: CalDavClientFactory = mockk {
        every { createClient(any(), any()) } returns server
    }
    private val credentialManager: CredentialManager = mockk(relaxed = true)

    private val homeUrl = "https://dav.example.test/cal/"
    private val defaultUrl = "${homeUrl}personal/"
    private val otherUrl = "${homeUrl}work/"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "quickadd_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(
            context,
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile }
        )
        coEvery { credentialManager.getCredentials(any()) } returns AccountCredentials(
            username = "testuser1",
            password = "testpass1",
            serverUrl = "https://dav.example.test"
        )
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), dataStore)
        accounts = AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            credentialManager = credentialManager,
            reminderScheduler = mockk(relaxed = true),
            workManager = mockk(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true),
        )
        calendars = CalendarRepositoryImpl(db.calendarsDao())
        val coordinator = EventCoordinator(
            eventWriter = EventWriter(db, generator),
            eventReader = EventReader(db),
            occurrenceGenerator = generator,
            localCalendarInitializer = LocalCalendarInitializer(db),
            icsSubscriptionRepository = mockk(),
            contactBirthdayRepository = mockk(),
            contactAnniversaryRepository = mockk(),
            accountRepository = accounts,
            syncScheduler = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            widgetUpdateManager = mockk(relaxed = true),
            inviteNotifier = mockk(relaxed = true),
            icsRefreshScheduleReconciler = mockk(relaxed = true),
            dataStore = dataStore,
        )
        viewModel = QuickAddViewModel(coordinator, dataStore, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
        Dispatchers.resetMain()
    }

    /** A synced account with two writable calendars; the first is the user's default. */
    private suspend fun seedDefault(provider: AccountProvider): Pair<Long, Long> {
        val accountId = db.accountsDao().insert(
            Account(provider = provider, email = "testuser1@example.test", homeSetUrl = homeUrl)
        )
        val defaultId = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = defaultUrl, displayName = "personal", color = 0)
        )
        db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = otherUrl, displayName = "work", color = 0)
        )
        dataStore.setDefaultCalendar(DefaultCalendar.Room(defaultId))
        return accountId to defaultId
    }

    private fun listing(url: String, readOnly: Boolean = false) = CalDavCalendar(
        href = url.removePrefix("https://dav.example.test"),
        url = url,
        displayName = url.trimEnd('/').substringAfterLast('/'),
        color = null,
        ctag = "c1",
        isReadOnly = readOnly
    )

    /** The server lists only [listed]; a direct probe of the default's URL answers [probe]. */
    private fun serverLists(vararg listed: CalDavCalendar, probe: CalDavResult<Boolean>? = null) {
        coEvery { server.listCalendars(homeUrl) } returns CalDavResult.Success(listed.toList())
        if (probe != null) coEvery { server.probeCalendarCollection(defaultUrl) } returns probe
    }

    private suspend fun refreshCalDav(accountId: Long) {
        val result = CalDavAccountDiscoveryService(clientFactory, accounts, calendars)
            .refreshCalendars(accountId)
        assertTrue("refresh failed: $result", result is DiscoveryResult.Success)
    }

    private suspend fun refreshICloud(accountId: Long) {
        val credentialProvider: ICloudCredentialProvider = mockk {
            coEvery { getCredentials(any()) } returns
                Credentials("testuser1", "testpass1", "https://dav.example.test")
        }
        val result = ICloudAccountDiscoveryService(
            clientFactory, credentialProvider, ICloudQuirks(), accounts, calendars
        ).refreshCalendars(accountId)
        assertTrue("refresh failed: $result", result is DiscoveryResult.Success)
    }

    /**
     * Saves "Coffee tomorrow at 3pm" from Quick Add. It must land in the calendar the event form
     * would preselect: the first writable one, in the order the app lists calendars, before the
     * save. With no calendar left (after a sign-out) the save creates the local calendar and
     * must use it.
     */
    private suspend fun assertQuickAddSaves() {
        val expected = db.calendarsDao().getAllOnce().firstOrNull { !it.isReadOnly }?.id
        viewModel.onInputChanged("Coffee tomorrow at 3pm")
        val result = viewModel.save()

        assertTrue("save failed: ${result.exceptionOrNull()}", result.isSuccess)
        val calendar = db.calendarsDao().getById(result.getOrThrow().calendarId)
        assertNotNull("event saved into a missing calendar", calendar)
        assertFalse("event saved into a read-only calendar", calendar!!.isReadOnly)
        val expectedId = expected ?: db.calendarsDao().getAllOnce().single().id
        assertEquals("not the form's fallback calendar", expectedId, calendar.id)
    }

    @Test
    fun `quick add saves after the default calendar's account is signed out`() = runBlocking {
        val (accountId, _) = seedDefault(AccountProvider.CALDAV)
        accounts.deleteAccount(accountId)

        assertQuickAddSaves()
    }

    @Test
    fun `quick add saves after a CalDAV refresh removes the default calendar`() = runBlocking {
        val (accountId, defaultId) = seedDefault(AccountProvider.CALDAV)
        serverLists(listing(otherUrl), probe = CalDavResult.notFoundError("gone"))
        refreshCalDav(accountId)
        assertNull("refresh kept the deleted calendar", db.calendarsDao().getById(defaultId))

        assertQuickAddSaves()
    }

    @Test
    fun `quick add saves after an iCloud refresh removes the default calendar`() = runBlocking {
        val (accountId, defaultId) = seedDefault(AccountProvider.ICLOUD)
        serverLists(listing(otherUrl), probe = CalDavResult.notFoundError("gone"))
        refreshICloud(accountId)
        assertNull("refresh kept the deleted calendar", db.calendarsDao().getById(defaultId))

        assertQuickAddSaves()
    }

    @Test
    fun `quick add saves after a CalDAV refresh makes the default calendar read-only`() = runBlocking {
        val (accountId, defaultId) = seedDefault(AccountProvider.CALDAV)
        serverLists(listing(defaultUrl, readOnly = true), listing(otherUrl))
        refreshCalDav(accountId)
        assertEquals(true, db.calendarsDao().getById(defaultId)?.isReadOnly)

        assertQuickAddSaves()
    }

    @Test
    fun `quick add saves after an iCloud refresh makes the default calendar read-only`() = runBlocking {
        val (accountId, defaultId) = seedDefault(AccountProvider.ICLOUD)
        serverLists(listing(defaultUrl, readOnly = true), listing(otherUrl))
        refreshICloud(accountId)
        assertEquals(true, db.calendarsDao().getById(defaultId)?.isReadOnly)

        assertQuickAddSaves()
    }
}
