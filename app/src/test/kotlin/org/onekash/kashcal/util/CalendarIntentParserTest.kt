package org.onekash.kashcal.util

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CalendarIntentParser.
 *
 * Tests verify:
 * - Intent detection (isCalendarInsertIntent)
 * - Intent parsing (all CalendarContract extras)
 * - Edge cases (missing fields, invalid values)
 * - CalendarIntentData helper methods (invitees formatting)
 * - CalendarContract content URI parsing (VIEW/EDIT intents from launchers)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarIntentParserTest {

    // ==================== isCalendarInsertIntent Tests ====================

    @Test
    fun `isCalendarInsertIntent returns true for valid intent`() {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.dir/event"
        }

        assertTrue(CalendarIntentParser.isCalendarInsertIntent(intent))
    }

    @Test
    fun `isCalendarInsertIntent returns false for null intent`() {
        assertFalse(CalendarIntentParser.isCalendarInsertIntent(null))
    }

    @Test
    fun `isCalendarInsertIntent returns false for wrong action`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "vnd.android.cursor.dir/event"
        }

        assertFalse(CalendarIntentParser.isCalendarInsertIntent(intent))
    }

    @Test
    fun `isCalendarInsertIntent returns false for wrong mime type`() {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = "text/plain"
        }

        assertFalse(CalendarIntentParser.isCalendarInsertIntent(intent))
    }

    @Test
    fun `isCalendarInsertIntent returns false for null mime type`() {
        val intent = Intent(Intent.ACTION_INSERT)

        assertFalse(CalendarIntentParser.isCalendarInsertIntent(intent))
    }

    // ==================== parse Tests ====================

    @Test
    fun `parse extracts title correctly`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.Events.TITLE, "Team Meeting")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals("Team Meeting", result!!.first.title)
    }

    @Test
    fun `parse extracts description correctly`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.Events.DESCRIPTION, "Discuss Q1 roadmap")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals("Discuss Q1 roadmap", result!!.first.description)
    }

    @Test
    fun `parse extracts location correctly`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.Events.EVENT_LOCATION, "Conference Room A")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals("Conference Room A", result!!.first.location)
    }

    @Test
    fun `parse extracts start time correctly`() {
        val startTime = 1704369600000L // Jan 4, 2024 12:00:00 UTC
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals(startTime, result!!.first.startTimeMillis)
    }

    @Test
    fun `parse extracts end time correctly`() {
        val endTime = 1704373200000L // Jan 4, 2024 13:00:00 UTC
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals(endTime, result!!.first.endTimeMillis)
    }

    @Test
    fun `parse extracts all-day flag correctly - true`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertTrue(result!!.first.isAllDay)
    }

    @Test
    fun `parse extracts all-day flag correctly - false`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertFalse(result!!.first.isAllDay)
    }

    @Test
    fun `parse extracts rrule correctly`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.Events.RRULE, "FREQ=WEEKLY;BYDAY=MO")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", result!!.first.rrule)
    }

    @Test
    fun `parse extracts invitees from comma-separated string`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(Intent.EXTRA_EMAIL, "alice@example.com, bob@example.com")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals(listOf("alice@example.com", "bob@example.com"), result!!.second)
    }

    @Test
    fun `parse handles single invitee`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(Intent.EXTRA_EMAIL, "alice@example.com")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals(listOf("alice@example.com"), result!!.second)
    }

    @Test
    fun `parse handles missing optional fields gracefully`() {
        val intent = createValidCalendarIntent()
        // No extras added

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        val data = result!!.first
        assertNull(data.title)
        assertNull(data.description)
        assertNull(data.location)
        assertNull(data.startTimeMillis)
        assertNull(data.endTimeMillis)
        assertFalse(data.isAllDay)
        assertNull(data.rrule)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun `parse handles invalid start time (negative)`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertNull(result!!.first.startTimeMillis)
    }

    @Test
    fun `parse handles invalid start time (zero)`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L)
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertNull(result!!.first.startTimeMillis)
    }

    @Test
    fun `parse returns null for non-calendar intent`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "text/plain"
        }

        val result = CalendarIntentParser.parse(intent)

        assertNull(result)
    }

    @Test
    fun `parse returns null for null intent`() {
        val result = CalendarIntentParser.parse(null)

        assertNull(result)
    }

    @Test
    fun `parse trims whitespace from invitees`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(Intent.EXTRA_EMAIL, "  alice@example.com  ,  bob@example.com  ")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals(listOf("alice@example.com", "bob@example.com"), result!!.second)
    }

    @Test
    fun `parse filters empty invitees`() {
        val intent = createValidCalendarIntent().apply {
            putExtra(Intent.EXTRA_EMAIL, "alice@example.com,,bob@example.com,")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        assertEquals(listOf("alice@example.com", "bob@example.com"), result!!.second)
    }

    @Test
    fun `parse extracts all fields together`() {
        val startTime = 1704369600000L
        val endTime = 1704373200000L
        val intent = createValidCalendarIntent().apply {
            putExtra(CalendarContract.Events.TITLE, "Team Standup")
            putExtra(CalendarContract.Events.DESCRIPTION, "Daily sync meeting")
            putExtra(CalendarContract.Events.EVENT_LOCATION, "Zoom")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            putExtra(CalendarContract.Events.RRULE, "FREQ=DAILY")
            putExtra(Intent.EXTRA_EMAIL, "team@example.com")
        }

        val result = CalendarIntentParser.parse(intent)

        assertNotNull(result)
        val (data, invitees) = result!!
        assertEquals("Team Standup", data.title)
        assertEquals("Daily sync meeting", data.description)
        assertEquals("Zoom", data.location)
        assertEquals(startTime, data.startTimeMillis)
        assertEquals(endTime, data.endTimeMillis)
        assertFalse(data.isAllDay)
        assertEquals("FREQ=DAILY", data.rrule)
        assertEquals(listOf("team@example.com"), invitees)
    }

    // ==================== CalendarIntentData.getDescriptionWithInvitees Tests ====================

    @Test
    fun `getDescriptionWithInvitees appends invitees to empty description`() {
        val data = CalendarIntentData(description = null)
        val invitees = listOf("alice@example.com", "bob@example.com")

        val result = data.getDescriptionWithInvitees(invitees)

        assertEquals("Invitees: alice@example.com, bob@example.com", result)
    }

    @Test
    fun `getDescriptionWithInvitees appends invitees to existing description`() {
        val data = CalendarIntentData(description = "Discuss Q1 roadmap")
        val invitees = listOf("alice@example.com")

        val result = data.getDescriptionWithInvitees(invitees)

        assertEquals("Discuss Q1 roadmap\n\nInvitees: alice@example.com", result)
    }

    @Test
    fun `getDescriptionWithInvitees returns original description when no invitees`() {
        val data = CalendarIntentData(description = "Discuss Q1 roadmap")
        val invitees = emptyList<String>()

        val result = data.getDescriptionWithInvitees(invitees)

        assertEquals("Discuss Q1 roadmap", result)
    }

    @Test
    fun `getDescriptionWithInvitees returns empty string for null description and no invitees`() {
        val data = CalendarIntentData(description = null)
        val invitees = emptyList<String>()

        val result = data.getDescriptionWithInvitees(invitees)

        assertEquals("", result)
    }

    @Test
    fun `getDescriptionWithInvitees handles empty description string`() {
        val data = CalendarIntentData(description = "")
        val invitees = listOf("alice@example.com")

        val result = data.getDescriptionWithInvitees(invitees)

        assertEquals("Invitees: alice@example.com", result)
    }

    // ==================== isCalendarContractIntent Tests ====================

    @Test
    fun `isCalendarContractIntent returns true for VIEW with time URI`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/1704369600000")
        }

        assertTrue(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns true for VIEW with events URI`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/events")
        }

        assertTrue(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns true for EDIT with events URI`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
        }

        assertTrue(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns false for null intent`() {
        assertFalse(CalendarIntentParser.isCalendarContractIntent(null))
    }

    @Test
    fun `isCalendarContractIntent returns false for wrong host`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.example.other/time/1704369600000")
        }

        assertFalse(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns false for wrong action (INSERT)`() {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = Uri.parse("content://com.android.calendar/time/1704369600000")
        }

        assertFalse(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns false for no data URI`() {
        val intent = Intent(Intent.ACTION_VIEW)

        assertFalse(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns false for wrong scheme (https)`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://com.android.calendar/time/1704369600000")
        }

        assertFalse(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    @Test
    fun `isCalendarContractIntent returns false for DELETE action`() {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("content://com.android.calendar/events/123")
        }

        assertFalse(CalendarIntentParser.isCalendarContractIntent(intent))
    }

    // ==================== parseCalendarContractUri - GoToDate Tests ====================

    @Test
    fun `parseCalendarContractUri returns GoToDate for valid time millis`() {
        val millis = 1704369600000L // Jan 4, 2024 12:00:00 UTC
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/$millis")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertTrue(result is CalendarContractAction.GoToDate)
        val dayCode = (result as CalendarContractAction.GoToDate).dayCode
        // Verify dayCode is a valid YYYYMMDD (exact value depends on system timezone)
        assertTrue("dayCode $dayCode should be 8 digits", dayCode in 20240104..20240105)
    }

    @Test
    fun `parseCalendarContractUri returns GoToDate with correct dayCode`() {
        // Use DayPagerUtils to compute expected dayCode for comparison
        val millis = 1704369600000L
        val expectedDayCode = DayPagerUtils.msToDayCode(millis)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/$millis")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertTrue(result is CalendarContractAction.GoToDate)
        assertEquals(expectedDayCode, (result as CalendarContractAction.GoToDate).dayCode)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for non-numeric millis`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/abc")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for zero millis`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/0")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for negative millis`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/-1")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for time path with no millis segment`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for time path with empty millis segment`() {
        // content://com.android.calendar/time/ — trailing slash creates empty second segment
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri clamps extreme millis to reasonable range`() {
        // Year 292278994 millis - should be clamped to ~year 2200
        val extremeMillis = Long.MAX_VALUE
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/$extremeMillis")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertTrue(result is CalendarContractAction.GoToDate)
        val dayCode = (result as CalendarContractAction.GoToDate).dayCode
        // Clamped to ~year 2200, dayCode should be 2199XXXX or 2200XXXX range
        assertTrue("dayCode $dayCode should be clamped to reasonable range", dayCode < 22010000)
    }

    // ==================== parseCalendarContractUri - CreateEvent Tests ====================

    @Test
    fun `parseCalendarContractUri returns CreateEvent for EDIT events with begin time`() {
        val startTime = 1704369600000L
        val endTime = 1704373200000L
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertTrue(result is CalendarContractAction.CreateEvent)
        val createEvent = result as CalendarContractAction.CreateEvent
        assertEquals(startTime, createEvent.data.startTimeMillis)
        assertEquals(endTime, createEvent.data.endTimeMillis)
    }

    @Test
    fun `parseCalendarContractUri CreateEvent extracts all extras`() {
        val startTime = 1704369600000L
        val endTime = 1704373200000L
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            putExtra(CalendarContract.Events.TITLE, "Lunch Meeting")
            putExtra(CalendarContract.Events.DESCRIPTION, "Discuss project")
            putExtra(CalendarContract.Events.EVENT_LOCATION, "Cafe")
            putExtra(CalendarContract.Events.RRULE, "FREQ=WEEKLY")
            putExtra(Intent.EXTRA_EMAIL, "alice@example.com, bob@example.com")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertTrue(result is CalendarContractAction.CreateEvent)
        val createEvent = result as CalendarContractAction.CreateEvent
        assertEquals("Lunch Meeting", createEvent.data.title)
        assertEquals("Discuss project", createEvent.data.description)
        assertEquals("Cafe", createEvent.data.location)
        assertEquals(startTime, createEvent.data.startTimeMillis)
        assertEquals(endTime, createEvent.data.endTimeMillis)
        assertEquals("FREQ=WEEKLY", createEvent.data.rrule)
        assertEquals(listOf("alice@example.com", "bob@example.com"), createEvent.invitees)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for EDIT events without extras`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for EDIT events with zero begin time`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L)
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for EDIT events with negative begin time`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -100L)
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri CreateEvent extracts all-day flag`() {
        val startTime = 1704326400000L // Jan 4, 2024 00:00:00 UTC
        val endTime = 1704412800000L   // Jan 5, 2024 00:00:00 UTC
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertTrue(result is CalendarContractAction.CreateEvent)
        val createEvent = result as CalendarContractAction.CreateEvent
        assertTrue(createEvent.data.isAllDay)
        assertEquals(startTime, createEvent.data.startTimeMillis)
        assertEquals(endTime, createEvent.data.endTimeMillis)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for VIEW events with begin time (not EDIT)`() {
        // VIEW on /events should be OpenApp even if extras are present — only EDIT creates
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1704369600000L)
            putExtra(CalendarContract.Events.TITLE, "Meeting")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for VIEW events (not EDIT)`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/events")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    // ==================== parseCalendarContractUri - Fallback Tests ====================

    @Test
    fun `parseCalendarContractUri returns OpenApp for VIEW events with ID`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/events/123")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for EDIT events with ID`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse("content://com.android.calendar/events/123")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for unknown path`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/calendars/5")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns OpenApp for empty path (just authority)`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertEquals(CalendarContractAction.OpenApp, result)
    }

    @Test
    fun `parseCalendarContractUri returns null for null intent`() {
        val result = CalendarIntentParser.parseCalendarContractUri(null)

        assertNull(result)
    }

    @Test
    fun `parseCalendarContractUri returns null for non-matching intent`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://example.com")
        }

        val result = CalendarIntentParser.parseCalendarContractUri(intent)

        assertNull(result)
    }

    // ==================== Helper Methods ====================

    private fun createValidCalendarIntent(): Intent {
        return Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.dir/event"
        }
    }
}
