package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.HOUR
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what renaming a contact does to today's birthday or anniversary reminder (#425), through
 * the real [ContactBirthdayRepository] / [ContactAnniversaryRepository] sync, occurrence generator
 * and reminder scheduler over Room. The Contacts Provider is a cursor of one contact whose date is
 * today; the rename is the only change, so the reminder (the default, 9 AM the day of) must come
 * through the sync as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ContactEventRenameReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var dataStore: KashCalDataStore
    private val contentResolver = mockk<ContentResolver>()
    private val accountRepository = mockk<AccountRepository>()
    private lateinit var savedZone: TimeZone
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now(ZoneOffset.UTC)
    private val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private var contactName = "Ann"
    private var calendarId = 0L

    @Before
    fun setUp() {
        // All-day occurrences start at UTC midnight; at UTC-12 today's 9 AM is still ahead until
        // 21:00 UTC.
        val context: Context = ApplicationProvider.getApplicationContext()
        // Before the zone change: Robolectric's AlarmManager shadow puts back, after every test,
        // the default zone it first saw.
        fixture = ReminderAlarmFixture(context)
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        dataStore = TestDataStoreFactory.createDefault()
        // The real defaults: 9 AM the day of.
        coEvery { dataStore.getBirthdayReminder() } returns KashCalDataStore.DEFAULT_BIRTHDAY_REMINDER_MINUTES
        coEvery { dataStore.getAnniversaryReminder() } returns KashCalDataStore.DEFAULT_ANNIVERSARY_REMINDER_MINUTES
        every { contentResolver.query(ContactsContract.Data.CONTENT_URI, any(), any(), any(), any()) } answers {
            MatrixCursor(
                arrayOf(
                    ContactsContract.Data.LOOKUP_KEY,
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Event.START_DATE,
                    ContactsContract.CommonDataKinds.Event.TYPE
                )
            ).apply { addRow(arrayOf("ann-key", contactName, "1990-%02d-%02d".format(today.monthValue, today.dayOfMonth), 0)) }
        }
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(savedZone)
    }

    private suspend fun repositoryFor(type: ContactEventType): BaseContactEventRepository {
        val db = fixture.database
        val accountId = db.accountsDao().insert(Account(provider = AccountProvider.CONTACTS, email = type.accountEmail))
        calendarId = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://${type.accountEmail}", displayName = type.name, color = -1)
        )
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, type.accountEmail)
        } coAnswers { db.accountsDao().getById(accountId) }
        val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), dataStore)
        val context: Context = ApplicationProvider.getApplicationContext()
        return when (type) {
            ContactEventType.BIRTHDAY -> ContactBirthdayRepository(
                accountRepository, db.calendarsDao(), db.eventsDao(), generator, fixture.scheduler,
                fixture.eventReader, contentResolver, dataStore, context
            )
            ContactEventType.ANNIVERSARY -> ContactAnniversaryRepository(
                accountRepository, db.calendarsDao(), db.eventsDao(), generator, fixture.scheduler,
                fixture.eventReader, contentResolver, dataStore, context
            )
        }
    }

    /** Syncs the contact in, then returns its event; the default reminder is 9 AM the day of. */
    private suspend fun syncIn(repository: BaseContactEventRepository): Event {
        assertTrue(repository.syncEvents() is ContactEventSyncResult.Success)
        val event = fixture.database.eventsDao().getAllMasterEventsForCalendar(calendarId).single()
        assertEquals(listOf("PT9H"), event.reminders)
        assertTrue(
            "today's occurrence generated",
            fixture.database.occurrencesDao().getForEvent(event.id).any { it.startTs == todayUtc }
        )
        return event
    }

    private suspend fun renameAndSync(repository: BaseContactEventRepository) {
        contactName = "Ann Lee"
        val result = repository.syncEvents()
        assertEquals(ContactEventSyncResult.Success(0, 1, 0), result)
    }

    /** Today's 9 AM row of [event], which the sync made. */
    private suspend fun todaysRow(event: Event): Long =
        checkNotNull(fixture.database.scheduledRemindersDao().findExisting(event.id, todayUtc, "PT9H")).id

    private fun nineAmToday(): Long {
        val trigger = DateTimeUtils.allDayReminderTriggerTime(todayUtc, 9 * HOUR)
        assumeTrue("9 AM still ahead at UTC-12", trigger > now + MINUTE)
        return trigger
    }

    private fun todaysReminderIsArmedWhenAdded(type: ContactEventType) = runBlocking {
        val trigger = nineAmToday()

        syncIn(repositoryFor(type))

        assertEquals(listOf(trigger), fixture.armedReminders().values.toList())
    }

    private fun todaysReminderSurvivesRename(type: ContactEventType) = runBlocking {
        val trigger = nineAmToday()
        val repository = repositoryFor(type)
        val rowId = todaysRow(syncIn(repository))

        renameAndSync(repository)

        assertEquals(mapOf(rowId to trigger), fixture.armedReminders())
    }

    private fun snoozeSurvivesRename(type: ContactEventType) = runBlocking {
        nineAmToday()
        val repository = repositoryFor(type)
        // The sync's row fired an hour ago and its notification is still showing.
        val rowId = todaysRow(syncIn(repository))
        fixture.scheduler.cancelAlarm(rowId)
        fixture.database.scheduledRemindersDao().updateTriggerTime(rowId, now - HOUR)
        fixture.scheduler.markAsFired(rowId)

        renameAndSync(repository)
        fixture.scheduler.snoozeReminder(rowId, snoozeDurationMinutes = 15)

        assertNotNull("row behind the notification", fixture.scheduler.getReminder(rowId))
        assertEquals(1, fixture.reminderRowCount())
    }

    @Test
    fun `a birthday added today gets its 9 AM reminder`() =
        todaysReminderIsArmedWhenAdded(ContactEventType.BIRTHDAY)

    @Test
    fun `an anniversary added today gets its 9 AM reminder`() =
        todaysReminderIsArmedWhenAdded(ContactEventType.ANNIVERSARY)

    @Test
    fun `today's birthday reminder still due survives a contact rename`() =
        todaysReminderSurvivesRename(ContactEventType.BIRTHDAY)

    @Test
    fun `today's anniversary reminder still due survives a contact rename`() =
        todaysReminderSurvivesRename(ContactEventType.ANNIVERSARY)

    @Test
    fun `Snooze on a showing birthday notification works after a contact rename`() =
        snoozeSurvivesRename(ContactEventType.BIRTHDAY)

    @Test
    fun `Snooze on a showing anniversary notification works after a contact rename`() =
        snoozeSurvivesRename(ContactEventType.ANNIVERSARY)
}
