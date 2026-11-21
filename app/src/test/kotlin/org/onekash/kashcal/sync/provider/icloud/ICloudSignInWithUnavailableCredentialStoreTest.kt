package org.onekash.kashcal.sync.provider.icloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.credential.FakeCredentialManager
import org.onekash.kashcal.data.credential.UnifiedCredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Signing in again to an iCloud account the app already has (#370, #371), over real Room and a
 * mocked iCloud server. With a store that can't be opened (no Android Keystore under
 * Robolectric), every credential save returns false; the sign-in fails but the existing account,
 * its calendar and its events, including an edit not yet pushed, stay unchanged. With a working
 * store the same sign-in updates the existing account in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ICloudSignInWithUnavailableCredentialStoreTest {

    private lateinit var db: KashCalDatabase
    private lateinit var context: Context

    private val appleId = "user@example.test"
    private val principalUrl = "https://caldav.icloud.com/123/principal"
    private val homeUrl = "https://caldav.icloud.com/123/calendars"
    private val calendarUrl = "https://caldav.icloud.com/123/calendars/personal/"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun service(credentialManager: CredentialManager): ICloudAccountDiscoveryService {
        val accounts = AccountRepositoryImpl(
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
        val client: CalDavClient = mockk(relaxed = true) {
            coEvery { discoverPrincipal(any()) } returns CalDavResult.success(principalUrl)
            coEvery { discoverCalendarHome(any()) } returns CalDavResult.success(listOf(homeUrl))
            coEvery { listCalendars(any()) } returns CalDavResult.success(
                listOf(
                    CalDavCalendar(
                        href = "/123/calendars/personal/",
                        url = calendarUrl,
                        displayName = "Personal",
                        color = "#FF2196F3",
                        ctag = "ctag-1",
                        isReadOnly = false,
                    )
                )
            )
            coEvery { discoverScheduleOutboxUrl(any()) } returns CalDavResult.success(null)
            coEvery { supportsAutoSchedule(any()) } returns CalDavResult.success(false)
        }
        val factory: CalDavClientFactory = mockk { every { createClient(any(), any()) } returns client }
        return ICloudAccountDiscoveryService(
            clientFactory = factory,
            credentialProvider = mockk(relaxed = true),
            icloudQuirks = ICloudQuirks(),
            accountRepository = accounts,
            calendarRepository = CalendarRepositoryImpl(db.calendarsDao()),
        )
    }

    private suspend fun seedAccount(isEnabled: Boolean): Pair<Account, Long> {
        val accountId = db.accountsDao().insert(
            Account(
                provider = AccountProvider.ICLOUD,
                email = appleId,
                displayName = "iCloud",
                principalUrl = "https://caldav.icloud.com/123/old-principal",
                homeSetUrl = homeUrl,
                isEnabled = isEnabled,
            )
        )
        val calendarId = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calendarUrl, displayName = "Personal", color = 0xFF2196F3.toInt())
        )
        db.eventsDao().insert(event("synced", calendarId, SyncStatus.SYNCED))
        db.eventsDao().insert(event("not-pushed", calendarId, SyncStatus.PENDING_CREATE))
        return db.accountsDao().getById(accountId)!! to calendarId
    }

    @Test
    fun `a failed sign-in to an existing iCloud account keeps the account, its calendar and its events`() = runBlocking {
        val (before, calendarId) = seedAccount(isEnabled = false)

        val result = service(UnifiedCredentialManager(context)).discoverAndCreateAccount(appleId, "abcd-efgh-ijkl-mnop")

        assertTrue("sign-in reports the failed save, got $result", result is DiscoveryResult.Error)
        assertEquals("existing account row unchanged", before, db.accountsDao().getById(before.id))
        assertEquals("calendar kept", listOf(calendarId), db.calendarsDao().getByAccountIdOnce(before.id).map { it.id })
        assertEquals(
            "both events kept",
            setOf("synced", "not-pushed"),
            db.eventsDao().getAllMasterEventsForCalendar(calendarId).map { it.uid }.toSet(),
        )
    }

    @Test
    fun `a successful sign-in to an existing iCloud account updates it in place`() = runBlocking {
        val (before, calendarId) = seedAccount(isEnabled = false)
        val credentials = FakeCredentialManager()

        val result = service(credentials).discoverAndCreateAccount(appleId, "abcd-efgh-ijkl-mnop")

        assertTrue("got $result", result is DiscoveryResult.Success)
        assertEquals(before.id, (result as DiscoveryResult.Success).account.id)
        val after = db.accountsDao().getById(before.id)!!
        assertEquals(principalUrl, after.principalUrl)
        assertEquals(homeUrl, after.homeSetUrl)
        assertTrue("re-adding enables the account", after.isEnabled)
        assertEquals(1, db.accountsDao().getAllOnce().size)
        assertEquals(appleId, credentials.stored[before.id]?.username)
        assertEquals("abcd-efgh-ijkl-mnop", credentials.stored[before.id]?.password)
        assertEquals(
            setOf("synced", "not-pushed"),
            db.eventsDao().getAllMasterEventsForCalendar(calendarId).map { it.uid }.toSet(),
        )
    }

    private fun event(uid: String, calendarId: Long, status: SyncStatus) = Event(
        uid = uid,
        calendarId = calendarId,
        title = uid,
        startTs = 1_800_000_000_000,
        endTs = 1_800_003_600_000,
        dtstamp = 1_800_000_000_000,
        syncStatus = status,
    )
}
