package org.onekash.kashcal.domain.initializer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.onekash.kashcal.testutil.insertOfflineCalendar

/**
 * Tests [LocalCalendarInitializer] over an in-memory Room database.
 *
 * Covers:
 * - the first call creates the local account and calendar, leaving other calendars untouched
 * - later calls, and [LocalCalendarInitializer.getLocalCalendarId], return the same calendar
 * - [LocalCalendarInitializer.isLocalAccount] follows the LOCAL provider alone
 * - [LocalCalendarInitializer.isLocalCalendar] matches every calendar of a LOCAL account, at any
 *   URL
 * - the calendar's color, default, writable and sort-order values, and an enabled account
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LocalCalendarInitializerTest {

    private lateinit var database: KashCalDatabase
    private lateinit var localCalendarInitializer: LocalCalendarInitializer

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
        localCalendarInitializer = LocalCalendarInitializer(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== ensureLocalCalendarExists ==========

    @Test
    fun `ensureLocalCalendarExists creates local account and calendar on first call`() = runTest {
        // Act
        val calendarId = localCalendarInitializer.ensureLocalCalendarExists()

        // Assert - calendar created
        val calendar = database.calendarsDao().getById(calendarId)
        assertNotNull(calendar)
        assertEquals(LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME, calendar!!.displayName)
        assertEquals(LocalCalendarInitializer.LOCAL_CALENDAR_URL, calendar.caldavUrl)
        assertEquals(LocalCalendarInitializer.LOCAL_CALENDAR_COLOR, calendar.color)
        assertTrue(calendar.isVisible)
        assertTrue(calendar.isDefault)
        assertFalse(calendar.isReadOnly)
        assertEquals(0, calendar.sortOrder)

        // Assert - account created
        val account = database.accountsDao().getById(calendar.accountId)
        assertNotNull(account)
        assertEquals(AccountProvider.LOCAL, account!!.provider)
        assertEquals(LocalCalendarInitializer.LOCAL_EMAIL, account.email)
        assertEquals(LocalCalendarInitializer.LOCAL_ACCOUNT_DISPLAY_NAME, account.displayName)
        assertTrue(account.isEnabled)
    }

    @Test
    fun `ensureLocalCalendarExists is idempotent - returns same calendar on multiple calls`() = runTest {
        // Act
        val firstCalendarId = localCalendarInitializer.ensureLocalCalendarExists()
        val secondCalendarId = localCalendarInitializer.ensureLocalCalendarExists()
        val thirdCalendarId = localCalendarInitializer.ensureLocalCalendarExists()

        // Assert - same ID returned
        assertEquals(firstCalendarId, secondCalendarId)
        assertEquals(secondCalendarId, thirdCalendarId)

        // Assert - only one calendar exists
        val calendars = database.calendarsDao().getAllOnce()
        assertEquals(1, calendars.size)

        // Assert - only one account exists
        val accounts = database.accountsDao().getAllOnce()
        assertEquals(1, accounts.size)
    }

    @Test
    fun `ensureLocalCalendarExists does not affect existing calendars`() = runTest {
        // Setup - create another account and calendar first
        val otherAccountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com", displayName = "iCloud")
        )
        val otherCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = otherAccountId,
                caldavUrl = "https://caldav.example.com/cal/",
                displayName = "Work Calendar",
                color = 0xFF0000FF.toInt()
            )
        )

        // Act
        val localCalendarId = localCalendarInitializer.ensureLocalCalendarExists()

        // Assert - both calendars exist
        val calendars = database.calendarsDao().getAllOnce()
        assertEquals(2, calendars.size)

        // Assert - other calendar unchanged
        val otherCalendar = database.calendarsDao().getById(otherCalendarId)
        assertNotNull(otherCalendar)
        assertEquals("Work Calendar", otherCalendar!!.displayName)

        // Assert - local calendar is separate
        assertTrue(localCalendarId != otherCalendarId)
    }

    // ========== getLocalCalendarId ==========

    @Test
    fun `getLocalCalendarId creates calendar if not exists`() = runTest {
        // Assert - no calendars initially
        assertEquals(0, database.calendarsDao().getAllOnce().size)

        // Act
        val calendarId = localCalendarInitializer.getLocalCalendarId()

        // Assert - calendar created
        assertTrue(calendarId > 0)
        val calendar = database.calendarsDao().getById(calendarId)
        assertNotNull(calendar)
        assertEquals(LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME, calendar!!.displayName)
    }

    @Test
    fun `getLocalCalendarId returns existing calendar if exists`() = runTest {
        // Setup - create local calendar first
        val existingId = localCalendarInitializer.ensureLocalCalendarExists()

        // Act
        val calendarId = localCalendarInitializer.getLocalCalendarId()

        // Assert - same ID returned
        assertEquals(existingId, calendarId)
    }

    // ========== isLocalAccount ==========

    @Test
    fun `isLocalAccount returns true for local account`() = runTest {
        // Setup
        localCalendarInitializer.ensureLocalCalendarExists()
        val localAccount = database.accountsDao().getByProviderAndEmail(
            AccountProvider.LOCAL,
            LocalCalendarInitializer.LOCAL_EMAIL
        )!!

        // Assert
        assertTrue(LocalCalendarInitializer.isLocalAccount(localAccount))
    }

    @Test
    fun `isLocalAccount returns false for every other provider`() {
        AccountProvider.entries.filter { it != AccountProvider.LOCAL }.forEach { provider ->
            val account = Account(provider = provider, email = LocalCalendarInitializer.LOCAL_EMAIL)
            assertFalse("$provider", LocalCalendarInitializer.isLocalAccount(account))
        }
    }

    @Test
    fun `isLocalAccount follows the provider, not the email`() {
        val account = Account(provider = AccountProvider.LOCAL, email = "different@example.test")

        assertTrue(LocalCalendarInitializer.isLocalAccount(account))
    }

    @Test
    fun `isLocalAccount returns false for a missing account`() {
        assertFalse(LocalCalendarInitializer.isLocalAccount(null))
    }

    // ========== isLocalCalendar ==========

    @Test
    fun `isLocalCalendar returns true for local calendar`() = runTest {
        // Setup
        val calendarId = localCalendarInitializer.ensureLocalCalendarExists()
        val localCalendar = database.calendarsDao().getById(calendarId)!!

        // Assert
        assertTrue(localCalendarInitializer.isLocalCalendar(localCalendar))
    }

    @Test
    fun `isLocalCalendar returns true for a second calendar of the local account`() = runTest {
        val builtInId = localCalendarInitializer.ensureLocalCalendarExists()

        val second = database.calendarsDao().getById(database.insertOfflineCalendar())!!

        assertTrue(second.caldavUrl.startsWith(LocalCalendarInitializer.NEW_CALENDAR_URL_PREFIX))
        assertEquals(database.calendarsDao().getById(builtInId)!!.accountId, second.accountId)
        assertTrue(localCalendarInitializer.isLocalCalendar(second))
    }

    @Test
    fun `isLocalCalendar returns false for non-local calendar`() = runTest {
        // Setup
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@example.com")
        )
        val otherCalendar = Calendar(
            accountId = accountId,
            caldavUrl = "https://caldav.example.com/cal/",
            displayName = "Work",
            color = 0xFF0000FF.toInt()
        )

        // Assert
        assertFalse(localCalendarInitializer.isLocalCalendar(otherCalendar))
    }

    @Test
    fun `isLocalCalendar returns false for a contact calendar at a local URL`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.CONTACTS, email = "contacts")
        )
        val birthdays = Calendar(
            accountId = accountId,
            caldavUrl = "local://contact_birthdays",
            displayName = "Birthdays",
            color = 0
        )

        assertFalse(localCalendarInitializer.isLocalCalendar(birthdays))
    }

    @Test
    fun `isLocalCalendar returns false for a feed calendar`() = runTest {
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.ICS, email = "ics")
        )
        val feed = Calendar(
            accountId = accountId,
            caldavUrl = "ics://feed/1",
            displayName = "Holidays",
            color = 0
        )

        assertFalse(localCalendarInitializer.isLocalCalendar(feed))
    }

    @Test
    fun `isLocalCalendar returns false when the account is missing`() = runTest {
        val orphan = Calendar(accountId = 999L, caldavUrl = "local://calendar/orphan", displayName = "Gone", color = 0)

        assertFalse(localCalendarInitializer.isLocalCalendar(orphan))
    }

    // ========== Edge Cases ==========

    @Test
    fun `local calendar has correct default properties`() = runTest {
        // Act
        val calendarId = localCalendarInitializer.ensureLocalCalendarExists()
        val calendar = database.calendarsDao().getById(calendarId)!!

        // Assert specific properties for local calendar
        // Material Gray 500
        assertEquals(LocalCalendarInitializer.LOCAL_CALENDAR_COLOR, calendar.color)
        assertTrue(calendar.isDefault)
        assertFalse(calendar.isReadOnly) // Users can always write to local
        assertEquals(0, calendar.sortOrder) // 0 is also the Calendar default
    }

    @Test
    fun `local account is always enabled`() = runTest {
        // Act
        localCalendarInitializer.ensureLocalCalendarExists()
        val account = database.accountsDao().getByProviderAndEmail(
            AccountProvider.LOCAL,
            LocalCalendarInitializer.LOCAL_EMAIL
        )!!

        // Assert
        assertTrue(account.isEnabled)
    }
}
