package org.onekash.kashcal.data.repository

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Unit tests for CalendarRepositoryImpl.
 */
class CalendarRepositoryImplTest {

    private lateinit var calendarRepository: CalendarRepositoryImpl
    private lateinit var calendarsDao: CalendarsDao

    // Helper to create test Calendar with required fields
    private fun testCalendar(
        id: Long = 0L,
        accountId: Long = 1L,
        displayName: String = "Test Calendar",
        caldavUrl: String = "https://caldav.example.com/cal/",
        color: Int = 0xFF0000FF.toInt(),
        isVisible: Boolean = true,
        isDefault: Boolean = false
    ) = Calendar(
        id = id,
        accountId = accountId,
        displayName = displayName,
        caldavUrl = caldavUrl,
        color = color,
        isVisible = isVisible,
        isDefault = isDefault
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        calendarsDao = mockk(relaxed = true)
        calendarRepository = CalendarRepositoryImpl(calendarsDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Flow Tests ==========

    @Test
    fun `getAllCalendarsFlow returns flow from DAO`() = runBlocking {
        // Setup
        val calendars = listOf(testCalendar(id = 1L, displayName = "Cal 1"))
        every { calendarsDao.getAll() } returns flowOf(calendars)

        // Execute
        val flow = calendarRepository.getAllCalendarsFlow()

        // Verify
        assertNotNull(flow)
    }

    @Test
    fun `getVisibleCalendarsFlow filters by is_visible`() = runBlocking {
        // Setup
        val visibleCalendars = listOf(testCalendar(id = 1L, displayName = "Visible", isVisible = true))
        every { calendarsDao.getVisibleCalendars() } returns flowOf(visibleCalendars)

        // Execute
        val flow = calendarRepository.getVisibleCalendarsFlow()

        // Verify
        assertNotNull(flow)
        verify { calendarsDao.getVisibleCalendars() }
    }

    @Test
    fun `getCalendarsForAccountFlow returns account calendars`() = runBlocking {
        // Setup
        val calendars = listOf(testCalendar(id = 1L, accountId = 5L, displayName = "Account Cal"))
        every { calendarsDao.getByAccountId(5L) } returns flowOf(calendars)

        // Execute
        val flow = calendarRepository.getCalendarsForAccountFlow(5L)

        // Verify
        assertNotNull(flow)
        verify { calendarsDao.getByAccountId(5L) }
    }

    // ========== One-Shot Query Tests ==========

    @Test
    fun `getCalendarById returns correct calendar`() = runBlocking {
        // Setup
        val calendar = testCalendar(id = 1L, displayName = "Test")
        coEvery { calendarsDao.getById(1L) } returns calendar

        // Execute
        val result = calendarRepository.getCalendarById(1L)

        // Verify
        assertEquals(calendar, result)
    }

    @Test
    fun `getCalendarByUrl returns correct calendar`() = runBlocking {
        // Setup
        val url = "https://caldav.icloud.com/12345/calendar/"
        val calendar = testCalendar(id = 1L, displayName = "Test", caldavUrl = url)
        coEvery { calendarsDao.getByCaldavUrl(url) } returns calendar

        // Execute
        val result = calendarRepository.getCalendarByUrl(url)

        // Verify
        assertEquals(calendar, result)
    }

    @Test
    fun `getCalendarsForAccountOnce returns only account calendars`() = runBlocking {
        // Setup
        val calendars = listOf(
            testCalendar(id = 1L, accountId = 5L, displayName = "Cal 1", caldavUrl = "https://a/1"),
            testCalendar(id = 2L, accountId = 5L, displayName = "Cal 2", caldavUrl = "https://a/2")
        )
        coEvery { calendarsDao.getByAccountIdOnce(5L) } returns calendars

        // Execute
        val result = calendarRepository.getCalendarsForAccountOnce(5L)

        // Verify
        assertEquals(2, result.size)
        assertTrue(result.all { it.accountId == 5L })
    }

    @Test
    fun `getDefaultCalendar returns any default calendar`() = runBlocking {
        // Setup
        val defaultCal = testCalendar(id = 1L, displayName = "Default", isDefault = true)
        coEvery { calendarsDao.getAnyDefaultCalendar() } returns defaultCal

        // Execute
        val result = calendarRepository.getDefaultCalendar()

        // Verify
        assertEquals(defaultCal, result)
    }

    // ========== Write Operation Tests ==========

    @Test
    fun `createCalendar returns generated ID`() = runBlocking {
        // Setup
        val calendar = testCalendar(id = 0L, displayName = "New Cal")
        coEvery { calendarsDao.insert(calendar) } returns 42L

        // Execute
        val result = calendarRepository.createCalendar(calendar)

        // Verify
        assertEquals(42L, result)
    }

    @Test
    fun `updateCalendar calls DAO update`() = runBlocking {
        // Setup
        val calendar = testCalendar(id = 1L, displayName = "Updated")

        // Execute
        calendarRepository.updateCalendar(calendar)

        // Verify
        coVerify { calendarsDao.update(calendar) }
    }

    @Test
    fun `deleteCalendar removes calendar from database`() = runBlocking {
        // Execute
        calendarRepository.deleteCalendar(1L)

        // Verify
        coVerify { calendarsDao.deleteById(1L) }
    }

    @Test
    fun `setVisibility updates calendar visibility`() = runBlocking {
        // Execute
        calendarRepository.setVisibility(1L, false)

        // Verify
        coVerify { calendarsDao.setVisible(1L, false) }
    }

    @Test
    fun `setAllVisible updates all calendars for account`() = runBlocking {
        // Setup
        val calendars = listOf(
            testCalendar(id = 1L, accountId = 5L, displayName = "Cal 1", caldavUrl = "https://a/1"),
            testCalendar(id = 2L, accountId = 5L, displayName = "Cal 2", caldavUrl = "https://a/2"),
            testCalendar(id = 3L, accountId = 5L, displayName = "Cal 3", caldavUrl = "https://a/3")
        )
        coEvery { calendarsDao.getByAccountIdOnce(5L) } returns calendars

        // Execute
        calendarRepository.setAllVisible(5L, false)

        // Verify all calendars updated
        coVerify { calendarsDao.setVisible(1L, false) }
        coVerify { calendarsDao.setVisible(2L, false) }
        coVerify { calendarsDao.setVisible(3L, false) }
    }

    // ========== Sync Metadata Tests ==========

    @Test
    fun `updateSyncToken persists both syncToken and ctag`() = runBlocking {
        // Execute
        calendarRepository.updateSyncToken(1L, "sync-token-123", "ctag-456")

        // Verify
        coVerify { calendarsDao.updateSyncToken(1L, "sync-token-123", "ctag-456") }
    }

    @Test
    fun `updateCtag persists ctag only`() = runBlocking {
        // Execute
        calendarRepository.updateCtag(1L, "ctag-789")

        // Verify
        coVerify { calendarsDao.updateCtag(1L, "ctag-789") }
    }

    @Test
    fun `setDefaultCalendar sets correct calendar as default`() = runBlocking {
        // Execute
        calendarRepository.setDefaultCalendar(5L, 10L)

        // Verify
        coVerify { calendarsDao.setDefaultCalendar(5L, 10L) }
    }

    // ========== Provider Filter Tests ==========

    @Test
    fun `getCalendarCountByProviderFlow uses provider name`() = runBlocking {
        // Setup
        every { calendarsDao.getCalendarCountByProvider("CALDAV") } returns flowOf(3)

        // Execute
        val flow = calendarRepository.getCalendarCountByProviderFlow(AccountProvider.CALDAV)

        // Verify
        assertNotNull(flow)
        verify { calendarsDao.getCalendarCountByProvider("CALDAV") }
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `getCalendarById returns null for non-existent ID`() = runBlocking {
        // Setup
        coEvery { calendarsDao.getById(999L) } returns null

        // Execute
        val result = calendarRepository.getCalendarById(999L)

        // Verify
        assertNull(result)
    }

    @Test
    fun `getDefaultCalendar returns null when no default exists`() = runBlocking {
        // Setup
        coEvery { calendarsDao.getAnyDefaultCalendar() } returns null

        // Execute
        val result = calendarRepository.getDefaultCalendar()

        // Verify
        assertNull(result)
    }

    @Test
    fun `deleteCalendar on non-existent ID is no-op`() = runBlocking {
        // Setup - DAO delete silently succeeds even if ID doesn't exist
        coEvery { calendarsDao.deleteById(999L) } just runs

        // Execute - should NOT throw
        calendarRepository.deleteCalendar(999L)

        // Verify delete was called (Room handles non-existent gracefully)
        coVerify { calendarsDao.deleteById(999L) }
    }

    @Test
    fun `getCalendarsForAccountOnce returns empty list for non-existent account`() = runBlocking {
        // Setup
        coEvery { calendarsDao.getByAccountIdOnce(999L) } returns emptyList()

        // Execute
        val result = calendarRepository.getCalendarsForAccountOnce(999L)

        // Verify
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDefaultCalendarForAccount returns null when account has no default`() = runBlocking {
        // Setup
        coEvery { calendarsDao.getDefaultCalendar(5L) } returns null

        // Execute
        val result = calendarRepository.getDefaultCalendarForAccount(5L)

        // Verify
        assertNull(result)
    }

    @Test
    fun `getAllCalendars returns empty list when no calendars exist`() = runBlocking {
        // Setup
        coEvery { calendarsDao.getAllOnce() } returns emptyList()

        // Execute
        val result = calendarRepository.getAllCalendars()

        // Verify
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getEnabledCalendars returns only enabled calendars`() = runBlocking {
        // Setup
        val enabledCalendars = listOf(
            testCalendar(id = 1L, displayName = "Enabled Cal")
        )
        coEvery { calendarsDao.getEnabledCalendars() } returns enabledCalendars

        // Execute
        val result = calendarRepository.getEnabledCalendars()

        // Verify
        assertEquals(1, result.size)
    }

    @Test
    fun `setAllVisible with empty calendar list is no-op`() = runBlocking {
        // Setup - account has no calendars
        coEvery { calendarsDao.getByAccountIdOnce(5L) } returns emptyList()

        // Execute
        calendarRepository.setAllVisible(5L, true)

        // Verify setVisible never called (no calendars to update)
        coVerify(exactly = 0) { calendarsDao.setVisible(any(), any()) }
    }

    @Test
    fun `updateSyncToken with null values clears tokens`() = runBlocking {
        // Execute
        calendarRepository.updateSyncToken(1L, null, null)

        // Verify null values passed through
        coVerify { calendarsDao.updateSyncToken(1L, null, null) }
    }

    @Test
    fun `getCalendarByUrl returns null for non-existent URL`() = runBlocking {
        // Setup
        coEvery { calendarsDao.getByCaldavUrl("https://nonexistent.com/cal/") } returns null

        // Execute
        val result = calendarRepository.getCalendarByUrl("https://nonexistent.com/cal/")

        // Verify
        assertNull(result)
    }
}
