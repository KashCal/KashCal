package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for IcsSubscriptionsDao - ICS calendar subscription management.
 *
 * Critical for ensuring:
 * - Subscription CRUD operations work correctly
 * - Sync status updates (etag, lastModified, errors) are persisted
 * - Settings changes (name, color, interval) are applied
 * - URL uniqueness is enforced
 * - Cascade delete with Calendar works properly
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsSubscriptionsDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var subscriptionsDao: IcsSubscriptionsDao
    private lateinit var calendarsDao: CalendarsDao
    private var testAccountId: Long = 0
    private var testCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        subscriptionsDao = database.icsSubscriptionsDao()
        calendarsDao = database.calendarsDao()

        runTest {
            // Create ICS account (required for calendars)
            testAccountId = database.accountsDao().insert(
                Account(
                    provider = AccountProvider.ICS,
                    email = IcsSubscription.ACCOUNT_EMAIL
                )
            )

            // Create a calendar for subscription (use unique URL to avoid conflicts with createCalendar())
            testCalendarId = calendarsDao.insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "ics://test-setup-calendar",
                    displayName = "Test ICS Calendar",
                    color = 0xFF4CAF50.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // Counter for unique URLs - starts at 100 to avoid conflicts with setup
    private var subscriptionCounter = 100

    private fun createSubscription(
        url: String = "https://example.com/calendar${++subscriptionCounter}.ics",
        name: String = "Test Subscription",
        color: Int = 0xFF4CAF50.toInt(),
        calendarId: Long = testCalendarId,
        enabled: Boolean = true,
        syncIntervalHours: Int = 24,
        lastSync: Long = 0,
        etag: String? = null,
        lastModified: String? = null,
        username: String? = null,
        lastError: String? = null
    ): IcsSubscription {
        return IcsSubscription(
            url = url,
            name = name,
            color = color,
            calendarId = calendarId,
            enabled = enabled,
            syncIntervalHours = syncIntervalHours,
            lastSync = lastSync,
            etag = etag,
            lastModified = lastModified,
            username = username,
            lastError = lastError
        )
    }

    private suspend fun createCalendar(): Long {
        return calendarsDao.insert(
            Calendar(
                accountId = testAccountId,
                caldavUrl = "ics://subscription/${++subscriptionCounter}",
                displayName = "Calendar $subscriptionCounter",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    // ==================== Basic CRUD Tests ====================

    @Test
    fun `insert creates subscription with generated ID`() = runTest {
        val subscription = createSubscription()
        val id = subscriptionsDao.insert(subscription)

        assertTrue(id > 0)

        val retrieved = subscriptionsDao.getById(id)
        assertNotNull(retrieved)
        assertEquals(subscription.url, retrieved?.url)
        assertEquals(subscription.name, retrieved?.name)
    }

    @Test
    fun `upsert creates new subscription`() = runTest {
        val subscription = createSubscription()
        val id = subscriptionsDao.upsert(subscription)

        assertTrue(id > 0)
        val retrieved = subscriptionsDao.getById(id)
        assertNotNull(retrieved)
    }

    @Test
    fun `upsert updates existing subscription`() = runTest {
        val subscription = createSubscription()
        val id = subscriptionsDao.insert(subscription)

        val updated = subscription.copy(id = id, name = "Updated Name")
        subscriptionsDao.upsert(updated)

        val retrieved = subscriptionsDao.getById(id)
        assertEquals("Updated Name", retrieved?.name)
    }

    @Test
    fun `update modifies subscription`() = runTest {
        val subscription = createSubscription()
        val id = subscriptionsDao.insert(subscription)

        val toUpdate = subscription.copy(id = id, name = "New Name", color = 0xFFFF0000.toInt())
        subscriptionsDao.update(toUpdate)

        val retrieved = subscriptionsDao.getById(id)
        assertEquals("New Name", retrieved?.name)
        assertEquals(0xFFFF0000.toInt(), retrieved?.color)
    }

    @Test
    fun `delete removes subscription`() = runTest {
        val subscription = createSubscription()
        val id = subscriptionsDao.insert(subscription)

        subscriptionsDao.delete(subscription.copy(id = id))

        val retrieved = subscriptionsDao.getById(id)
        assertNull(retrieved)
    }

    @Test
    fun `deleteById removes subscription by ID`() = runTest {
        val subscription = createSubscription()
        val id = subscriptionsDao.insert(subscription)

        subscriptionsDao.deleteById(id)

        val retrieved = subscriptionsDao.getById(id)
        assertNull(retrieved)
    }

    @Test
    fun `getById returns null for non-existent ID`() = runTest {
        val subscription = subscriptionsDao.getById(99999L)
        assertNull(subscription)
    }

    // ==================== Query Tests ====================

    @Test
    fun `getAll returns reactive Flow of subscriptions`() = runTest {
        subscriptionsDao.getAll().test {
            // Initial state
            val initial = awaitItem()
            assertEquals(0, initial.size)

            // Add subscription
            val cal1 = createCalendar()
            subscriptionsDao.insert(createSubscription(calendarId = cal1, name = "Sub A"))

            val afterInsert = awaitItem()
            assertEquals(1, afterInsert.size)
            assertEquals("Sub A", afterInsert.first().name)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getAll returns subscriptions sorted by name ASC`() = runTest {
        val cal1 = createCalendar()
        val cal2 = createCalendar()
        val cal3 = createCalendar()

        subscriptionsDao.insert(createSubscription(calendarId = cal1, name = "Zebra"))
        subscriptionsDao.insert(createSubscription(calendarId = cal2, name = "Alpha"))
        subscriptionsDao.insert(createSubscription(calendarId = cal3, name = "Middle"))

        val all = subscriptionsDao.getAllOnce()

        assertEquals(3, all.size)
        assertEquals("Alpha", all[0].name)
        assertEquals("Middle", all[1].name)
        assertEquals("Zebra", all[2].name)
    }

    @Test
    fun `getByUrl returns subscription by URL`() = runTest {
        val testUrl = "https://unique.example.com/calendar.ics"
        subscriptionsDao.insert(createSubscription(url = testUrl, name = "By URL"))

        val found = subscriptionsDao.getByUrl(testUrl)

        assertNotNull(found)
        assertEquals("By URL", found?.name)
    }

    @Test
    fun `getByUrl returns null for non-existent URL`() = runTest {
        val found = subscriptionsDao.getByUrl("https://nonexistent.com/calendar.ics")
        assertNull(found)
    }

    @Test
    fun `getByCalendarId returns subscription by calendar ID`() = runTest {
        val calId = createCalendar()
        subscriptionsDao.insert(createSubscription(calendarId = calId, name = "By CalendarId"))

        val found = subscriptionsDao.getByCalendarId(calId)

        assertNotNull(found)
        assertEquals("By CalendarId", found?.name)
    }

    @Test
    fun `getEnabled returns only enabled subscriptions`() = runTest {
        val cal1 = createCalendar()
        val cal2 = createCalendar()
        val cal3 = createCalendar()

        subscriptionsDao.insert(createSubscription(calendarId = cal1, enabled = true, name = "Enabled 1"))
        subscriptionsDao.insert(createSubscription(calendarId = cal2, enabled = false, name = "Disabled"))
        subscriptionsDao.insert(createSubscription(calendarId = cal3, enabled = true, name = "Enabled 2"))

        val enabled = subscriptionsDao.getEnabled()

        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `getWithErrors returns subscriptions with last_error set`() = runTest {
        val cal1 = createCalendar()
        val cal2 = createCalendar()
        val cal3 = createCalendar()

        subscriptionsDao.insert(createSubscription(calendarId = cal1, lastError = null))
        subscriptionsDao.insert(createSubscription(calendarId = cal2, lastError = "Network error"))
        subscriptionsDao.insert(createSubscription(calendarId = cal3, lastError = "Parse error"))

        val withErrors = subscriptionsDao.getWithErrors()

        assertEquals(2, withErrors.size)
        assertTrue(withErrors.all { !it.lastError.isNullOrEmpty() })
    }

    // ==================== Count Tests ====================

    @Test
    fun `getCount returns total subscription count`() = runTest {
        val cal1 = createCalendar()
        val cal2 = createCalendar()

        subscriptionsDao.insert(createSubscription(calendarId = cal1))
        subscriptionsDao.insert(createSubscription(calendarId = cal2))

        val count = subscriptionsDao.getCount()
        assertEquals(2, count)
    }

    @Test
    fun `getEnabledCount returns only enabled count`() = runTest {
        val cal1 = createCalendar()
        val cal2 = createCalendar()
        val cal3 = createCalendar()

        subscriptionsDao.insert(createSubscription(calendarId = cal1, enabled = true))
        subscriptionsDao.insert(createSubscription(calendarId = cal2, enabled = false))
        subscriptionsDao.insert(createSubscription(calendarId = cal3, enabled = true))

        val enabledCount = subscriptionsDao.getEnabledCount()
        assertEquals(2, enabledCount)
    }

    // ==================== Sync Status Update Tests ====================

    @Test
    fun `updateSyncSuccess updates timestamp and clears error`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(lastError = "Previous error"))

        val syncTime = System.currentTimeMillis()
        subscriptionsDao.updateSyncSuccess(
            id = id,
            timestamp = syncTime,
            etag = "\"abc123\"",
            lastModified = "Wed, 21 Oct 2024 07:28:00 GMT"
        )

        val updated = subscriptionsDao.getById(id)
        assertEquals(syncTime, updated?.lastSync)
        assertEquals("\"abc123\"", updated?.etag)
        assertEquals("Wed, 21 Oct 2024 07:28:00 GMT", updated?.lastModified)
        assertNull(updated?.lastError) // Error should be cleared
    }

    @Test
    fun `updateSyncSuccess handles null etag and lastModified`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(
            etag = "old-etag",
            lastModified = "old-date"
        ))

        subscriptionsDao.updateSyncSuccess(
            id = id,
            timestamp = System.currentTimeMillis(),
            etag = null,
            lastModified = null
        )

        val updated = subscriptionsDao.getById(id)
        assertNull(updated?.etag)
        assertNull(updated?.lastModified)
    }

    @Test
    fun `updateSyncError sets error message`() = runTest {
        val id = subscriptionsDao.insert(createSubscription())

        subscriptionsDao.updateSyncError(id, "HTTP 404 Not Found")

        val updated = subscriptionsDao.getById(id)
        assertEquals("HTTP 404 Not Found", updated?.lastError)
    }

    @Test
    fun `clearError removes error message`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(lastError = "Some error"))

        subscriptionsDao.clearError(id)

        val updated = subscriptionsDao.getById(id)
        assertNull(updated?.lastError)
    }

    // ==================== Settings Update Tests ====================

    @Test
    fun `setEnabled toggles enabled status`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(enabled = true))

        subscriptionsDao.setEnabled(id, false)
        assertEquals(false, subscriptionsDao.getById(id)?.enabled)

        subscriptionsDao.setEnabled(id, true)
        assertEquals(true, subscriptionsDao.getById(id)?.enabled)
    }

    @Test
    fun `updateSettings updates name, color, and interval`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(
            name = "Old Name",
            color = 0xFF000000.toInt(),
            syncIntervalHours = 24
        ))

        subscriptionsDao.updateSettings(
            id = id,
            name = "New Name",
            color = 0xFFFF5722.toInt(),
            syncIntervalHours = 6
        )

        val updated = subscriptionsDao.getById(id)
        assertEquals("New Name", updated?.name)
        assertEquals(0xFFFF5722.toInt(), updated?.color)
        assertEquals(6, updated?.syncIntervalHours)
    }

    @Test
    fun `updateName updates only name`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(name = "Old", color = 0xFF000000.toInt()))

        subscriptionsDao.updateName(id, "New Name")

        val updated = subscriptionsDao.getById(id)
        assertEquals("New Name", updated?.name)
        assertEquals(0xFF000000.toInt(), updated?.color) // Color unchanged
    }

    @Test
    fun `updateColor updates only color`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(name = "Keep", color = 0xFF000000.toInt()))

        subscriptionsDao.updateColor(id, 0xFFE91E63.toInt())

        val updated = subscriptionsDao.getById(id)
        assertEquals("Keep", updated?.name) // Name unchanged
        assertEquals(0xFFE91E63.toInt(), updated?.color)
    }

    @Test
    fun `updateSyncInterval updates only interval`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(syncIntervalHours = 24))

        subscriptionsDao.updateSyncInterval(id, 1) // Every hour

        val updated = subscriptionsDao.getById(id)
        assertEquals(1, updated?.syncIntervalHours)
    }

    @Test
    fun `updateUsername updates authentication username`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(username = null))

        subscriptionsDao.updateUsername(id, "user@example.com")

        val updated = subscriptionsDao.getById(id)
        assertEquals("user@example.com", updated?.username)
    }

    @Test
    fun `updateUsername can clear username`() = runTest {
        val id = subscriptionsDao.insert(createSubscription(username = "old-user"))

        subscriptionsDao.updateUsername(id, null)

        val updated = subscriptionsDao.getById(id)
        assertNull(updated?.username)
    }

    // ==================== Utility Query Tests ====================

    @Test
    fun `urlExists returns true for existing URL`() = runTest {
        val testUrl = "https://exists.example.com/calendar.ics"
        subscriptionsDao.insert(createSubscription(url = testUrl))

        assertTrue(subscriptionsDao.urlExists(testUrl))
    }

    @Test
    fun `urlExists returns false for non-existent URL`() = runTest {
        assertFalse(subscriptionsDao.urlExists("https://nonexistent.com/calendar.ics"))
    }

    @Test
    fun `exists returns true for existing ID`() = runTest {
        val id = subscriptionsDao.insert(createSubscription())

        assertTrue(subscriptionsDao.exists(id))
    }

    @Test
    fun `exists returns false for non-existent ID`() = runTest {
        assertFalse(subscriptionsDao.exists(99999L))
    }

    // ==================== Constraint Tests ====================

    @Test
    fun `URL uniqueness is enforced`() = runTest {
        val testUrl = "https://unique.example.com/calendar.ics"
        val cal1 = createCalendar()
        val cal2 = createCalendar()

        subscriptionsDao.insert(createSubscription(url = testUrl, calendarId = cal1))

        // Second insert with same URL should fail
        try {
            subscriptionsDao.insert(createSubscription(url = testUrl, calendarId = cal2))
            assertTrue("Should have thrown exception for duplicate URL", false)
        } catch (e: Exception) {
            // Expected - unique constraint violation
            assertTrue(e is android.database.sqlite.SQLiteConstraintException)
        }
    }

    @Test
    fun `calendar_id uniqueness is enforced`() = runTest {
        val calId = createCalendar()

        subscriptionsDao.insert(createSubscription(
            url = "https://first.com/calendar.ics",
            calendarId = calId
        ))

        // Second insert with same calendar_id should fail
        try {
            subscriptionsDao.insert(createSubscription(
                url = "https://second.com/calendar.ics",
                calendarId = calId
            ))
            assertTrue("Should have thrown exception for duplicate calendar_id", false)
        } catch (e: Exception) {
            // Expected - unique constraint violation
            assertTrue(e is android.database.sqlite.SQLiteConstraintException)
        }
    }

    // ==================== Cascade Delete Tests ====================

    @Test
    fun `deleting calendar cascades to subscription`() = runTest {
        val calId = createCalendar()
        val subId = subscriptionsDao.insert(createSubscription(calendarId = calId))

        // Delete the calendar
        calendarsDao.deleteById(calId)

        // Subscription should be cascade deleted
        val subscription = subscriptionsDao.getById(subId)
        assertNull(subscription)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles webcal URL scheme`() = runTest {
        val webcalUrl = "webcal://example.com/calendar.ics"
        val id = subscriptionsDao.insert(createSubscription(url = webcalUrl))

        val retrieved = subscriptionsDao.getById(id)
        assertEquals(webcalUrl, retrieved?.url)
        // getNormalizedUrl() should convert webcal to https
        assertEquals("https://example.com/calendar.ics", retrieved?.getNormalizedUrl())
    }

    @Test
    fun `handles very long URL`() = runTest {
        val longUrl = "https://example.com/" + "a".repeat(1000) + ".ics"
        val id = subscriptionsDao.insert(createSubscription(url = longUrl))

        val retrieved = subscriptionsDao.getById(id)
        assertEquals(longUrl, retrieved?.url)
    }

    @Test
    fun `handles unicode in name`() = runTest {
        val unicodeName = "日本語カレンダー 📅 Événements"
        val id = subscriptionsDao.insert(createSubscription(name = unicodeName))

        val retrieved = subscriptionsDao.getById(id)
        assertEquals(unicodeName, retrieved?.name)
    }

    @Test
    fun `computed property isDueForSync works correctly`() = runTest {
        val now = System.currentTimeMillis()

        // Due for sync (last sync was 25 hours ago with 24h interval)
        val dueSub = createSubscription(
            lastSync = now - 25 * 60 * 60 * 1000L,
            syncIntervalHours = 24,
            enabled = true
        )
        assertTrue(dueSub.isDueForSync())

        // Not due (last sync was 1 hour ago with 24h interval)
        val notDueSub = createSubscription(
            lastSync = now - 1 * 60 * 60 * 1000L,
            syncIntervalHours = 24,
            enabled = true
        )
        assertFalse(notDueSub.isDueForSync())

        // Disabled subscription is never due
        val disabledSub = createSubscription(
            lastSync = now - 100 * 60 * 60 * 1000L,
            syncIntervalHours = 24,
            enabled = false
        )
        assertFalse(disabledSub.isDueForSync())
    }

    @Test
    fun `computed property requiresAuth works correctly`() = runTest {
        val withAuth = createSubscription(username = "user@example.com")
        assertTrue(withAuth.requiresAuth())

        val withoutAuth = createSubscription(username = null)
        assertFalse(withoutAuth.requiresAuth())

        val withBlankAuth = createSubscription(username = "   ")
        assertFalse(withBlankAuth.requiresAuth())
    }

    @Test
    fun `computed property hasError works correctly`() = runTest {
        val withError = createSubscription(lastError = "Some error")
        assertTrue(withError.hasError())

        val withoutError = createSubscription(lastError = null)
        assertFalse(withoutError.hasError())

        val withBlankError = createSubscription(lastError = "")
        assertFalse(withBlankError.hasError())
    }
}
