package org.onekash.kashcal.sync.provider.caldav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.onekash.kashcal.sync.discovery.DiscoveredCalendar
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Signing in again to a CalDAV account the app already has (#370, #371), over real Room. With a
 * store that can't be opened (no Android Keystore under Robolectric), every credential save
 * returns false; the sign-in fails but the existing account, its calendar and its events,
 * including an edit not yet pushed, stay unchanged. With a working store the same sign-in updates
 * the existing account in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SignInWithUnavailableCredentialStoreTest {

    private lateinit var db: KashCalDatabase
    private lateinit var context: Context

    private val username = "user"
    private val server = "https://nextcloud.example.com"
    private val homeSet = "$server/remote.php/dav/calendars/user/"
    private val calendarUrl = "${homeSet}personal/"
    private val principalUrl = "$server/remote.php/dav/principals/users/user/"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun service(credentialManager: CredentialManager): CalDavAccountDiscoveryService {
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
        return CalDavAccountDiscoveryService(
            mockk(relaxed = true), accounts, CalendarRepositoryImpl(db.calendarsDao())
        )
    }

    private suspend fun seedAccount(isEnabled: Boolean): Pair<Account, Long> {
        val accountId = db.accountsDao().insert(
            Account(
                provider = AccountProvider.CALDAV,
                email = username,
                displayName = "Home server",
                principalUrl = "$server/remote.php/dav/principals/old/",
                homeSetUrl = homeSet,
                isEnabled = isEnabled,
            )
        )
        val calendarId = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = calendarUrl, displayName = "Personal", color = 0xFF0000)
        )
        db.eventsDao().insert(event("synced", calendarId, SyncStatus.SYNCED))
        db.eventsDao().insert(event("not-pushed", calendarId, SyncStatus.PENDING_CREATE))
        return db.accountsDao().getById(accountId)!! to calendarId
    }

    private suspend fun signIn(credentialManager: CredentialManager) =
        service(credentialManager).createAccountWithSelectedCalendars(
            serverUrl = server,
            username = username,
            password = "new-password",
            trustInsecure = false,
            principalUrl = principalUrl,
            calendarHomeUrl = homeSet,
            selectedCalendars = listOf(DiscoveredCalendar(href = calendarUrl, displayName = "Personal", color = 0xFF0000)),
        )

    @Test
    fun `a failed sign-in to an existing account keeps the account, its calendar and its events`() = runBlocking {
        val (before, calendarId) = seedAccount(isEnabled = true)

        val result = signIn(UnifiedCredentialManager(context))

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
    fun `a failed sign-in leaves a disabled existing account disabled`() = runBlocking {
        val (before, _) = seedAccount(isEnabled = false)

        signIn(UnifiedCredentialManager(context))

        assertEquals(before, db.accountsDao().getById(before.id))
    }

    @Test
    fun `a successful sign-in to an existing account updates it in place`() = runBlocking {
        val (before, calendarId) = seedAccount(isEnabled = false)
        val credentials = FakeCredentialManager()

        val result = signIn(credentials)

        assertTrue("got $result", result is DiscoveryResult.Success)
        assertEquals(before.id, (result as DiscoveryResult.Success).account.id)
        val after = db.accountsDao().getById(before.id)!!
        assertEquals(principalUrl, after.principalUrl)
        assertEquals(homeSet, after.homeSetUrl)
        assertTrue("re-adding enables the account", after.isEnabled)
        assertEquals("Home server", after.displayName)
        assertEquals(1, db.accountsDao().getAllOnce().size)
        val saved = credentials.stored[before.id]!!
        assertEquals(username, saved.username)
        assertEquals("new-password", saved.password)
        assertEquals(server, saved.serverUrl)
        assertEquals(principalUrl, saved.principalUrl)
        assertEquals(homeSet, saved.calendarHomeSet)
        assertEquals(listOf(calendarId), db.calendarsDao().getByAccountIdOnce(before.id).map { it.id })
        assertEquals(
            setOf("synced", "not-pushed"),
            db.eventsDao().getAllMasterEventsForCalendar(calendarId).map { it.uid }.toSet(),
        )
    }

    @Test
    fun `signing in again to an account without credentials re-attaches it and keeps it as it was`() = runBlocking {
        val (before, calendarId) = seedAccount(isEnabled = false)
        val credentials = FakeCredentialManager()

        val result = service(credentials).createAccountWithSelectedCalendars(
            serverUrl = server,
            username = username,
            password = "new-password",
            trustInsecure = false,
            principalUrl = principalUrl,
            calendarHomeUrl = homeSet,
            selectedCalendars = listOf(DiscoveredCalendar(href = calendarUrl, displayName = "Personal", color = 0xFF0000)),
            displayName = "Ignored name",
            expectedAccountId = before.id,
        )

        assertTrue("got $result", result is DiscoveryResult.Success)
        val after = db.accountsDao().getById(before.id)!!
        assertEquals(before.copy(principalUrl = principalUrl), after)
        assertEquals(1, db.accountsDao().getAllOnce().size)
        assertEquals("new-password", credentials.stored[before.id]?.password)
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
