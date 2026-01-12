package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Integration tests for CalendarsDao.
 *
 * Tests CRUD operations, FK constraints, and cascade deletes.
 */
class CalendarsDaoTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }

    private var testAccountId: Long = 0

    @Before
    override fun setup() {
        super.setup()
        // Create a parent account for FK tests
        runTest {
            testAccountId = accountsDao.insert(
                Account(provider = "icloud", email = "test@example.com")
            )
        }
    }

    private fun createCalendar(
        accountId: Long = testAccountId,
        caldavUrl: String = "https://caldav.example.com/cal/${System.nanoTime()}/",
        displayName: String = "Test Calendar"
    ) = Calendar(
        accountId = accountId,
        caldavUrl = caldavUrl,
        displayName = displayName,
        color = 0xFF0000FF.toInt() // Blue
    )

    // ========== Insert Tests ==========

    @Test
    fun `insert returns generated id`() = runTest {
        val calendar = createCalendar()
        val id = calendarsDao.insert(calendar)
        assertTrue(id > 0)
    }

    @Test
    fun `insert and retrieve calendar`() = runTest {
        val calendar = createCalendar(displayName = "Work Calendar")
        val id = calendarsDao.insert(calendar)

        val retrieved = calendarsDao.getById(id)
        assertNotNull(retrieved)
        assertEquals("Work Calendar", retrieved!!.displayName)
        assertEquals(testAccountId, retrieved.accountId)
    }

    // ========== FK Constraint Tests ==========

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun `insert with invalid accountId throws`() = runTest {
        val calendar = createCalendar(accountId = 999L)
        calendarsDao.insert(calendar)
    }

    // ========== Query Tests ==========

    @Test
    fun `getById returns null for non-existent id`() = runTest {
        val result = calendarsDao.getById(999L)
        assertNull(result)
    }

    @Test
    fun `getByCaldavUrl finds calendar`() = runTest {
        val url = "https://caldav.icloud.com/unique-calendar/"
        calendarsDao.insert(createCalendar(caldavUrl = url))

        val result = calendarsDao.getByCaldavUrl(url)
        assertNotNull(result)
        assertEquals(url, result!!.caldavUrl)
    }

    @Test
    fun `getByAccountId returns calendars for account`() = runTest {
        // Create second account
        val account2Id = accountsDao.insert(
            Account(provider = "fastmail", email = "other@example.com")
        )

        // Add calendars to both accounts
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "Cal 1"))
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "Cal 2"))
        calendarsDao.insert(createCalendar(accountId = account2Id, displayName = "Cal 3"))

        val account1Calendars = calendarsDao.getByAccountIdOnce(testAccountId)
        assertEquals(2, account1Calendars.size)

        val account2Calendars = calendarsDao.getByAccountIdOnce(account2Id)
        assertEquals(1, account2Calendars.size)
    }

    @Test
    fun `getVisibleCalendars filters hidden`() = runTest {
        val visibleId = calendarsDao.insert(createCalendar(displayName = "Visible"))
        val hiddenId = calendarsDao.insert(createCalendar(displayName = "Hidden"))

        calendarsDao.setVisible(hiddenId, false)

        val visible = calendarsDao.getVisibleCalendars().first()
        assertEquals(1, visible.size)
        assertEquals(visibleId, visible[0].id)
    }

    @Test
    fun `getDefaultCalendar returns default for account`() = runTest {
        val cal1Id = calendarsDao.insert(createCalendar(displayName = "Cal 1"))
        val cal2Id = calendarsDao.insert(createCalendar(displayName = "Cal 2"))

        calendarsDao.setDefaultCalendar(testAccountId, cal2Id)

        val defaultCal = calendarsDao.getDefaultCalendar(testAccountId)
        assertNotNull(defaultCal)
        assertEquals(cal2Id, defaultCal!!.id)
    }

    @Test
    fun `setDefaultCalendar clears previous default`() = runTest {
        val cal1Id = calendarsDao.insert(createCalendar(displayName = "Cal 1"))
        val cal2Id = calendarsDao.insert(createCalendar(displayName = "Cal 2"))

        calendarsDao.setDefaultCalendar(testAccountId, cal1Id)
        assertEquals(true, calendarsDao.getById(cal1Id)!!.isDefault)

        calendarsDao.setDefaultCalendar(testAccountId, cal2Id)
        assertEquals(false, calendarsDao.getById(cal1Id)!!.isDefault)
        assertEquals(true, calendarsDao.getById(cal2Id)!!.isDefault)
    }

    // ========== Update Tests ==========

    @Test
    fun `update modifies calendar`() = runTest {
        val id = calendarsDao.insert(createCalendar(displayName = "Original"))

        val calendar = calendarsDao.getById(id)!!
        calendarsDao.update(calendar.copy(displayName = "Updated"))

        assertEquals("Updated", calendarsDao.getById(id)!!.displayName)
    }

    @Test
    fun `updateSyncToken sets sync metadata`() = runTest {
        val id = calendarsDao.insert(createCalendar())

        calendarsDao.updateSyncToken(id, syncToken = "token-123", ctag = "ctag-456")

        val result = calendarsDao.getById(id)!!
        assertEquals("token-123", result.syncToken)
        assertEquals("ctag-456", result.ctag)
    }

    @Test
    fun `updateColor changes calendar color`() = runTest {
        val id = calendarsDao.insert(createCalendar())

        calendarsDao.updateColor(id, 0xFFFF0000.toInt()) // Red

        assertEquals(0xFFFF0000.toInt(), calendarsDao.getById(id)!!.color)
    }

    @Test
    fun `updateDisplayName changes name`() = runTest {
        val id = calendarsDao.insert(createCalendar(displayName = "Old Name"))

        calendarsDao.updateDisplayName(id, "New Name")

        assertEquals("New Name", calendarsDao.getById(id)!!.displayName)
    }

    // ========== Delete Tests ==========

    @Test
    fun `delete removes calendar`() = runTest {
        val calendar = createCalendar()
        val id = calendarsDao.insert(calendar)

        calendarsDao.delete(calendar.copy(id = id))

        assertNull(calendarsDao.getById(id))
    }

    @Test
    fun `deleteById removes calendar`() = runTest {
        val id = calendarsDao.insert(createCalendar())

        calendarsDao.deleteById(id)

        assertNull(calendarsDao.getById(id))
    }

    @Test
    fun `deleteByAccountId removes all calendars for account`() = runTest {
        calendarsDao.insert(createCalendar(displayName = "Cal 1"))
        calendarsDao.insert(createCalendar(displayName = "Cal 2"))

        calendarsDao.deleteByAccountId(testAccountId)

        assertEquals(0, calendarsDao.getByAccountIdOnce(testAccountId).size)
    }

    // ========== Cascade Delete Tests ==========

    @Test
    fun `deleting account cascades to calendars`() = runTest {
        val calId = calendarsDao.insert(createCalendar())

        // Delete parent account
        accountsDao.deleteById(testAccountId)

        // Calendar should be deleted too
        assertNull(calendarsDao.getById(calId))
    }

    // ========== Unique Constraint Tests ==========

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun `insert duplicate caldavUrl throws`() = runTest {
        val url = "https://caldav.example.com/duplicate/"
        calendarsDao.insert(createCalendar(caldavUrl = url))
        calendarsDao.insert(createCalendar(caldavUrl = url))
    }

    // ========== Sort Order Tests ==========

    @Test
    fun `calendars are sorted by sortOrder then displayName`() = runTest {
        calendarsDao.insert(createCalendar(displayName = "Zebra").copy(sortOrder = 2))
        calendarsDao.insert(createCalendar(displayName = "Alpha").copy(sortOrder = 1))
        calendarsDao.insert(createCalendar(displayName = "Beta").copy(sortOrder = 1))

        val calendars = calendarsDao.getAllOnce()
        assertEquals("Alpha", calendars[0].displayName)
        assertEquals("Beta", calendars[1].displayName)
        assertEquals("Zebra", calendars[2].displayName)
    }

    // ========== Provider-based Query Tests (Checkpoint 2 fix) ==========

    @Test
    fun `getCalendarsByProvider returns only matching provider`() = runTest {
        // Create a local account
        val localAccountId = accountsDao.insert(
            Account(provider = "local", email = "local@device")
        )

        // Create calendars for both providers
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "iCloud Cal 1"))
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "iCloud Cal 2"))
        calendarsDao.insert(createCalendar(accountId = localAccountId, displayName = "Local Cal"))

        // Query by provider
        val iCloudCalendars = calendarsDao.getCalendarsByProvider("icloud").first()
        val localCalendars = calendarsDao.getCalendarsByProvider("local").first()

        assertEquals(2, iCloudCalendars.size)
        assertEquals(1, localCalendars.size)
        assertEquals("Local Cal", localCalendars[0].displayName)
    }

    @Test
    fun `getCalendarCountByProvider returns correct count`() = runTest {
        // Create a local account
        val localAccountId = accountsDao.insert(
            Account(provider = "local", email = "local@device")
        )

        // Create calendars for both providers
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "iCloud Cal 1"))
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "iCloud Cal 2"))
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "iCloud Cal 3"))
        calendarsDao.insert(createCalendar(accountId = localAccountId, displayName = "Local Cal"))

        // Query count by provider
        val iCloudCount = calendarsDao.getCalendarCountByProvider("icloud").first()
        val localCount = calendarsDao.getCalendarCountByProvider("local").first()

        assertEquals(3, iCloudCount)
        assertEquals(1, localCount)
    }

    @Test
    fun `getCalendarCountByProvider returns zero for non-existent provider`() = runTest {
        calendarsDao.insert(createCalendar(displayName = "iCloud Cal"))

        val googleCount = calendarsDao.getCalendarCountByProvider("google").first()
        assertEquals(0, googleCount)
    }

    @Test
    fun `getCalendarsByProvider excludes ICS subscription calendars`() = runTest {
        // Note: ICS subscriptions don't have an account (they're in ics_subscriptions table)
        // This test verifies that only calendars with a proper account are counted
        calendarsDao.insert(createCalendar(accountId = testAccountId, displayName = "iCloud Cal"))

        val iCloudCalendars = calendarsDao.getCalendarsByProvider("icloud").first()
        assertEquals(1, iCloudCalendars.size)
        // ICS subscription calendars are stored separately and won't be included
    }
}
