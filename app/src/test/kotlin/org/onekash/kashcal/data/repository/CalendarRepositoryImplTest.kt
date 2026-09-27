package org.onekash.kashcal.data.repository

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/** Tests that [CalendarRepositoryImpl] delegates each read and write to [CalendarsDao]. */
class CalendarRepositoryImplTest {

    private lateinit var calendarRepository: CalendarRepositoryImpl
    private lateinit var calendarsDao: CalendarsDao

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

        // Explicit stubs, not relaxed, so an unstubbed call throws instead of silently returning
        // null or empty. These are the defaults; per-test stubs override them.
        calendarsDao = mockk()
        coEvery { calendarsDao.getById(any()) } returns null
        coEvery { calendarsDao.getByCaldavUrl(any()) } returns null
        coEvery { calendarsDao.getByAccountIdOnce(any()) } returns emptyList()
        coEvery { calendarsDao.getAllOnce() } returns emptyList()
        coEvery { calendarsDao.getEnabledCalendars() } returns emptyList()
        coEvery { calendarsDao.insert(any()) } returns 0L
        coEvery { calendarsDao.update(any()) } just runs
        coEvery { calendarsDao.deleteById(any()) } just runs
        coEvery { calendarsDao.setVisible(any(), any()) } just runs
        coEvery { calendarsDao.setVisibleForAccount(any(), any()) } just runs
        coEvery { calendarsDao.updateSyncToken(any(), any(), any()) } just runs
        coEvery { calendarsDao.updateCtag(any(), any()) } just runs
        every { calendarsDao.getAll() } returns flowOf(emptyList())
        every { calendarsDao.getVisibleCalendars() } returns flowOf(emptyList())
        every { calendarsDao.getByAccountId(any()) } returns flowOf(emptyList())
        every { calendarsDao.getCalendarCountByProvider(any()) } returns flowOf(0)
        calendarRepository = CalendarRepositoryImpl(calendarsDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Flow Tests ==========

    @Test
    fun `getAllCalendarsFlow returns flow from DAO`() = runBlocking {
        val calendars = listOf(testCalendar(id = 1L, displayName = "Cal 1"))
        every { calendarsDao.getAll() } returns flowOf(calendars)

        val flow = calendarRepository.getAllCalendarsFlow()

        assertNotNull(flow)
    }

    @Test
    fun `getVisibleCalendarsFlow filters by is_visible`() = runBlocking {
        val visibleCalendars = listOf(testCalendar(id = 1L, displayName = "Visible", isVisible = true))
        every { calendarsDao.getVisibleCalendars() } returns flowOf(visibleCalendars)

        val flow = calendarRepository.getVisibleCalendarsFlow()

        assertNotNull(flow)
        verify { calendarsDao.getVisibleCalendars() }
    }

    @Test
    fun `getCalendarsForAccountFlow returns account calendars`() = runBlocking {
        val calendars = listOf(testCalendar(id = 1L, accountId = 5L, displayName = "Account Cal"))
        every { calendarsDao.getByAccountId(5L) } returns flowOf(calendars)

        val flow = calendarRepository.getCalendarsForAccountFlow(5L)

        assertNotNull(flow)
        verify { calendarsDao.getByAccountId(5L) }
    }

    // ========== One-Shot Query Tests ==========

    @Test
    fun `getCalendarById returns correct calendar`() = runBlocking {
        val calendar = testCalendar(id = 1L, displayName = "Test")
        coEvery { calendarsDao.getById(1L) } returns calendar

        val result = calendarRepository.getCalendarById(1L)

        assertEquals(calendar, result)
    }

    @Test
    fun `getCalendarByUrl returns correct calendar`() = runBlocking {
        val url = "https://caldav.icloud.com/12345/calendar/"
        val calendar = testCalendar(id = 1L, displayName = "Test", caldavUrl = url)
        coEvery { calendarsDao.getByCaldavUrl(url) } returns calendar

        val result = calendarRepository.getCalendarByUrl(url)

        assertEquals(calendar, result)
    }

    @Test
    fun `getCalendarsForAccountOnce returns only account calendars`() = runBlocking {
        val calendars = listOf(
            testCalendar(id = 1L, accountId = 5L, displayName = "Cal 1", caldavUrl = "https://a/1"),
            testCalendar(id = 2L, accountId = 5L, displayName = "Cal 2", caldavUrl = "https://a/2")
        )
        coEvery { calendarsDao.getByAccountIdOnce(5L) } returns calendars

        val result = calendarRepository.getCalendarsForAccountOnce(5L)

        assertEquals(2, result.size)
        assertTrue(result.all { it.accountId == 5L })
    }

    // ========== Write Operation Tests ==========

    @Test
    fun `createCalendar returns generated ID`() = runBlocking {
        val calendar = testCalendar(id = 0L, displayName = "New Cal")
        coEvery { calendarsDao.insert(calendar) } returns 42L

        val result = calendarRepository.createCalendar(calendar)

        assertEquals(42L, result)
    }

    @Test
    fun `updateCalendar calls DAO update`() = runBlocking {
        val calendar = testCalendar(id = 1L, displayName = "Updated")

        calendarRepository.updateCalendar(calendar)

        coVerify { calendarsDao.update(calendar) }
    }

    @Test
    fun `deleteCalendar removes calendar from database`() = runBlocking {
        calendarRepository.deleteCalendar(1L)

        coVerify { calendarsDao.deleteById(1L) }
    }

    @Test
    fun `setVisibility updates calendar visibility`() = runBlocking {
        calendarRepository.setVisibility(1L, false)

        coVerify { calendarsDao.setVisible(1L, false) }
    }

    @Test
    fun `setAllVisible updates all calendars for account`() = runBlocking {
        calendarRepository.setAllVisible(5L, false)

        // One account-wide UPDATE, not one per calendar.
        coVerify { calendarsDao.setVisibleForAccount(5L, false) }
    }

    // ========== Sync Metadata Tests ==========

    @Test
    fun `updateSyncToken persists both syncToken and ctag`() = runBlocking {
        calendarRepository.updateSyncToken(1L, "sync-token-123", "ctag-456")

        coVerify { calendarsDao.updateSyncToken(1L, "sync-token-123", "ctag-456") }
    }

    @Test
    fun `updateCtag persists ctag only`() = runBlocking {
        calendarRepository.updateCtag(1L, "ctag-789")

        coVerify { calendarsDao.updateCtag(1L, "ctag-789") }
    }

    // ========== Provider Filter Tests ==========

    @Test
    fun `getCalendarCountByProviderFlow uses provider name`() = runBlocking {
        every { calendarsDao.getCalendarCountByProvider("CALDAV") } returns flowOf(3)

        val flow = calendarRepository.getCalendarCountByProviderFlow(AccountProvider.CALDAV)

        assertNotNull(flow)
        verify { calendarsDao.getCalendarCountByProvider("CALDAV") }
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `getCalendarById returns null for non-existent ID`() = runBlocking {
        coEvery { calendarsDao.getById(999L) } returns null

        val result = calendarRepository.getCalendarById(999L)

        assertNull(result)
    }

    @Test
    fun `deleteCalendar on non-existent ID is no-op`() = runBlocking {
        // The DAO delete succeeds for a missing ID.
        coEvery { calendarsDao.deleteById(999L) } just runs

        // Doesn't throw.
        calendarRepository.deleteCalendar(999L)

        coVerify { calendarsDao.deleteById(999L) }
    }

    @Test
    fun `getCalendarsForAccountOnce returns empty list for non-existent account`() = runBlocking {
        coEvery { calendarsDao.getByAccountIdOnce(999L) } returns emptyList()

        val result = calendarRepository.getCalendarsForAccountOnce(999L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllCalendars returns empty list when no calendars exist`() = runBlocking {
        coEvery { calendarsDao.getAllOnce() } returns emptyList()

        val result = calendarRepository.getAllCalendars()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getEnabledCalendars returns only enabled calendars`() = runBlocking {
        val enabledCalendars = listOf(
            testCalendar(id = 1L, displayName = "Enabled Cal")
        )
        coEvery { calendarsDao.getEnabledCalendars() } returns enabledCalendars

        val result = calendarRepository.getEnabledCalendars()

        assertEquals(1, result.size)
    }

    @Test
    fun `setAllVisible with empty calendar list is no-op`() = runBlocking {
        coEvery { calendarsDao.getByAccountIdOnce(5L) } returns emptyList()

        calendarRepository.setAllVisible(5L, true)

        // setAllVisible issues setVisibleForAccount, never a per-calendar setVisible.
        coVerify(exactly = 0) { calendarsDao.setVisible(any(), any()) }
    }

    @Test
    fun `updateSyncToken with null values clears tokens`() = runBlocking {
        calendarRepository.updateSyncToken(1L, null, null)

        // Nulls are passed through to the DAO.
        coVerify { calendarsDao.updateSyncToken(1L, null, null) }
    }

    @Test
    fun `getCalendarByUrl returns null for non-existent URL`() = runBlocking {
        coEvery { calendarsDao.getByCaldavUrl("https://nonexistent.com/cal/") } returns null

        val result = calendarRepository.getCalendarByUrl("https://nonexistent.com/cal/")

        assertNull(result)
    }
}
