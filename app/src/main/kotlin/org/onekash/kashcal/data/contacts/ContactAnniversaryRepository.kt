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

private const val TAG = "ContactAnniversaryRepo"

/**
 * Data class representing a contact with anniversary information.
 */
data class ContactAnniversary(
    val lookupKey: String,
    val displayName: String,
    val anniversary: ContactEventDate
)

/**
 * Repository for managing contact anniversary calendar.
 *
 * Handles:
 * - Creating/removing the anniversary calendar
 * - Syncing anniversaries from phone contacts
 * - Upsert/delete logic for anniversary events
 *
 * Pattern: Similar to ContactBirthdayRepository but for contact anniversaries.
 */
@Singleton
class ContactAnniversaryRepository @Inject constructor(
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
        const val ACCOUNT_EMAIL = "contact_anniversaries"
        const val CALENDAR_DISPLAY_NAME = "Contact Anniversaries"
        const val SOURCE_PREFIX = "contact_anniversary"

        // Caldav URL format: "contact_anniversary:{lookupKey}:{month}-{day}"
        fun getCaldavUrl(lookupKey: String, month: Int, day: Int): String =
            "$SOURCE_PREFIX:$lookupKey:$month-$day"
    }

    // ========== Calendar Management ==========

    /**
     * Check if the anniversary calendar exists.
     */
    suspend fun anniversaryCalendarExists(): Boolean = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
        account != null && calendarsDao.getByAccountIdOnce(account.id).isNotEmpty()
    }

    /**
     * Get the anniversary calendar ID, or null if not created.
     */
    suspend fun getAnniversaryCalendarId(): Long? = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
            ?: return@withContext null
        calendarsDao.getByAccountIdOnce(account.id).firstOrNull()?.id
    }

    /**
     * Ensure the anniversary calendar exists, creating it if needed.
     *
     * @param color Initial color for the calendar
     * @return Calendar ID
     */
    suspend fun ensureCalendarExists(color: Int = SubscriptionColors.Pink): Long = withContext(Dispatchers.IO) {
        // Check if account exists
        var account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
        if (account == null) {
            // Create contacts account
            account = Account(
                provider = AccountProvider.CONTACTS,
                email = ACCOUNT_EMAIL,
                displayName = "Contact Anniversaries"
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
            caldavUrl = "local://contact_anniversaries",
            displayName = CALENDAR_DISPLAY_NAME,
            color = color,
            isReadOnly = true,
            isVisible = true,
            isDefault = false
        )
        val calendarId = calendarsDao.insert(calendar)
        Log.i(TAG, "Created anniversary calendar: $calendarId")

        calendarId
    }

    /**
     * Update the anniversary calendar color.
     */
    suspend fun updateCalendarColor(color: Int) = withContext(Dispatchers.IO) {
        val calendarId = getAnniversaryCalendarId() ?: return@withContext
        calendarsDao.updateColor(calendarId, color)
    }

    /**
     * Get the current calendar color.
     */
    suspend fun getCalendarColor(): Int? = withContext(Dispatchers.IO) {
        val calendarId = getAnniversaryCalendarId() ?: return@withContext null
        calendarsDao.getById(calendarId)?.color
    }

    /**
     * Remove the anniversary calendar and all its events.
     *
     * Uses AccountRepository.deleteAccount() which handles:
     * - Cancelling WorkManager jobs
     * - Cancelling reminders for all events
     * - Deleting credentials (none for CONTACTS)
     * - Cascade delete account -> calendars -> events -> occurrences
     */
    suspend fun removeCalendar() = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, ACCOUNT_EMAIL)
            ?: return@withContext

        // Delete account with full cleanup
        accountRepository.deleteAccount(account.id)
        Log.i(TAG, "Removed anniversary calendar and account")
    }

    // ========== Sync Operations ==========

    /**
     * Sync anniversaries from phone contacts.
     *
     * @return ContactEventSyncResult indicating success or failure
     */
    suspend fun syncAnniversaries(): ContactEventSyncResult = withContext(Dispatchers.IO) {
        try {
            val calendarId = getAnniversaryCalendarId()
                ?: return@withContext ContactEventSyncResult.Error("Anniversary calendar not created")

            val calendar = calendarsDao.getById(calendarId)
                ?: return@withContext ContactEventSyncResult.Error("Calendar not found")

            // Read anniversary reminder setting
            val reminderMinutes = dataStore.getAnniversaryReminder()

            // Read anniversaries from contacts
            val contactAnniversaries = readAnniversariesFromContacts()
            Log.d(TAG, "Found ${contactAnniversaries.size} contacts with anniversaries")

            // Get existing anniversary events
            val existingEvents = eventsDao.getAllMasterEventsForCalendar(calendarId)
            val existingByCaldavUrl = existingEvents.associateBy { event ->
                event.caldavUrl
            }

            var added = 0
            var updated = 0
            var deleted = 0

            // Process each contact anniversary
            val processedCaldavUrls = mutableSetOf<String>()
            for (contact in contactAnniversaries) {
                val caldavUrl = getCaldavUrl(contact.lookupKey, contact.anniversary.month, contact.anniversary.day)
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
                            ContactEventUtils.decodeEventYear(existingEvent.description) != contact.anniversary.year ||
                            existingEvent.reminders != expectedReminders ||
                            startTsNeedsMigration

                    if (needsUpdate) {
                        val updatedEvent = createAnniversaryEvent(contact, calendarId, existingEvent.id, reminderMinutes)
                        eventsDao.update(updatedEvent)
                        occurrenceGenerator.regenerateOccurrences(updatedEvent)
                        scheduleRemindersForEvent(updatedEvent, calendar.color, isModified = true)
                        updated++
                        Log.d(TAG, "Updated anniversary: ${contact.displayName}")
                    }
                } else {
                    // Insert new
                    val newEvent = createAnniversaryEvent(contact, calendarId, reminderMinutes = reminderMinutes)
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
                    Log.d(TAG, "Added anniversary: ${contact.displayName}")
                }
            }

            // Delete orphaned events (contacts removed or anniversary removed)
            for ((caldavUrl, event) in existingByCaldavUrl) {
                if (caldavUrl != null && caldavUrl !in processedCaldavUrls) {
                    reminderScheduler.cancelRemindersForEvent(event.id)
                    eventsDao.deleteById(event.id)
                    deleted++
                    Log.d(TAG, "Deleted orphaned anniversary: ${event.title}")
                }
            }

            Log.i(TAG, "Sync complete: $added added, $updated updated, $deleted deleted")
            ContactEventSyncResult.Success(added, updated, deleted)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading contacts", e)
            ContactEventSyncResult.Error("Contacts permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing anniversaries", e)
            ContactEventSyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // ========== Private Helpers ==========

    /**
     * Read anniversaries from phone contacts.
     */
    private fun readAnniversariesFromContacts(): List<ContactAnniversary> {
        val anniversaries = mutableListOf<ContactAnniversary>()

        val projection = arrayOf(
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Event.START_DATE,
            ContactsContract.CommonDataKinds.Event.TYPE
        )

        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY.toString()
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
                val anniversaryIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)

                while (it.moveToNext()) {
                    val lookupKey = it.getString(lookupKeyIndex)
                    val displayName = it.getString(displayNameIndex)
                    val anniversaryString = it.getString(anniversaryIndex)

                    if (lookupKey != null && displayName != null) {
                        val anniversaryInfo = ContactEventUtils.parseContactDate(anniversaryString)
                        if (anniversaryInfo != null) {
                            anniversaries.add(ContactAnniversary(lookupKey, displayName, anniversaryInfo))
                        }
                    }
                }
            }
        } finally {
            cursor?.close()
        }

        return anniversaries
    }

    /**
     * Create an anniversary event for a contact.
     *
     * @param contact The contact with anniversary info
     * @param calendarId The calendar ID
     * @param existingId Existing event ID for updates (0 for new events)
     * @param reminderMinutes Reminder minutes from user preferences (REMINDER_OFF for no reminder)
     */
    private fun createAnniversaryEvent(
        contact: ContactAnniversary,
        calendarId: Long,
        existingId: Long = 0,
        reminderMinutes: Int = KashCalDataStore.REMINDER_OFF
    ): Event {
        val anniversary = contact.anniversary
        val startTs = ContactEventUtils.getNextEventTimestamp(anniversary.month, anniversary.day)
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
            uid = if (existingId == 0L) "${UUID.randomUUID()}@kashcal.anniversary" else "",
            calendarId = calendarId,
            title = contact.displayName,
            description = ContactEventUtils.encodeEventYear(anniversary.year),
            startTs = startTs,
            endTs = endTs,
            timezone = "UTC",
            isAllDay = true,
            rrule = ContactEventUtils.generateYearlyRRule(),
            caldavUrl = getCaldavUrl(contact.lookupKey, anniversary.month, anniversary.day),
            syncStatus = SyncStatus.SYNCED, // Read-only, no push needed
            dtstamp = System.currentTimeMillis(),
            reminders = reminders
        )
    }

    /**
     * Schedule reminders for an anniversary event.
     * Helper method similar to ContactBirthdayRepository.
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
