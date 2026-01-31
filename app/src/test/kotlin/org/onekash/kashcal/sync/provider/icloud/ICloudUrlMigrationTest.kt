package org.onekash.kashcal.sync.provider.icloud

import android.util.Log
import androidx.room.Room
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration tests for ICloudUrlMigration.
 *
 * Uses in-memory Room database to test actual migration logic.
 * Tests verify:
 * - Migration normalizes regional URLs to canonical form
 * - Migration only affects iCloud accounts/calendars/events
 * - Migration runs only once (idempotent)
 * - Migration handles edge cases (empty database, already-canonical URLs)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ICloudUrlMigrationTest {

    private lateinit var database: KashCalDatabase
    private lateinit var dataStore: KashCalDataStore
    private lateinit var migration: ICloudUrlMigration

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            KashCalDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        dataStore = mockk(relaxed = true)
        coEvery { dataStore.getICloudUrlMigrationCompleted() } returns false
        coEvery { dataStore.setICloudUrlMigrationCompleted(any()) } just Runs

        migration = ICloudUrlMigration(database, dataStore)
    }

    @After
    fun teardown() {
        database.close()
        unmockkAll()
    }

    // ==================== Basic Migration Tests ====================

    @Test
    fun `migration skips if already completed`() = runTest {
        coEvery { dataStore.getICloudUrlMigrationCompleted() } returns true

        val result = migration.migrateIfNeeded()

        assertFalse(result)
        coVerify(exactly = 0) { dataStore.setICloudUrlMigrationCompleted(any()) }
    }

    @Test
    fun `migration runs if not completed`() = runTest {
        // Empty database, but migration should still mark as complete
        val result = migration.migrateIfNeeded()

        assertTrue(result)
        coVerify { dataStore.setICloudUrlMigrationCompleted(true) }
    }

    @Test
    fun `migration handles empty database`() = runTest {
        val result = migration.migrateIfNeeded()

        assertTrue(result)
        coVerify { dataStore.setICloudUrlMigrationCompleted(true) }
    }

    // ==================== Account Migration Tests ====================

    @Test
    fun `migration normalizes account homeSetUrl`() = runTest {
        // Setup: Create account with regional URL
        val accountId = database.accountsDao().insert(
            createAccount(
                homeSetUrl = "https://p180-caldav.icloud.com:443/12345/calendars/"
            )
        )

        migration.migrateIfNeeded()

        val updated = database.accountsDao().getById(accountId)
        assertEquals(
            "https://caldav.icloud.com/12345/calendars/",
            updated?.homeSetUrl
        )
    }

    @Test
    fun `migration normalizes account principalUrl if absolute`() = runTest {
        val accountId = database.accountsDao().insert(
            createAccount(
                principalUrl = "https://p180-caldav.icloud.com:443/12345/principal/",
                homeSetUrl = "https://p180-caldav.icloud.com:443/12345/calendars/"
            )
        )

        migration.migrateIfNeeded()

        val updated = database.accountsDao().getById(accountId)
        assertEquals(
            "https://caldav.icloud.com/12345/principal/",
            updated?.principalUrl
        )
        assertEquals(
            "https://caldav.icloud.com/12345/calendars/",
            updated?.homeSetUrl
        )
    }

    @Test
    fun `migration skips non-icloud accounts`() = runTest {
        // Create a local account (non-iCloud)
        val localAccountId = database.accountsDao().insert(
            Account(
                provider = AccountProvider.LOCAL,
                email = "local",
                displayName = "Local Calendar",
                principalUrl = null,
                homeSetUrl = null,
                isEnabled = true
            )
        )

        migration.migrateIfNeeded()

        // Local account should be unchanged
        val localAccount = database.accountsDao().getById(localAccountId)
        assertNotNull(localAccount)
        assertNull(localAccount?.homeSetUrl)
    }

    // ==================== Calendar Migration Tests ====================

    @Test
    fun `migration normalizes calendar caldavUrl`() = runTest {
        val accountId = database.accountsDao().insert(createAccount())
        val calendarId = database.calendarsDao().insert(
            createCalendar(
                accountId = accountId,
                caldavUrl = "https://p180-caldav.icloud.com:443/12345/calendars/ABC123/"
            )
        )

        migration.migrateIfNeeded()

        val updated = database.calendarsDao().getById(calendarId)
        assertEquals(
            "https://caldav.icloud.com/12345/calendars/ABC123/",
            updated?.caldavUrl
        )
    }

    // ==================== Event Migration Tests ====================

    @Test
    fun `migration normalizes event caldavUrl`() = runTest {
        val accountId = database.accountsDao().insert(createAccount())
        val calendarId = database.calendarsDao().insert(createCalendar(accountId = accountId))
        val eventId = database.eventsDao().insert(
            createEvent(
                calendarId = calendarId,
                caldavUrl = "https://p180-caldav.icloud.com:443/12345/calendars/ABC/event.ics"
            )
        )

        migration.migrateIfNeeded()

        val updated = database.eventsDao().getById(eventId)
        assertEquals(
            "https://caldav.icloud.com/12345/calendars/ABC/event.ics",
            updated?.caldavUrl
        )
    }

    @Test
    fun `migration skips already canonical event URLs`() = runTest {
        val accountId = database.accountsDao().insert(createAccount())
        val calendarId = database.calendarsDao().insert(createCalendar(accountId = accountId))
        val canonicalUrl = "https://caldav.icloud.com/12345/calendars/ABC/event.ics"
        val eventId = database.eventsDao().insert(
            createEvent(
                calendarId = calendarId,
                caldavUrl = canonicalUrl
            )
        )

        migration.migrateIfNeeded()

        val updated = database.eventsDao().getById(eventId)
        assertEquals(canonicalUrl, updated?.caldavUrl)
    }

    // ==================== PendingOperation Migration Tests ====================

    @Test
    fun `migration normalizes pendingOperation targetUrl`() = runTest {
        val accountId = database.accountsDao().insert(createAccount())
        val calendarId = database.calendarsDao().insert(createCalendar(accountId = accountId))
        val eventId = database.eventsDao().insert(createEvent(calendarId = calendarId))
        val opId = database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_UPDATE,
                targetUrl = "https://p180-caldav.icloud.com:443/12345/calendars/ABC/event.ics"
            )
        )

        migration.migrateIfNeeded()

        val updated = database.pendingOperationsDao().getById(opId)
        assertEquals(
            "https://caldav.icloud.com/12345/calendars/ABC/event.ics",
            updated?.targetUrl
        )
    }

    // ==================== Idempotency Tests ====================

    @Test
    fun `migration runs only once`() = runTest {
        // First call
        migration.migrateIfNeeded()
        coVerify(exactly = 1) { dataStore.setICloudUrlMigrationCompleted(true) }

        // Simulate completion flag now set
        coEvery { dataStore.getICloudUrlMigrationCompleted() } returns true

        // Second call should be a no-op
        val result = migration.migrateIfNeeded()
        assertFalse(result)
    }

    // ==================== Helper Methods ====================

    private fun createAccount(
        principalUrl: String? = "/12345/principal/",
        homeSetUrl: String? = "https://caldav.icloud.com/12345/calendars/"
    ) = Account(
        provider = AccountProvider.ICLOUD,
        email = "test@icloud.com",
        displayName = "iCloud",
        principalUrl = principalUrl,
        homeSetUrl = homeSetUrl,
        isEnabled = true
    )

    private fun createCalendar(
        accountId: Long,
        caldavUrl: String = "https://caldav.icloud.com/12345/calendars/ABC123/"
    ) = Calendar(
        accountId = accountId,
        caldavUrl = caldavUrl,
        displayName = "Test Calendar",
        color = 0xFF4CAF50.toInt(),
        isVisible = true,
        isDefault = false,
        isReadOnly = false
    )

    private fun createEvent(
        calendarId: Long,
        caldavUrl: String? = null
    ) = Event(
        calendarId = calendarId,
        uid = "test-uid-${System.nanoTime()}",
        title = "Test Event",
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600_000,
        isAllDay = false,
        dtstamp = System.currentTimeMillis(),
        caldavUrl = caldavUrl,
        syncStatus = SyncStatus.SYNCED
    )
}
