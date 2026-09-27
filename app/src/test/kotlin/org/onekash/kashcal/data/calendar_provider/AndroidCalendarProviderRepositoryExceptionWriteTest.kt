package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.CalendarErrorException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the real [AndroidCalendarProviderRepository]'s exception writes and series splits over
 * real SQL through [SqliteCalendarProvider].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidCalendarProviderRepositoryExceptionWriteTest {

    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repo: AndroidCalendarProviderRepository
    private var masterId = 0L

    @Before
    fun setup() {
        provider = SqliteCalendarProvider.install()
        repo = SqliteCalendarProvider.repository(ApplicationProvider.getApplicationContext())
        masterId = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL_ID,
            Events.TITLE to "Standup",
            Events.DTSTART to OCCURRENCE_TS - WEEK_MS,
            Events.DURATION to "PT1H",
            Events.RRULE to "FREQ=WEEKLY",
            Events.EVENT_TIMEZONE to "UTC",
            Events._SYNC_ID to "master-sync-id",
        )
    }

    @After
    fun closeProvider() = provider.db.close()

    private fun seedException(status: Int?, deleted: Boolean): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL_ID,
        Events.TITLE to "Standup (moved)",
        Events.DTSTART to OCCURRENCE_TS,
        Events.DTEND to OCCURRENCE_TS + HOUR_MS,
        Events.EVENT_TIMEZONE to "UTC",
        Events.ORIGINAL_ID to masterId,
        Events.ORIGINAL_SYNC_ID to "master-sync-id",
        Events.ORIGINAL_INSTANCE_TIME to OCCURRENCE_TS,
        Events.STATUS to status,
        Events._SYNC_ID to "exception-sync-id",
        Events.DELETED to if (deleted) 1 else 0,
    )

    private fun exceptionRows() = provider.rows(
        SqliteCalendarProvider.EVENTS,
        "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ?",
        masterId.toString(), OCCURRENCE_TS.toString(),
    )

    @Test
    fun `an exception row with no status is live`() = runTest {
        val row = seedException(status = null, deleted = false)

        assertEquals(row, repo.findExceptionEventId(masterId, OCCURRENCE_TS))
    }

    @Test
    fun `deleting an occurrence whose only exception is deleted cancels it with a new row`() = runTest {
        val deletedRow = seedException(status = Events.STATUS_CONFIRMED, deleted = true)

        assertTrue(repo.deleteSingleOccurrence(masterId, OCCURRENCE_TS, isAllDay = false).isSuccess)

        val rows = exceptionRows()
        assertEquals(2, rows.size)
        val old = rows.single { it[Events._ID] == deletedRow.toString() }
        assertEquals("the deleted row keeps its status", Events.STATUS_CONFIRMED.toString(), old[Events.STATUS])
        assertEquals("1", old[Events.DELETED])
        val cancel = rows.single { it[Events._ID] != deletedRow.toString() }
        assertEquals(Events.STATUS_CANCELED.toString(), cancel[Events.STATUS])
        assertEquals("0", cancel[Events.DELETED])
    }

    @Test
    fun `deleting an occurrence that is already cancelled adds no second cancellation`() = runTest {
        seedException(status = Events.STATUS_CANCELED, deleted = false)

        assertTrue(repo.deleteSingleOccurrence(masterId, OCCURRENCE_TS, isAllDay = false).isSuccess)

        val rows = exceptionRows()
        assertEquals(1, rows.size)
        assertEquals(Events.STATUS_CANCELED.toString(), rows.single()[Events.STATUS])
    }

    @Test
    fun `deleting an occurrence cancels its live exception even when an older cancelled row shares it`() = runTest {
        val cancelled = seedException(status = Events.STATUS_CANCELED, deleted = false)
        val live = seedException(status = Events.STATUS_CONFIRMED, deleted = false)

        assertTrue(repo.deleteSingleOccurrence(masterId, OCCURRENCE_TS, isAllDay = false).isSuccess)

        val rows = exceptionRows().associateBy { it[Events._ID] }
        assertEquals(2, rows.size)
        assertEquals(Events.STATUS_CANCELED.toString(), rows.getValue(live.toString())[Events.STATUS])
        assertEquals(Events.STATUS_CANCELED.toString(), rows.getValue(cancelled.toString())[Events.STATUS])
    }

    @Test
    fun `deleting an occurrence with a live exception cancels that exception in place`() = runTest {
        val live = seedException(status = Events.STATUS_CONFIRMED, deleted = false)

        assertTrue(repo.deleteSingleOccurrence(masterId, OCCURRENCE_TS, isAllDay = false).isSuccess)

        val row = exceptionRows().single()
        assertEquals(live.toString(), row[Events._ID])
        assertEquals(Events.STATUS_CANCELED.toString(), row[Events.STATUS])
    }

    // ---- reminders copied onto new rows keep their type, or the write fails ----

    private fun seedMasterReminders() {
        provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to masterId, Reminders.MINUTES to 30, Reminders.METHOD to Reminders.METHOD_EMAIL)
        provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to masterId, Reminders.MINUTES to 10, Reminders.METHOD to Reminders.METHOD_SMS)
    }

    private fun reminderRowsOf(eventId: Long) = provider.reminderRows(eventId)

    private val masterReminders = listOf(10 to Reminders.METHOD_SMS, 30 to Reminders.METHOD_EMAIL)

    private fun eventRowCount() = provider.rows(SqliteCalendarProvider.EVENTS).size

    private suspend fun createExceptionKeepingReminders() = repo.createException(
        calendarId = CAL_ID, masterEventId = masterId, originalInstanceTime = OCCURRENCE_TS,
        title = "Standup", description = null, location = null,
        startTs = OCCURRENCE_TS + HOUR_MS, endTs = OCCURRENCE_TS + 2 * HOUR_MS, isAllDay = false,
        timezone = "UTC", reminders = null,
    )

    private suspend fun splitKeepingReminders(fromTimeMs: Long) = repo.editThisAndFuture(
        masterEventId = masterId, fromTimeMs = fromTimeMs, isAllDay = false, calendarId = CAL_ID,
        title = "Standup", description = null, location = null,
        startTs = fromTimeMs + HOUR_MS, endTs = null, rrule = "FREQ=WEEKLY", duration = "PT1H",
        timezone = "UTC", reminders = null,
    )

    private fun masterRrule() =
        provider.rows(SqliteCalendarProvider.EVENTS, "${Events._ID} = ?", masterId.toString()).single()[Events.RRULE]

    @Test
    fun `a new exception of a series that is gone fails without writing`() = runTest {
        provider.db.execSQL("DELETE FROM ${SqliteCalendarProvider.EVENTS} WHERE ${Events._ID} = ?", arrayOf<Any>(masterId))

        val result = createExceptionKeepingReminders()

        val error = (result.exceptionOrNull() as CalendarErrorException).error
        assertEquals(CalendarError.DeviceCalendar.EventNotFound, error)
        assertEquals(0, eventRowCount())
    }

    @Test
    fun `a new exception fails without writing when the series reminders can't be read`() = runTest {
        seedMasterReminders()
        for (failure in SqliteCalendarProvider.QueryFailure.entries) {
            provider.queryFailures[SqliteCalendarProvider.REMINDERS] = failure
            val rowsBefore = eventRowCount()

            val result = createExceptionKeepingReminders()

            assertTrue("$failure must fail the write", result.isFailure)
            assertEquals("$failure must insert no row", rowsBefore, eventRowCount())
            provider.queryFailures.clear()
        }
    }

    @Test
    fun `splitting a series without reminder minutes gives the future half the series reminders with their types`() = runTest {
        seedMasterReminders()

        val newId = splitKeepingReminders(fromTimeMs = OCCURRENCE_TS).getOrThrow()

        assertFalse(newId == masterId)
        assertEquals(masterReminders, reminderRowsOf(newId))
        assertEquals(masterReminders, reminderRowsOf(masterId))
    }

    @Test
    fun `a split fails without writing when the series reminders can't be read`() = runTest {
        seedMasterReminders()
        for (failure in SqliteCalendarProvider.QueryFailure.entries) {
            provider.queryFailures[SqliteCalendarProvider.REMINDERS] = failure
            val rowsBefore = eventRowCount()

            val result = splitKeepingReminders(fromTimeMs = OCCURRENCE_TS)

            assertTrue("$failure must fail the split", result.isFailure)
            assertEquals("$failure must insert no row", rowsBefore, eventRowCount())
            assertEquals("$failure must leave the series rule", "FREQ=WEEKLY", masterRrule())
            provider.queryFailures.clear()
        }
    }

    @Test
    fun `a split that falls back to editing the series in place leaves its reminders alone`() = runTest {
        // A one-occurrence COUNT rule leaves nothing for a future half at a later occurrence,
        // so the series is edited in place.
        provider.db.execSQL(
            "UPDATE ${SqliteCalendarProvider.EVENTS} SET ${Events.RRULE} = ? WHERE ${Events._ID} = ?",
            arrayOf<Any>("FREQ=WEEKLY;COUNT=1", masterId),
        )
        seedMasterReminders()

        val id = splitKeepingReminders(fromTimeMs = OCCURRENCE_TS).getOrThrow()

        assertEquals(masterId, id)
        assertEquals(masterReminders, reminderRowsOf(masterId))
    }

    @Test
    fun `editing a series from its first occurrence without reminder minutes leaves its reminders alone`() = runTest {
        seedMasterReminders()

        val id = splitKeepingReminders(fromTimeMs = OCCURRENCE_TS - WEEK_MS).getOrThrow()

        assertEquals(masterId, id)
        assertEquals(masterReminders, reminderRowsOf(masterId))
    }

    // ---- guests and tags copied onto a new exception, or the write fails ----

    private fun seedCalendarAccount() = provider.seedCalendar(CAL_ID)

    /** One guest on a series the user was invited to: organizer is someone else. */
    private fun seedMasterGuest(masterHasAttendeeData: Int = 1) {
        provider.db.execSQL(
            "UPDATE ${SqliteCalendarProvider.EVENTS} SET ${Events.ORGANIZER} = ?, ${Events.HAS_ATTENDEE_DATA} = ? WHERE ${Events._ID} = ?",
            arrayOf<Any>("boss@example.test", masterHasAttendeeData, masterId),
        )
        provider.insertRow(
            SqliteCalendarProvider.ATTENDEES,
            Attendees.EVENT_ID to masterId,
            Attendees.ATTENDEE_NAME to "Ana",
            Attendees.ATTENDEE_EMAIL to "ana@example.test",
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE,
            Attendees.ATTENDEE_TYPE to Attendees.TYPE_OPTIONAL,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_TENTATIVE,
            Attendees.ATTENDEE_IDENTITY to "ana-identity",
            Attendees.ATTENDEE_ID_NAMESPACE to "org.example.people",
        )
    }

    /** A tag value this app never wrote, to prove the copy is verbatim. */
    private val foreignTagValue = "Work\\ weekly \\Client"

    private fun seedMasterTags() {
        provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES,
            ExtendedProperties.EVENT_ID to masterId,
            ExtendedProperties.NAME to EXTNAME_CATEGORIES,
            ExtendedProperties.VALUE to foreignTagValue,
        )
    }

    private fun newExceptionId(): Long = exceptionRows().single()[Events._ID]!!.toLong()

    @Test
    fun `a new exception copies every guest column and marks the row as carrying guests`() = runTest {
        seedCalendarAccount()
        seedMasterGuest()

        createExceptionKeepingReminders().getOrThrow()

        val id = newExceptionId()
        val guest = provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", id.toString()).single()
        assertEquals("Ana", guest[Attendees.ATTENDEE_NAME])
        assertEquals("ana@example.test", guest[Attendees.ATTENDEE_EMAIL])
        assertEquals(Attendees.RELATIONSHIP_ATTENDEE.toString(), guest[Attendees.ATTENDEE_RELATIONSHIP])
        assertEquals(Attendees.TYPE_OPTIONAL.toString(), guest[Attendees.ATTENDEE_TYPE])
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE.toString(), guest[Attendees.ATTENDEE_STATUS])
        assertEquals("ana-identity", guest[Attendees.ATTENDEE_IDENTITY])
        assertEquals("org.example.people", guest[Attendees.ATTENDEE_ID_NAMESPACE])
        val row = exceptionRows().single()
        assertEquals("1", row[Events.HAS_ATTENDEE_DATA])
        assertEquals("the series organizer, not the calendar owner", "boss@example.test", row[Events.ORGANIZER])
    }

    @Test
    fun `a new exception copies the series has-attendee-data value as stored`() = runTest {
        seedCalendarAccount()
        seedMasterGuest(masterHasAttendeeData = 0)

        createExceptionKeepingReminders().getOrThrow()

        assertEquals("0", exceptionRows().single()[Events.HAS_ATTENDEE_DATA])
    }

    @Test
    fun `a new exception of a series without guests still keeps the series organizer`() = runTest {
        seedCalendarAccount()
        provider.db.execSQL(
            "UPDATE ${SqliteCalendarProvider.EVENTS} SET ${Events.ORGANIZER} = ? WHERE ${Events._ID} = ?",
            arrayOf<Any>("boss@example.test", masterId),
        )

        createExceptionKeepingReminders().getOrThrow()

        assertEquals("boss@example.test", exceptionRows().single()[Events.ORGANIZER])
    }

    @Test
    fun `a new exception of a series without guests is not marked as carrying guests`() = runTest {
        seedCalendarAccount()

        createExceptionKeepingReminders().getOrThrow()

        assertEquals("0", exceptionRows().single()[Events.HAS_ATTENDEE_DATA])
    }

    @Test
    fun `a new exception copies the series tag value verbatim under the calendar account`() = runTest {
        seedCalendarAccount()
        seedMasterTags()

        createExceptionKeepingReminders().getOrThrow()

        val tag = provider.rows(
            SqliteCalendarProvider.EXTENDED_PROPERTIES, "${ExtendedProperties.EVENT_ID} = ?", newExceptionId().toString(),
        ).single()
        assertEquals(EXTNAME_CATEGORIES, tag[ExtendedProperties.NAME])
        assertEquals(foreignTagValue, tag[ExtendedProperties.VALUE])
    }

    @Test
    fun `a new exception fails without writing when the series guests or tags can't be read`() = runTest {
        seedCalendarAccount()
        seedMasterGuest()
        seedMasterTags()
        for (table in listOf(SqliteCalendarProvider.ATTENDEES, SqliteCalendarProvider.EXTENDED_PROPERTIES)) {
            for (failure in SqliteCalendarProvider.QueryFailure.entries) {
                provider.queryFailures[table] = failure
                val rowsBefore = eventRowCount()

                val result = createExceptionKeepingReminders()

                assertTrue("$table $failure must fail the write", result.isFailure)
                assertEquals("$table $failure must insert no row", rowsBefore, eventRowCount())
                provider.queryFailures.clear()
            }
        }
    }

    @Test
    fun `a new exception of a tagged series fails without writing when the calendar account is unknown`() = runTest {
        seedMasterTags() // no Calendars row, so the tag row has no account to be written under
        val rowsBefore = eventRowCount()

        val result = createExceptionKeepingReminders()

        assertTrue(result.isFailure)
        assertEquals(rowsBefore, eventRowCount())
    }

    // ---- the future half of a split keeps the series' guests, organizer and tags ----

    private suspend fun split(fromTimeMs: Long = OCCURRENCE_TS, categories: List<String>? = null) =
        repo.editThisAndFuture(
            masterEventId = masterId, fromTimeMs = fromTimeMs, isAllDay = false, calendarId = CAL_ID,
            title = "Standup", description = null, location = null,
            startTs = fromTimeMs + HOUR_MS, endTs = null, rrule = "FREQ=WEEKLY", duration = "PT1H",
            timezone = "UTC", reminders = listOf(15), categories = categories,
        )

    private fun eventRow(id: Long) =
        provider.rows(SqliteCalendarProvider.EVENTS, "${Events._ID} = ?", id.toString()).single()

    private fun guestRowsOf(id: Long) =
        provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", id.toString())

    private fun tagValuesOf(id: Long) = provider.rows(
        SqliteCalendarProvider.EXTENDED_PROPERTIES,
        "${ExtendedProperties.EVENT_ID} = ? AND ${ExtendedProperties.NAME} = ?",
        id.toString(), EXTNAME_CATEGORIES,
    ).map { it[ExtendedProperties.VALUE] }

    private fun assertWriteFailed(case: String, result: Result<*>) {
        val error = (result.exceptionOrNull() as? CalendarErrorException)?.error
        assertTrue("$case must fail as a write failure, got $error", error is CalendarError.DeviceCalendar.WriteFailed)
    }

    private fun makeCountLimited() = provider.db.execSQL(
        "UPDATE ${SqliteCalendarProvider.EVENTS} SET ${Events.RRULE} = ? WHERE ${Events._ID} = ?",
        arrayOf<Any>("FREQ=WEEKLY;COUNT=1", masterId),
    )

    @Test
    fun `the future half of a split gets every guest column, the series organizer and the guest flag`() = runTest {
        seedCalendarAccount()
        seedMasterGuest()

        val newId = split().getOrThrow()

        assertFalse(newId == masterId)
        val guest = guestRowsOf(newId).single()
        assertEquals("Ana", guest[Attendees.ATTENDEE_NAME])
        assertEquals("ana@example.test", guest[Attendees.ATTENDEE_EMAIL])
        assertEquals(Attendees.RELATIONSHIP_ATTENDEE.toString(), guest[Attendees.ATTENDEE_RELATIONSHIP])
        assertEquals(Attendees.TYPE_OPTIONAL.toString(), guest[Attendees.ATTENDEE_TYPE])
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE.toString(), guest[Attendees.ATTENDEE_STATUS])
        assertEquals("ana-identity", guest[Attendees.ATTENDEE_IDENTITY])
        assertEquals("org.example.people", guest[Attendees.ATTENDEE_ID_NAMESPACE])
        val row = eventRow(newId)
        assertEquals("the series organizer, not the calendar owner", "boss@example.test", row[Events.ORGANIZER])
        assertEquals("1", row[Events.HAS_ATTENDEE_DATA])
        assertEquals("the series keeps its own guest", 1, guestRowsOf(masterId).size)
    }

    @Test
    fun `the future half of a split copies the series has-attendee-data value as stored`() = runTest {
        seedCalendarAccount()
        seedMasterGuest(masterHasAttendeeData = 0)

        val newId = split().getOrThrow()

        assertEquals("0", eventRow(newId)[Events.HAS_ATTENDEE_DATA])
    }

    @Test
    fun `the future half of a split of a series without guests still keeps the series organizer`() = runTest {
        seedCalendarAccount()
        provider.db.execSQL(
            "UPDATE ${SqliteCalendarProvider.EVENTS} SET ${Events.ORGANIZER} = ? WHERE ${Events._ID} = ?",
            arrayOf<Any>("boss@example.test", masterId),
        )

        val newId = split().getOrThrow()

        assertEquals("boss@example.test", eventRow(newId)[Events.ORGANIZER])
        assertTrue(guestRowsOf(newId).isEmpty())
    }

    @Test
    fun `the future half of a split copies the series tag value verbatim under the calendar account`() = runTest {
        seedCalendarAccount()
        seedMasterTags()

        val newId = split(categories = null).getOrThrow()

        assertEquals(listOf(foreignTagValue), tagValuesOf(newId))
        assertEquals(listOf(foreignTagValue), tagValuesOf(masterId))
    }

    @Test
    fun `tags edited during a split replace the series tags on the future half only`() = runTest {
        seedCalendarAccount()
        seedMasterTags()

        val newId = split(categories = listOf("Travel")).getOrThrow()

        assertEquals(listOf(listOf("Travel")), tagValuesOf(newId).map { decodeCategories(it) })
        assertEquals("the series keeps its tags", listOf(foreignTagValue), tagValuesOf(masterId))
    }

    @Test
    fun `tags cleared during a split leave the future half with none`() = runTest {
        seedCalendarAccount()
        seedMasterTags()

        val newId = split(categories = emptyList()).getOrThrow()

        assertTrue(tagValuesOf(newId).isEmpty())
        assertEquals(listOf(foreignTagValue), tagValuesOf(masterId))
    }

    @Test
    fun `a split fails without writing when the series guests, organizer or tags can't be read`() = runTest {
        seedCalendarAccount()
        seedMasterGuest()
        seedMasterTags()
        val cases = SqliteCalendarProvider.QueryFailure.entries.flatMap { failure ->
            listOf(
                "attendees $failure" to { provider.queryFailures[SqliteCalendarProvider.ATTENDEES] = failure },
                "tags $failure" to { provider.queryFailures[SqliteCalendarProvider.EXTENDED_PROPERTIES] = failure },
                "organizer $failure" to { provider.projectionFailures[Events.ORGANIZER] = failure },
            )
        }
        for ((name, arm) in cases) {
            arm()
            val rowsBefore = eventRowCount()

            val result = split()

            assertWriteFailed(name, result)
            assertEquals("$name must insert no row", rowsBefore, eventRowCount())
            assertEquals("$name must leave the series rule", "FREQ=WEEKLY", masterRrule())
            provider.queryFailures.clear()
            provider.projectionFailures.clear()
        }
    }

    @Test
    fun `a split that must write tags fails without writing when the calendar account is unknown`() = runTest {
        // No Calendars row, so a tag row has no account to be written under.
        seedMasterTags()
        for (categories in listOf(null, listOf("Travel"))) {
            val rowsBefore = eventRowCount()

            val result = split(categories = categories)

            assertWriteFailed("categories=$categories", result)
            assertEquals("categories=$categories must insert no row", rowsBefore, eventRowCount())
            assertEquals("FREQ=WEEKLY", masterRrule())
        }
    }

    /**
     * Seeds a tagged series with a guest and returns a split the repository must answer by
     * editing the series itself: a split at the first occurrence, or at a later one of a
     * count-limited series with nothing left for a future half. Asserts the split point is after
     * the series start only in the count-limited case, so the branch under test is the one taken.
     */
    private fun inPlaceSplit(countLimited: Boolean): suspend (List<String>?) -> Long {
        seedCalendarAccount()
        seedMasterGuest()
        seedMasterTags()
        if (countLimited) makeCountLimited()
        val fromTimeMs = if (countLimited) OCCURRENCE_TS else OCCURRENCE_TS - WEEK_MS
        val seriesStart = eventRow(masterId)[Events.DTSTART]!!.toLong()
        assertEquals(
            "the split point decides the branch",
            countLimited, fromTimeMs > seriesStart,
        )
        return { categories -> split(fromTimeMs = fromTimeMs, categories = categories).getOrThrow() }
    }

    @Test
    fun `a split at the first occurrence keeps the series guests and tags`() = runTest {
        val run = inPlaceSplit(countLimited = false)

        assertEquals(masterId, run(null))
        assertEquals(1, guestRowsOf(masterId).size)
        assertEquals(listOf(foreignTagValue), tagValuesOf(masterId))
    }

    @Test
    fun `a split at the first occurrence applies edited tags to the series and keeps its guests`() = runTest {
        val run = inPlaceSplit(countLimited = false)

        assertEquals(masterId, run(listOf("Travel")))
        assertEquals(listOf(listOf("Travel")), tagValuesOf(masterId).map { decodeCategories(it) })
        assertEquals(1, guestRowsOf(masterId).size)
    }

    @Test
    fun `a count-limited split edited in place keeps the series guests and tags`() = runTest {
        val run = inPlaceSplit(countLimited = true)

        assertEquals(masterId, run(null))
        assertEquals(1, guestRowsOf(masterId).size)
        assertEquals(listOf(foreignTagValue), tagValuesOf(masterId))
    }

    @Test
    fun `a count-limited split edited in place applies edited tags and keeps the guests`() = runTest {
        val run = inPlaceSplit(countLimited = true)

        assertEquals(masterId, run(listOf("Travel")))
        assertEquals(listOf(listOf("Travel")), tagValuesOf(masterId).map { decodeCategories(it) })
        assertEquals(1, guestRowsOf(masterId).size)
    }

    companion object {
        const val CAL_ID = 7L
        const val OCCURRENCE_TS = 1_709_650_800_000L
        const val HOUR_MS = 3_600_000L
        const val WEEK_MS = 7L * 24 * HOUR_MS
    }
}
