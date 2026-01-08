package org.onekash.kashcal.data.preferences

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.SYNC_OPTIONS
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS

/**
 * Unit tests for UserPreferencesRepository validation helpers.
 *
 * Tests cover:
 * - Reminder validation (isValidReminder)
 * - Default reminder retrieval (getDefaultReminder)
 * - Reminder migration when toggling all-day (migrateReminder)
 * - Sync interval validation (isValidSyncInterval)
 * - Closest sync interval selection (getClosestSyncInterval)
 */
class UserPreferencesRepositoryTest {

    private lateinit var repository: UserPreferencesRepository
    private lateinit var mockDataStore: KashCalDataStore

    @Before
    fun setup() {
        mockDataStore = mockk(relaxed = true)
        repository = UserPreferencesRepository(mockDataStore)
    }

    // ==================== Constants Tests ====================

    @Test
    fun `KashCalDataStore has correct timed reminder default`() {
        assertEquals(15, KashCalDataStore.DEFAULT_REMINDER_MINUTES)
    }

    @Test
    fun `KashCalDataStore has correct all-day reminder default`() {
        assertEquals(720, KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES) // 12 hours before
    }

    @Test
    fun `KashCalDataStore has correct sync interval default`() {
        val expectedMs = 1L * 60 * 60 * 1000 // 1 hour
        assertEquals(expectedMs, KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS)
    }

    @Test
    fun `REMINDER_OFF equals KashCalDataStore constant`() {
        assertEquals(KashCalDataStore.REMINDER_OFF, REMINDER_OFF)
    }

    @Test
    fun `TIMED_REMINDER_OPTIONS is non-empty`() {
        assertTrue(TIMED_REMINDER_OPTIONS.isNotEmpty())
    }

    @Test
    fun `ALL_DAY_REMINDER_OPTIONS is non-empty`() {
        assertTrue(ALL_DAY_REMINDER_OPTIONS.isNotEmpty())
    }

    @Test
    fun `SYNC_OPTIONS is non-empty`() {
        assertTrue(SYNC_OPTIONS.isNotEmpty())
    }

    // ==================== isValidSyncInterval Tests ====================

    @Test
    fun `isValidSyncInterval accepts default interval`() {
        assertTrue(repository.isValidSyncInterval(KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS))
    }

    @Test
    fun `isValidSyncInterval rejects interval below minimum`() {
        val tooShort = KashCalDataStore.MIN_SYNC_INTERVAL_MS - 1
        assertFalse(repository.isValidSyncInterval(tooShort))
    }

    @Test
    fun `isValidSyncInterval accepts all defined intervals`() {
        SYNC_OPTIONS.forEach { option ->
            assertTrue("Should accept ${option.label}", repository.isValidSyncInterval(option.intervalMs))
        }
    }

    @Test
    fun `isValidSyncInterval rejects arbitrary value`() {
        val arbitrary = 12345678L
        assertFalse(repository.isValidSyncInterval(arbitrary))
    }

    // ==================== getClosestSyncInterval Tests ====================

    @Test
    fun `getClosestSyncInterval returns exact match`() {
        val expected = KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS
        assertEquals(expected, repository.getClosestSyncInterval(expected))
    }

    @Test
    fun `getClosestSyncInterval returns minimum for too-small value`() {
        val tooSmall = 1000L
        assertEquals(KashCalDataStore.MIN_SYNC_INTERVAL_MS, repository.getClosestSyncInterval(tooSmall))
    }

    @Test
    fun `getClosestSyncInterval finds closest option`() {
        // Given a value between two options, should return closest
        val result = repository.getClosestSyncInterval(KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS + 1000)
        assertTrue(SYNC_OPTIONS.any { it.intervalMs == result })
    }

    // ==================== isValidReminder Tests ====================

    @Test
    fun `isValidReminder accepts REMINDER_OFF for timed events`() {
        assertTrue(repository.isValidReminder(REMINDER_OFF, isAllDay = false))
    }

    @Test
    fun `isValidReminder accepts REMINDER_OFF for all-day events`() {
        assertTrue(repository.isValidReminder(REMINDER_OFF, isAllDay = true))
    }

    @Test
    fun `isValidReminder accepts default timed reminder`() {
        assertTrue(repository.isValidReminder(KashCalDataStore.DEFAULT_REMINDER_MINUTES, isAllDay = false))
    }

    @Test
    fun `isValidReminder accepts default all-day reminder`() {
        assertTrue(repository.isValidReminder(KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES, isAllDay = true))
    }

    @Test
    fun `isValidReminder rejects all-day reminder for timed event`() {
        // 540 minutes (9 AM day of) is only valid for all-day events
        assertFalse(repository.isValidReminder(540, isAllDay = false))
    }

    @Test
    fun `isValidReminder rejects 5 minutes for all-day event`() {
        // 5 minutes before doesn't make sense for all-day events
        assertFalse(repository.isValidReminder(5, isAllDay = true))
    }

    // ==================== getDefaultReminder Tests ====================

    @Test
    fun `getDefaultReminder returns timed default for timed events`() {
        assertEquals(KashCalDataStore.DEFAULT_REMINDER_MINUTES, repository.getDefaultReminder(isAllDay = false))
    }

    @Test
    fun `getDefaultReminder returns all-day default for all-day events`() {
        assertEquals(KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES, repository.getDefaultReminder(isAllDay = true))
    }

    // ==================== migrateReminder Tests ====================

    @Test
    fun `migrateReminder keeps REMINDER_OFF when switching to all-day`() {
        assertEquals(REMINDER_OFF, repository.migrateReminder(REMINDER_OFF, newIsAllDay = true))
    }

    @Test
    fun `migrateReminder keeps REMINDER_OFF when switching to timed`() {
        assertEquals(REMINDER_OFF, repository.migrateReminder(REMINDER_OFF, newIsAllDay = false))
    }

    @Test
    fun `migrateReminder changes invalid timed reminder to all-day default`() {
        // 5 minutes is valid for timed, invalid for all-day
        val result = repository.migrateReminder(5, newIsAllDay = true)
        assertEquals(KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES, result)
    }

    @Test
    fun `migrateReminder changes invalid all-day reminder to timed default`() {
        // 540 minutes (9 AM) is valid for all-day, invalid for timed
        val result = repository.migrateReminder(540, newIsAllDay = false)
        assertEquals(KashCalDataStore.DEFAULT_REMINDER_MINUTES, result)
    }

    @Test
    fun `migrateReminder keeps valid reminder when switching types`() {
        // If a value happens to be valid for both types, keep it
        // REMINDER_OFF is the only value valid for both
        assertEquals(REMINDER_OFF, repository.migrateReminder(REMINDER_OFF, newIsAllDay = true))
        assertEquals(REMINDER_OFF, repository.migrateReminder(REMINDER_OFF, newIsAllDay = false))
    }

    // ==================== Integration Tests ====================

    @Test
    fun `reminder options include REMINDER_OFF for both types`() {
        assertTrue(TIMED_REMINDER_OPTIONS.any { it.minutes == REMINDER_OFF })
        assertTrue(ALL_DAY_REMINDER_OPTIONS.any { it.minutes == REMINDER_OFF })
    }

    @Test
    fun `default reminders are in their respective options lists`() {
        assertTrue(
            TIMED_REMINDER_OPTIONS.any { it.minutes == KashCalDataStore.DEFAULT_REMINDER_MINUTES }
        )
        assertTrue(
            ALL_DAY_REMINDER_OPTIONS.any { it.minutes == KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES }
        )
    }

    @Test
    fun `switching all-day toggle migrates reminder correctly`() {
        // Simulate user creating timed event with 15 min reminder
        var reminder = 15
        var isAllDay = false
        assertTrue(repository.isValidReminder(reminder, isAllDay))

        // User toggles to all-day
        isAllDay = true
        reminder = repository.migrateReminder(reminder, isAllDay)

        // Should now have valid all-day reminder
        assertTrue(repository.isValidReminder(reminder, isAllDay))
        assertEquals(KashCalDataStore.DEFAULT_ALL_DAY_REMINDER_MINUTES, reminder)

        // User toggles back to timed
        isAllDay = false
        reminder = repository.migrateReminder(reminder, isAllDay)

        // Should now have valid timed reminder
        assertTrue(repository.isValidReminder(reminder, isAllDay))
        assertEquals(KashCalDataStore.DEFAULT_REMINDER_MINUTES, reminder)
    }
}
