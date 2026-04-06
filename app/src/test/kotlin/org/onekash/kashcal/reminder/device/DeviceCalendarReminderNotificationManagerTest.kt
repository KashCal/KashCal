package org.onekash.kashcal.reminder.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DeviceCalendarReminderNotificationManager (Phase 4 - Chunk 5).
 *
 * Tests cover:
 * - Notification ID generation (non-collision with Room reminders)
 * - Notification ID uniqueness for different events
 * - Notification ID range verification
 */
class DeviceCalendarReminderNotificationManagerTest {

    // ========== Notification ID Range ==========

    @Test
    fun `notification ID base is 20000`() {
        assertEquals(20000, DeviceCalendarReminderNotificationManager.NOTIFICATION_ID_BASE)
    }

    @Test
    fun `notification IDs are in range 20000-29999`() {
        // Test various eventId and occurrenceTs combinations
        val testCases = listOf(
            Pair(1L, 1000000L),
            Pair(999L, 9999999L),
            Pair(123456L, 1709251200000L),
            Pair(Long.MAX_VALUE, Long.MAX_VALUE),
            Pair(0L, 0L)
        )

        for ((eventId, occurrenceTs) in testCases) {
            val notificationId = calculateNotificationId(eventId, occurrenceTs)
            assertTrue(
                "Notification ID $notificationId should be >= 20000",
                notificationId >= 20000
            )
            assertTrue(
                "Notification ID $notificationId should be < 30000",
                notificationId < 30000
            )
        }
    }

    @Test
    fun `notification IDs do not overlap with Room reminder range`() {
        // Room reminders use 2000-11999
        val testCases = listOf(
            Pair(1L, 1000000L),
            Pair(100L, 1709251200000L),
            Pair(9999L, 9999999999L)
        )

        for ((eventId, occurrenceTs) in testCases) {
            val notificationId = calculateNotificationId(eventId, occurrenceTs)
            assertTrue(
                "Notification ID $notificationId should not be in Room range (2000-11999)",
                notificationId < 2000 || notificationId >= 12000
            )
        }
    }

    // ========== Notification ID Uniqueness ==========

    @Test
    fun `different events produce different notification IDs`() {
        val id1 = calculateNotificationId(123L, 1709251200000L)
        val id2 = calculateNotificationId(456L, 1709251200000L)

        assertNotEquals("Different events should have different IDs", id1, id2)
    }

    @Test
    fun `same event different occurrences produce different notification IDs`() {
        val id1 = calculateNotificationId(123L, 1709251200000L)
        val id2 = calculateNotificationId(123L, 1709337600000L) // Next day

        assertNotEquals("Different occurrences should have different IDs", id1, id2)
    }

    @Test
    fun `same event same occurrence produces same notification ID`() {
        val id1 = calculateNotificationId(123L, 1709251200000L)
        val id2 = calculateNotificationId(123L, 1709251200000L)

        assertEquals("Same event/occurrence should have same ID", id1, id2)
    }

    // ========== Intent Actions ==========

    @Test
    fun `snooze action constant is correct`() {
        assertEquals(
            "org.onekash.kashcal.DEVICE_SNOOZE_REMINDER",
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_SNOOZE
        )
    }

    @Test
    fun `dismiss action constant is correct`() {
        assertEquals(
            "org.onekash.kashcal.DEVICE_DISMISS_REMINDER",
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_DISMISS
        )
    }

    @Test
    fun `show event action constant is correct`() {
        assertEquals(
            "org.onekash.kashcal.DEVICE_SHOW_EVENT",
            DeviceCalendarReminderNotificationManager.ACTION_DEVICE_SHOW_EVENT
        )
    }

    // ========== Request Code Collision Fix ==========

    @Test
    fun `request code range is 100_000`() {
        assertEquals(100_000, DeviceCalendarReminderNotificationManager.REQUEST_CODE_RANGE)
    }

    @Test
    fun `open request codes are always positive`() {
        val testCases = listOf(
            Pair(Long.MAX_VALUE, 1L),
            Pair(1L, Long.MAX_VALUE),
            Pair(0L, 0L),
            Pair(123L, 456L),
            Pair(999_999L, 1_709_251_200_000L)
        )

        for ((eventId, occurrenceTs) in testCases) {
            val code = DeviceCalendarReminderNotificationManager.computeOpenRequestCode(eventId, occurrenceTs)
            assertTrue("Open request code $code must be positive", code > 0)
        }
    }

    @Test
    fun `snooze action request codes are always positive`() {
        val testCases = listOf(
            Pair(Long.MAX_VALUE, 1L),
            Pair(0L, 0L),
            Pair(123L, 456L)
        )

        for ((eventId, occurrenceTs) in testCases) {
            val code = DeviceCalendarReminderNotificationManager.computeSnoozeRequestCode(eventId, occurrenceTs)
            assertTrue("Snooze request code $code must be positive", code > 0)
        }
    }

    @Test
    fun `dismiss request codes are always positive`() {
        val testIds = listOf(0, 20000, 20500, 29999, Int.MAX_VALUE)

        for (notificationId in testIds) {
            val code = DeviceCalendarReminderNotificationManager.computeDismissRequestCode(notificationId)
            assertTrue("Dismiss request code $code must be positive", code > 0)
        }
    }

    @Test
    fun `request code ranges do not overlap`() {
        // Generate request codes from all three types and verify no overlap
        val openCodes = mutableSetOf<Int>()
        val snoozeCodes = mutableSetOf<Int>()
        val dismissCodes = mutableSetOf<Int>()

        for (i in 1L..100L) {
            val eventId = i * 13
            val occurrenceTs = 1_700_000_000_000L + (i * 60_000L)
            openCodes.add(DeviceCalendarReminderNotificationManager.computeOpenRequestCode(eventId, occurrenceTs))
            snoozeCodes.add(DeviceCalendarReminderNotificationManager.computeSnoozeRequestCode(eventId, occurrenceTs))
            dismissCodes.add(DeviceCalendarReminderNotificationManager.computeDismissRequestCode((20000 + i).toInt()))
        }

        // No set should intersect with another
        assertTrue("Open and snooze codes must not overlap", openCodes.intersect(snoozeCodes).isEmpty())
        assertTrue("Open and dismiss codes must not overlap", openCodes.intersect(dismissCodes).isEmpty())
        assertTrue("Snooze and dismiss codes must not overlap", snoozeCodes.intersect(dismissCodes).isEmpty())
    }

    @Test
    fun `request codes do not collide for 50 different events`() {
        val openCodes = mutableSetOf<Int>()
        val collisions = mutableListOf<String>()

        for (i in 1L..50L) {
            val eventId = i * 7
            val occurrenceTs = 1_700_000_000_000L + (i * 3_600_000L)
            val code = DeviceCalendarReminderNotificationManager.computeOpenRequestCode(eventId, occurrenceTs)
            if (!openCodes.add(code)) {
                collisions.add("eventId=$eventId, occurrenceTs=$occurrenceTs -> code=$code")
            }
        }

        assertTrue(
            "Expected 0 collisions among 50 events, got ${collisions.size}: $collisions",
            collisions.isEmpty()
        )
    }

    // ========== Test Helper ==========

    /**
     * Mirror the notification ID calculation from the manager.
     */
    private fun calculateNotificationId(eventId: Long, occurrenceTs: Long): Int {
        val combined = (eventId xor (occurrenceTs / 60000)) % 10000
        return (DeviceCalendarReminderNotificationManager.NOTIFICATION_ID_BASE + combined).toInt()
    }
}
