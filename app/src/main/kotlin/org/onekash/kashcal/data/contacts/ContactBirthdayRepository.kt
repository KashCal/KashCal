package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.ui.screens.settings.SubscriptionColors
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactBirthdayRepo"

/**
 * Data class representing a contact with birthday information.
 */
data class ContactBirthday(
    val lookupKey: String,
    val displayName: String,
    val birthday: ContactEventDate
)

/**
 * Repository for managing contact birthday calendar.
 *
 * Handles:
 * - Creating/removing the birthday calendar
 * - Syncing birthdays from phone contacts
 * - Upsert/delete logic for birthday events
 *
 * Pattern: Similar to IcsSubscriptionRepository but for contact birthdays.
 */
@Singleton
class ContactBirthdayRepository @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val reminderScheduler: ReminderScheduler,
    private val eventReader: EventReader,
    private val contentResolver: ContentResolver,
    private val dataStore: KashCalDataStore
) {

    companion object {
        const val ACCOUNT_EMAIL = "contact_birthdays"
        const val CALENDAR_DISPLAY_NAME = "Contact Birthdays"
        const val SOURCE_PREFIX = "contact_birthday"

        // Caldav URL format: "contact_birthday:{lookupKey}:{month}-{day}"
        fun getCaldavUrl(lookupKey: String, month: Int, day: Int): String =
            "$SOURCE_PREFIX:$lookupKey:$month-$day"
    }

    // ========== Calendar Management ==========

    /**
     * Check if the birthday calendar exists.
     */
    suspend fun birthdayCalendarExists(): Boolean = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
        account != null && calendarsDao.getByAccountIdOnce(account.id).isNotEmpty()
    }

    /**
     * Get the birthday calendar ID, or null if not created.
     */
    suspend fun getBirthdayCalendarId(): Long? = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
            ?: return@withContext null
        calendarsDao.getByAccountIdOnce(account.id).firstOrNull()?.id
    }

    /**
     * Ensure the birthday calendar exists, creating it if needed.
     *
     * @param color Initial color for the calendar
     * @return Calendar ID
     */
    suspend fun ensureCalendarExists(color: Int = SubscriptionColors.Purple): Long = withContext(Dispatchers.IO) {
        // Check if account exists
        var account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
        if (account == null) {
            // Create contacts account
            account = Account(
                provider = AccountProvider.CONTACTS,
                email = ACCOUNT_EMAIL,
                displayName = "Contact Birthdays"
            )
            val accountId = accountRepository.createAccount(account)
            account = account.copy(id = accountId)
            Log.i(TAG, "Created contacts account: $accountId")
        }

        // Check if calendar exists
        val existingCalendars = calendarsDao.getByAccountIdOnce(account.id)
        if (existingCalendars.isNotEmpty()) {
            return@withContext existingCalendars.first().id
        }

        // Create calendar
        val calendar = Calendar(
            accountId = account.id,
            caldavUrl = "local://contact_birthdays",
            displayName = CALENDAR_DISPLAY_NAME,
            color = color,
            isReadOnly = true,
            isVisible = true,
            isDefault = false
        )
        val calendarId = calendarsDao.insert(calendar)
        Log.i(TAG, "Created birthday calendar: $calendarId")

        calendarId
    }

    /**
     * Update the birthday calendar color.
     */
    suspend fun updateCalendarColor(color: Int) = withContext(Dispatchers.IO) {
        val calendarId = getBirthdayCalendarId() ?: return@withContext
        calendarsDao.updateColor(calendarId, color)
    }

    /**
     * Get the current calendar color.
     */
    suspend fun getCalendarColor(): Int? = withContext(Dispatchers.IO) {
        val calendarId = getBirthdayCalendarId() ?: return@withContext null
        calendarsDao.getById(calendarId)?.color
    }

    /**
     * Remove the birthday calendar and all its events.
     *
     * Uses AccountRepository.deleteAccount() which handles:
     * - Cancelling WorkManager jobs
     * - Cancelling reminders for all events
     * - Deleting credentials (none for CONTACTS)
     * - Cascade delete account → calendars → events → occurrences
     */
    suspend fun removeCalendar() = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
            ?: return@withContext

        // Delete account with full cleanup
        accountRepository.deleteAccount(account.id)
        Log.i(TAG, "Removed birthday calendar and account")
    }

    // ========== Sync Operations ==========

    /**
     * Sync birthdays from phone contacts.
     *
     * @return ContactEventSyncResult indicating success or failure
     */
    suspend fun syncBirthdays(): ContactEventSyncResult = withContext(Dispatchers.IO) {
        try {
            val calendarId = getBirthdayCalendarId()
                ?: return@withContext ContactEventSyncResult.Error("Birthday calendar not created")

            val calendar = calendarsDao.getById(calendarId)
                ?: return@withContext ContactEventSyncResult.Error("Calendar not found")

            // Read birthday reminder setting
            val reminderMinutes = dataStore.getBirthdayReminder()

            // Read birthdays from contacts
            val contactBirthdays = readBirthdaysFromContacts()
            Log.d(TAG, "Found ${contactBirthdays.size} contacts with birthdays")

            // Get existing birthday events
            val existingEvents = eventsDao.getAllMasterEventsForCalendar(calendarId)
            val existingByCaldavUrl = existingEvents.associateBy { event ->
                event.caldavUrl
            }

            var added = 0
            var updated = 0
            var deleted = 0

            // Process each contact birthday
            val processedCaldavUrls = mutableSetOf<String>()
            for (contact in contactBirthdays) {
                val caldavUrl = getCaldavUrl(contact.lookupKey, contact.birthday.month, contact.birthday.day)
                processedCaldavUrls.add(caldavUrl)

                val existingEvent = existingByCaldavUrl[caldavUrl]
                if (existingEvent != null) {
                    // Update if changed (including reminder changes)
                    val expectedReminders = if (reminderMinutes > 0) {
                        listOf(ContactEventUtils.minutesToIsoDuration(reminderMinutes))
                    } else {
                        null
                    }

                    // Migration: Fix events with startTs in future year (v20.8.0 timezone bug)
                    // These events were created with wrong year due to UTC comparison
                    val existingCal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = existingEvent.startTs
                    }
                    val existingYear = existingCal.get(java.util.Calendar.YEAR)
                    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                    val startTsNeedsMigration = existingYear > currentYear

                    val needsUpdate = existingEvent.title != contact.displayName ||
                            ContactEventUtils.decodeEventYear(existingEvent.description) != contact.birthday.year ||
                            existingEvent.reminders != expectedReminders ||
                            startTsNeedsMigration

                    if (needsUpdate) {
                        val updatedEvent = createBirthdayEvent(contact, calendarId, existingEvent.id, reminderMinutes)
                        eventsDao.update(updatedEvent)
                        occurrenceGenerator.regenerateOccurrences(updatedEvent)
                        scheduleRemindersForEvent(updatedEvent, calendar.color, isModified = true)
                        updated++
                        Log.d(TAG, "Updated birthday: ${contact.displayName}")
                    }
                } else {
                    // Insert new
                    val newEvent = createBirthdayEvent(contact, calendarId, reminderMinutes = reminderMinutes)
                    val eventId = eventsDao.insert(newEvent)
                    val insertedEvent = newEvent.copy(id = eventId)

                    // Generate occurrences
                    val now = System.currentTimeMillis()
                    val oneYearAgo = now - (365L * 24 * 60 * 60 * 1000)
                    val twoYearsAhead = now + (2L * 365 * 24 * 60 * 60 * 1000)
                    occurrenceGenerator.generateOccurrences(insertedEvent, oneYearAgo, twoYearsAhead)

                    // Schedule reminders
                    scheduleRemindersForEvent(insertedEvent, calendar.color, isModified = false)
                    added++
                    Log.d(TAG, "Added birthday: ${contact.displayName}")
                }
            }

            // Delete orphaned events (contacts removed or birthday removed)
            for ((caldavUrl, event) in existingByCaldavUrl) {
                if (caldavUrl != null && caldavUrl !in processedCaldavUrls) {
                    reminderScheduler.cancelRemindersForEvent(event.id)
                    eventsDao.deleteById(event.id)
                    deleted++
                    Log.d(TAG, "Deleted orphaned birthday: ${event.title}")
                }
            }

            Log.i(TAG, "Sync complete: $added added, $updated updated, $deleted deleted")
            ContactEventSyncResult.Success(added, updated, deleted)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading contacts", e)
            ContactEventSyncResult.Error("Contacts permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing birthdays", e)
            ContactEventSyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // ========== Private Helpers ==========

    /**
     * Read birthdays from phone contacts.
     */
    private fun readBirthdaysFromContacts(): List<ContactBirthday> {
        val birthdays = mutableListOf<ContactBirthday>()

        val projection = arrayOf(
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Event.START_DATE,
            ContactsContract.CommonDataKinds.Event.TYPE
        )

        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
        )

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                ContactsContract.Data.DISPLAY_NAME
            )

            cursor?.let {
                val lookupKeyIndex = it.getColumnIndex(ContactsContract.Data.LOOKUP_KEY)
                val displayNameIndex = it.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                val birthdayIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)

                while (it.moveToNext()) {
                    val lookupKey = it.getString(lookupKeyIndex)
                    val displayName = it.getString(displayNameIndex)
                    val birthdayString = it.getString(birthdayIndex)

                    if (lookupKey != null && displayName != null) {
                        val birthdayInfo = ContactEventUtils.parseContactDate(birthdayString)
                        if (birthdayInfo != null) {
                            birthdays.add(ContactBirthday(lookupKey, displayName, birthdayInfo))
                        }
                    }
                }
            }
        } finally {
            cursor?.close()
        }

        return birthdays
    }

    /**
     * Create a birthday event for a contact.
     *
     * @param contact The contact with birthday info
     * @param calendarId The calendar ID
     * @param existingId Existing event ID for updates (0 for new events)
     * @param reminderMinutes Reminder minutes from user preferences (REMINDER_OFF for no reminder)
     */
    private fun createBirthdayEvent(
        contact: ContactBirthday,
        calendarId: Long,
        existingId: Long = 0,
        reminderMinutes: Int = KashCalDataStore.REMINDER_OFF
    ): Event {
        val birthday = contact.birthday
        val startTs = ContactEventUtils.getNextEventTimestamp(birthday.month, birthday.day)
        // All-day event: end is same as start (Room handles all-day correctly)
        val endTs = startTs + (24 * 60 * 60 * 1000) - 1 // End of day

        // Convert reminder minutes to ISO 8601 duration format
        val reminders = if (reminderMinutes > 0) {
            listOf(ContactEventUtils.minutesToIsoDuration(reminderMinutes))
        } else {
            null
        }

        return Event(
            id = existingId,
            uid = if (existingId == 0L) "${UUID.randomUUID()}@kashcal.birthday" else "",
            calendarId = calendarId,
            title = contact.displayName,
            description = ContactEventUtils.encodeEventYear(birthday.year),
            startTs = startTs,
            endTs = endTs,
            timezone = "UTC",
            isAllDay = true,
            rrule = ContactEventUtils.generateYearlyRRule(),
            caldavUrl = getCaldavUrl(contact.lookupKey, birthday.month, birthday.day),
            syncStatus = SyncStatus.SYNCED, // Read-only, no push needed
            dtstamp = System.currentTimeMillis(),
            reminders = reminders
        )
    }

    /**
     * Schedule reminders for a birthday event.
     * Helper method similar to IcsSubscriptionRepository.
     *
     * @param event The event to schedule reminders for
     * @param calendarColor Calendar color for notification
     * @param isModified If true, cancels existing reminders first (handles time changes)
     */
    private suspend fun scheduleRemindersForEvent(
        event: Event,
        calendarColor: Int,
        isModified: Boolean
    ) {
        // Skip events without reminders
        if (event.reminders.isNullOrEmpty()) return

        try {
            // For modified events, cancel existing reminders first (handles time changes)
            if (isModified) {
                reminderScheduler.cancelRemindersForEvent(event.id)
            }

            // Get occurrences in schedule window
            val occurrences = eventReader.getOccurrencesForEventInScheduleWindow(event.id)

            if (occurrences.isEmpty()) return

            reminderScheduler.scheduleRemindersForEvent(
                event = event,
                occurrences = occurrences,
                calendarColor = calendarColor
            )
        } catch (e: Exception) {
            // Log but don't fail sync for reminder scheduling errors
            Log.e(TAG, "Failed to schedule reminders for event ${event.id}: ${e.message}")
        }
    }
}
