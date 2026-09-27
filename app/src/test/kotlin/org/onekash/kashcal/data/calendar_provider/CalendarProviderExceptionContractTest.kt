package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts how [CalendarProviderRepository] finds, writes and cancels the exception rows of a
 * device series: which occurrence a row names (the series' all-day flag decides, not the
 * caller's), and which reminders, guests and tags updates, new exceptions and splits keep or
 * write. The same assertions run against [FakeCalendarProviderRepository] and against the real
 * repository over [SqliteCalendarProvider]. The ViewModel and writer tests run on the fake, so
 * this keeps the fake honest and fails when the real queries regress.
 *
 * Reminder rows carry a type (METHOD). The device only fires alerts itself, but an email or SMS
 * reminder is stored so the sync adapter can send it back to the server, so a write that doesn't
 * mean to change reminders must not turn them into alerts.
 *
 * A deleted row (DELETED = 1, waiting for the sync adapter to purge it) and a cancelled row
 * (STATUS_CANCELED) both show as no occurrence at all, so neither may be picked as "the
 * occurrence's exception".
 */
abstract class CalendarProviderExceptionContractTest {

    protected abstract val repo: CalendarProviderRepository

    // Seed and read rows in whichever store backs [repo].
    /** A weekly series, timed at [OCCURRENCE_TS] or all-day from [DAY0]. */
    protected abstract fun seedMaster(allDay: Boolean = false): Long
    protected abstract fun seedException(masterId: Long, originalInstanceTime: Long, status: Int, deleted: Boolean): Long
    protected abstract fun seedOneOff(): Long
    protected abstract fun seedReminder(eventId: Long, minutes: Int, method: Int)

    /** An event's reminder rows as (minutes, method), sorted by minutes. */
    protected abstract fun reminderRowsOf(eventId: Long): List<Pair<Int, Int>>

    /** A guest row as the fake and the provider both store it. */
    protected data class Guest(val name: String?, val email: String, val relationship: Int, val status: Int)

    protected abstract fun seedGuests(eventId: Long, guests: List<Guest>)
    protected abstract fun guestsOf(eventId: Long): List<Guest>
    protected abstract fun seedTags(eventId: Long, tags: List<String>)
    protected abstract fun tagsOf(eventId: Long): List<String>

    @Test
    fun `a live exception row is found for its occurrence`() = runTest {
        val master = seedMaster()
        val live = seedException(master, OCCURRENCE_TS, Events.STATUS_CONFIRMED, deleted = false)

        assertEquals(live, repo.findExceptionEventId(master, OCCURRENCE_TS))
    }

    @Test
    fun `a deleted exception row is not the occurrence's exception`() = runTest {
        val master = seedMaster()
        seedException(master, OCCURRENCE_TS, Events.STATUS_CONFIRMED, deleted = true)

        assertNull(repo.findExceptionEventId(master, OCCURRENCE_TS))
    }

    @Test
    fun `a cancelled exception row is not the occurrence's exception`() = runTest {
        val master = seedMaster()
        seedException(master, OCCURRENCE_TS, Events.STATUS_CANCELED, deleted = false)

        assertNull(repo.findExceptionEventId(master, OCCURRENCE_TS))
    }

    @Test
    fun `the live row wins when a dead row shares its occurrence`() = runTest {
        val master = seedMaster()
        seedException(master, OCCURRENCE_TS, Events.STATUS_CANCELED, deleted = false)
        seedException(master, OCCURRENCE_TS, Events.STATUS_CONFIRMED, deleted = true)
        val live = seedException(master, OCCURRENCE_TS, Events.STATUS_TENTATIVE, deleted = false)

        assertEquals(live, repo.findExceptionEventId(master, OCCURRENCE_TS))
    }

    @Test
    fun `an exception of another occurrence is not returned`() = runTest {
        val master = seedMaster()
        seedException(master, OCCURRENCE_TS + WEEK_MS, Events.STATUS_CONFIRMED, deleted = false)

        assertNull(repo.findExceptionEventId(master, OCCURRENCE_TS))
    }

    // ---- reminders keep their type ----

    private fun seedEmailAndSms(eventId: Long) {
        seedReminder(eventId, 30, Reminders.METHOD_EMAIL)
        seedReminder(eventId, 10, Reminders.METHOD_SMS)
    }

    private val emailAndSms = listOf(10 to Reminders.METHOD_SMS, 30 to Reminders.METHOD_EMAIL)

    private suspend fun update(eventId: Long, reminders: List<Int>?) = repo.updateEvent(
        eventId = eventId, title = "Standup", description = null, location = null,
        startTs = OCCURRENCE_TS + HOUR_MS, endTs = OCCURRENCE_TS + 2 * HOUR_MS, isAllDay = false,
        rrule = null, duration = null, timezone = "UTC", reminders = reminders,
    )

    private suspend fun createException(masterId: Long, reminders: List<Int>?) = repo.createException(
        calendarId = CAL_ID, masterEventId = masterId, originalInstanceTime = OCCURRENCE_TS,
        title = "Standup", description = null, location = null,
        startTs = OCCURRENCE_TS + HOUR_MS, endTs = OCCURRENCE_TS + 2 * HOUR_MS, isAllDay = false,
        timezone = "UTC", reminders = reminders,
    )

    @Test
    fun `an update that leaves reminders alone keeps their types`() = runTest {
        val event = seedOneOff()
        seedEmailAndSms(event)

        assertTrue(update(event, reminders = null).isSuccess)

        assertEquals(emailAndSms, reminderRowsOf(event))
    }

    @Test
    fun `an update given reminder minutes writes them as alerts`() = runTest {
        val event = seedOneOff()
        seedEmailAndSms(event)

        assertTrue(update(event, reminders = listOf(15)).isSuccess)

        assertEquals(listOf(15 to Reminders.METHOD_ALERT), reminderRowsOf(event))
    }

    @Test
    fun `a new exception without reminder minutes copies the series reminders with their types`() = runTest {
        val master = seedMaster()
        seedEmailAndSms(master)

        val exception = createException(master, reminders = null).getOrThrow()

        assertEquals(emailAndSms, reminderRowsOf(exception))
        assertEquals("the series keeps its own reminders", emailAndSms, reminderRowsOf(master))
    }

    @Test
    fun `a new exception given reminder minutes writes them as alerts`() = runTest {
        val master = seedMaster()
        seedEmailAndSms(master)

        val exception = createException(master, reminders = listOf(5)).getOrThrow()

        assertEquals(listOf(5 to Reminders.METHOD_ALERT), reminderRowsOf(exception))
    }

    // ---- a new exception keeps the series' guests and tags ----

    protected val seriesGuests = listOf(
        Guest("Me", "me@example.test", Attendees.RELATIONSHIP_ORGANIZER, Attendees.ATTENDEE_STATUS_ACCEPTED),
        Guest("Ana", "ana@example.test", Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS_TENTATIVE),
        Guest(null, "bo@example.test", Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS_NONE),
    )

    @Test
    fun `a new exception copies the series guests and tags`() = runTest {
        val master = seedMaster()
        seedGuests(master, seriesGuests)
        seedTags(master, listOf("Work", "Weekly"))

        val exception = createException(master, reminders = listOf(15)).getOrThrow()

        assertEquals(seriesGuests, guestsOf(exception))
        assertEquals(listOf("Work", "Weekly"), tagsOf(exception))
        assertEquals("the series keeps its guests", seriesGuests, guestsOf(master))
        assertEquals("the series keeps its tags", listOf("Work", "Weekly"), tagsOf(master))
    }

    @Test
    fun `a new exception of a series without guests or tags gets none`() = runTest {
        val master = seedMaster()

        val exception = createException(master, reminders = listOf(15)).getOrThrow()

        assertEquals(emptyList<Guest>(), guestsOf(exception))
        assertEquals(emptyList<String>(), tagsOf(exception))
    }

    // ---- the future half of a split keeps the series' guests and tags ----

    /** Split a week after the seeded series starts, so a new row is written. */
    private suspend fun splitLater(masterId: Long, categories: List<String>?) = repo.editThisAndFuture(
        masterEventId = masterId, fromTimeMs = OCCURRENCE_TS + WEEK_MS, isAllDay = false, calendarId = CAL_ID,
        title = "Standup", description = null, location = null,
        startTs = OCCURRENCE_TS + WEEK_MS + HOUR_MS, endTs = null, rrule = "FREQ=WEEKLY", duration = "PT1H",
        timezone = "UTC", reminders = listOf(15), categories = categories,
    )

    @Test
    fun `the future half of a split copies the series guests and tags`() = runTest {
        val master = seedMaster()
        seedGuests(master, seriesGuests)
        seedTags(master, listOf("Work", "Weekly"))

        val future = splitLater(master, categories = null).getOrThrow()

        assertTrue(future != master)
        assertEquals(seriesGuests, guestsOf(future))
        assertEquals(listOf("Work", "Weekly"), tagsOf(future))
        assertEquals("the series keeps its guests", seriesGuests, guestsOf(master))
        assertEquals("the series keeps its tags", listOf("Work", "Weekly"), tagsOf(master))
    }

    @Test
    fun `the future half of a split of a series without guests or tags gets none`() = runTest {
        val master = seedMaster()

        val future = splitLater(master, categories = null).getOrThrow()

        assertEquals(emptyList<Guest>(), guestsOf(future))
        assertEquals(emptyList<String>(), tagsOf(future))
    }

    @Test
    fun `tags edited during a split win over the series tags on the future half`() = runTest {
        val master = seedMaster()
        seedGuests(master, seriesGuests)
        seedTags(master, listOf("Work", "Weekly"))

        val future = splitLater(master, categories = listOf("Travel")).getOrThrow()

        assertEquals(listOf("Travel"), tagsOf(future))
        assertEquals("guests are still copied", seriesGuests, guestsOf(future))
        assertEquals(listOf("Work", "Weekly"), tagsOf(master))
    }

    // ---- the series' all-day flag decides which occurrence an exception names ----

    /** The occurrence a created exception row names: (ORIGINAL_INSTANCE_TIME, ORIGINAL_ALL_DAY). */
    protected abstract fun slotOfException(exceptionId: Long): Pair<Long, Boolean>

    /** The occurrence the latest single-occurrence delete of [masterId] cancelled. */
    protected abstract fun slotOfCancellation(masterId: Long): Pair<Long, Boolean>

    private suspend fun createExceptionAs(masterId: Long, originalInstanceTime: Long, isAllDay: Boolean, startTs: Long, endTs: Long) =
        repo.createException(
            calendarId = CAL_ID, masterEventId = masterId, originalInstanceTime = originalInstanceTime,
            title = "Standup", description = null, location = null,
            startTs = startTs, endTs = endTs, isAllDay = isAllDay,
            timezone = "UTC", reminders = listOf(15),
        )

    @Test
    fun `an occurrence of a timed series saved all-day still names the timed occurrence`() = runTest {
        val master = seedMaster(allDay = false)

        val exception = createExceptionAs(master, OCCURRENCE_TS, isAllDay = true, startTs = DAY0, endTs = DAY0 + DAY_MS - 1).getOrThrow()

        assertEquals(OCCURRENCE_TS to false, slotOfException(exception))
    }

    @Test
    fun `an occurrence of an all-day series saved timed still names that day`() = runTest {
        val master = seedMaster(allDay = true)
        val day = DAY0 + WEEK_MS

        val exception = createExceptionAs(master, day + 5 * HOUR_MS, isAllDay = false, startTs = day + 9 * HOUR_MS, endTs = day + 10 * HOUR_MS)
            .getOrThrow()

        assertEquals(day to true, slotOfException(exception))
    }

    @Test
    fun `the exception of a timed series is found whatever all-day flag the caller passes`() = runTest {
        val master = seedMaster(allDay = false)
        val live = seedException(master, OCCURRENCE_TS, Events.STATUS_CONFIRMED, deleted = false)

        assertEquals(live, repo.findExceptionEventId(master, OCCURRENCE_TS, isAllDay = true))
    }

    @Test
    fun `deleting an occurrence of a timed series cancels the timed occurrence whatever flag the caller passes`() = runTest {
        val master = seedMaster(allDay = false)

        assertTrue(repo.deleteSingleOccurrence(master, OCCURRENCE_TS, isAllDay = true).isSuccess)

        assertEquals(OCCURRENCE_TS to false, slotOfCancellation(master))
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val CAL_ID = 7L
        const val OCCURRENCE_TS = 1_709_650_800_000L
        /** Midnight UTC of [OCCURRENCE_TS]'s day. */
        const val DAY0 = 1_709_596_800_000L
        const val WEEK_MS = 7L * 24 * HOUR_MS
    }
}

/** The contract on [FakeCalendarProviderRepository]. */
class FakeCalendarProviderExceptionContractTest : CalendarProviderExceptionContractTest() {
    private val fake = FakeCalendarProviderRepository()
    private var nextId = 500L
    override val repo: CalendarProviderRepository get() = fake

    override fun seedMaster(allDay: Boolean): Long {
        val id = nextId++
        fake.deviceEvents[id] = if (allDay) {
            deviceEventRow(id, rrule = "FREQ=WEEKLY").copy(startTs = DAY0, duration = "P1D", isAllDay = true)
        } else {
            deviceEventRow(id, rrule = "FREQ=WEEKLY")
        }
        return id
    }

    override fun slotOfException(exceptionId: Long): Pair<Long, Boolean> =
        fake.createdExceptions.single { it.resultId == exceptionId }.slot!!

    override fun slotOfCancellation(masterId: Long): Pair<Long, Boolean> =
        fake.deletedOccurrences.last { it.masterEventId == masterId }.slot!!

    override fun seedException(masterId: Long, originalInstanceTime: Long, status: Int, deleted: Boolean): Long {
        val id = nextId++
        fake.deviceEvents[id] = deviceEventRow(
            id,
            originalId = masterId,
            originalInstanceTime = originalInstanceTime,
            status = status,
        )
        if (deleted) fake.softDeletedEventIds.add(id)
        return id
    }

    override fun seedOneOff(): Long {
        val id = nextId++
        fake.deviceEvents[id] = deviceEventRow(id).copy(duration = null, endTs = OCCURRENCE_TS + HOUR_MS)
        return id
    }

    override fun seedReminder(eventId: Long, minutes: Int, method: Int) {
        fake.reminderRows[eventId] =
            fake.reminderRows[eventId].orEmpty() + FakeCalendarProviderRepository.ReminderRow(minutes, method)
    }

    override fun reminderRowsOf(eventId: Long): List<Pair<Int, Int>> =
        fake.reminderRows[eventId].orEmpty().map { it.minutes to it.method }.sortedBy { it.first }

    override fun seedGuests(eventId: Long, guests: List<Guest>) {
        fake.deviceAttendees[eventId] = guests.mapIndexed { i, g ->
            DeviceAttendee(id = 900L + i, name = g.name, email = g.email, relationship = g.relationship, status = g.status)
        }
    }

    override fun guestsOf(eventId: Long): List<Guest> =
        fake.deviceAttendees[eventId].orEmpty().map { Guest(it.name, it.email!!, it.relationship, it.status) }

    override fun seedTags(eventId: Long, tags: List<String>) {
        fake.eventCategories[eventId] = tags
    }

    override fun tagsOf(eventId: Long): List<String> = fake.eventCategories[eventId].orEmpty()

    private fun deviceEventRow(
        id: Long,
        rrule: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        status: Int = Events.STATUS_CONFIRMED,
    ) = DeviceEvent(
        id = id, calendarId = CAL_ID, title = "Standup", description = null, location = null,
        startTs = originalInstanceTime ?: OCCURRENCE_TS, endTs = null, duration = "PT1H", isAllDay = false,
        rrule = rrule, rdate = null, exdate = null, exrule = null, timezone = "UTC",
        originalId = originalId, originalInstanceTime = originalInstanceTime, status = status,
        availability = 0, accessLevel = 700, calendarColor = null, eventColor = null,
    )
}

/** The contract on the real [AndroidCalendarProviderRepository] over real SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidCalendarProviderExceptionContractTest : CalendarProviderExceptionContractTest() {
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var real: AndroidCalendarProviderRepository
    override val repo: CalendarProviderRepository get() = real

    @Before
    fun installProvider() {
        provider = SqliteCalendarProvider.install()
        real = SqliteCalendarProvider.repository(ApplicationProvider.getApplicationContext())
        provider.seedCalendar(CAL_ID)
    }

    override fun seedGuests(eventId: Long, guests: List<Guest>) {
        for (g in guests) {
            provider.insertRow(
                SqliteCalendarProvider.ATTENDEES,
                Attendees.EVENT_ID to eventId,
                Attendees.ATTENDEE_NAME to g.name,
                Attendees.ATTENDEE_EMAIL to g.email,
                Attendees.ATTENDEE_RELATIONSHIP to g.relationship,
                Attendees.ATTENDEE_STATUS to g.status,
                Attendees.ATTENDEE_TYPE to Attendees.TYPE_REQUIRED,
            )
        }
    }

    override fun guestsOf(eventId: Long): List<Guest> =
        provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", eventId.toString()).map {
            Guest(
                it[Attendees.ATTENDEE_NAME], it[Attendees.ATTENDEE_EMAIL]!!,
                it[Attendees.ATTENDEE_RELATIONSHIP]!!.toInt(), it[Attendees.ATTENDEE_STATUS]!!.toInt(),
            )
        }

    override fun seedTags(eventId: Long, tags: List<String>) {
        provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES,
            ExtendedProperties.EVENT_ID to eventId,
            ExtendedProperties.NAME to EXTNAME_CATEGORIES,
            ExtendedProperties.VALUE to encodeCategories(tags),
        )
    }

    override fun tagsOf(eventId: Long): List<String> =
        provider.rows(
            SqliteCalendarProvider.EXTENDED_PROPERTIES,
            "${ExtendedProperties.EVENT_ID} = ? AND ${ExtendedProperties.NAME} = ?",
            eventId.toString(), EXTNAME_CATEGORIES,
        ).flatMap { decodeCategories(it[ExtendedProperties.VALUE]) }

    @After
    fun closeProvider() = provider.db.close()

    override fun seedMaster(allDay: Boolean): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL_ID,
        Events.TITLE to "Standup",
        Events.DTSTART to if (allDay) DAY0 else OCCURRENCE_TS,
        Events.DURATION to if (allDay) "P1D" else "PT1H",
        Events.RRULE to "FREQ=WEEKLY",
        Events.EVENT_TIMEZONE to "UTC",
        Events.ALL_DAY to if (allDay) 1 else 0,
        Events._SYNC_ID to "master-sync-id",
    )

    private fun slotOf(row: Map<String, String?>): Pair<Long, Boolean> =
        row[Events.ORIGINAL_INSTANCE_TIME]!!.toLong() to (row[Events.ORIGINAL_ALL_DAY] == "1")

    override fun slotOfException(exceptionId: Long): Pair<Long, Boolean> =
        slotOf(provider.row(SqliteCalendarProvider.EVENTS, exceptionId)!!)

    override fun slotOfCancellation(masterId: Long): Pair<Long, Boolean> = slotOf(
        provider.rows(
            SqliteCalendarProvider.EVENTS,
            "${Events.ORIGINAL_ID} = ? AND ${Events.STATUS} = ?",
            masterId.toString(), Events.STATUS_CANCELED.toString(),
        ).last()
    )

    override fun seedOneOff(): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL_ID,
        Events.TITLE to "Standup",
        Events.DTSTART to OCCURRENCE_TS,
        Events.DTEND to OCCURRENCE_TS + HOUR_MS,
        Events.EVENT_TIMEZONE to "UTC",
    )

    override fun seedReminder(eventId: Long, minutes: Int, method: Int) {
        provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to eventId,
            Reminders.MINUTES to minutes,
            Reminders.METHOD to method,
        )
    }

    override fun reminderRowsOf(eventId: Long): List<Pair<Int, Int>> = provider.reminderRows(eventId)

    override fun seedException(masterId: Long, originalInstanceTime: Long, status: Int, deleted: Boolean): Long =
        provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL_ID,
            Events.TITLE to "Standup",
            Events.DTSTART to originalInstanceTime,
            Events.DTEND to originalInstanceTime + HOUR_MS,
            Events.EVENT_TIMEZONE to "UTC",
            Events.ORIGINAL_ID to masterId,
            Events.ORIGINAL_SYNC_ID to "master-sync-id",
            Events.ORIGINAL_INSTANCE_TIME to originalInstanceTime,
            Events.STATUS to status,
            Events._SYNC_ID to "exception-sync-id-${System.nanoTime()}",
            Events.DELETED to if (deleted) 1 else 0,
        )
}
