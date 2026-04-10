package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.util.DateTimeUtils
import java.util.UUID

/**
 * Internal data class representing a contact with an event date (birthday or anniversary).
 */
data class ContactEventEntry(
    val lookupKey: String,
    val displayName: String,
    val date: ContactEventDate
)

/**
 * Base repository for contact event calendars (birthdays, anniversaries).
 *
 * Contains all shared logic for:
 * - Creating/removing the contact event calendar
 * - Syncing events from phone contacts
 * - Upsert/delete logic for contact events
 *
 * Subclasses exist only for Hilt DI (to distinguish birthday vs anniversary instances)
 * and to provide the DataStore reminder accessor via [getReminderMinutes].
 */
abstract class BaseContactEventRepository(
    private val accountRepository: AccountRepository,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val reminderScheduler: ReminderScheduler,
    private val eventReader: EventReader,
    private val contentResolver: ContentResolver,
    protected val dataStore: KashCalDataStore,
    protected val eventType: ContactEventType
) {
    private val tag: String get() = eventType.logTag

    /**
     * Returns the reminder duration in minutes from DataStore.
     * Different per type (birthday vs anniversary use different DataStore keys).
     */
    protected abstract suspend fun getReminderMinutes(): Int

    // ========== Calendar Management ==========

    /**
     * Check if the contact event calendar exists.
     */
    suspend fun calendarExists(): Boolean = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
        account != null && calendarsDao.getByAccountIdOnce(account.id).isNotEmpty()
    }

    /**
     * Get the calendar ID, or null if not created.
     */
    suspend fun getCalendarId(): Long? = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
            ?: return@withContext null
        calendarsDao.getByAccountIdOnce(account.id).firstOrNull()?.id
    }

    /**
     * Ensure the calendar exists, creating it if needed.
     *
     * @param color Initial color for the calendar
     * @return Calendar ID
     */
    suspend fun ensureCalendarExists(color: Int = eventType.defaultColor): Long = withContext(Dispatchers.IO) {
        // Check if account exists
        var account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
        if (account == null) {
            // Create contacts account
            account = Account(
                provider = AccountProvider.CONTACTS,
                email = eventType.accountEmail,
                displayName = eventType.calendarDisplayName
            )
            val accountId = accountRepository.createAccount(account)
            account = account.copy(id = accountId)
            Log.i(tag, "Created contacts account: $accountId")
        }

        // Check if calendar exists
        val existingCalendars = calendarsDao.getByAccountIdOnce(account.id)
        if (existingCalendars.isNotEmpty()) {
            return@withContext existingCalendars.first().id
        }

        // Create calendar
        val calendar = Calendar(
            accountId = account.id,
            caldavUrl = eventType.localCalendarUrl,
            displayName = eventType.calendarDisplayName,
            color = color,
            isReadOnly = true,
            isVisible = true,
            isDefault = false
        )
        val calendarId = calendarsDao.insert(calendar)
        Log.i(tag, "Created ${eventType.name.lowercase()} calendar: $calendarId")

        calendarId
    }

    /**
     * Update the calendar color.
     */
    suspend fun updateCalendarColor(color: Int) = withContext(Dispatchers.IO) {
        val calendarId = getCalendarId() ?: return@withContext
        calendarsDao.updateColor(calendarId, color)
    }

    /**
     * Get the current calendar color.
     */
    suspend fun getCalendarColor(): Int? = withContext(Dispatchers.IO) {
        val calendarId = getCalendarId() ?: return@withContext null
        calendarsDao.getById(calendarId)?.color
    }

    /**
     * Remove the calendar and all its events.
     *
     * Uses AccountRepository.deleteAccount() which handles:
     * - Cancelling WorkManager jobs
     * - Cancelling reminders for all events
     * - Deleting credentials (none for CONTACTS)
     * - Cascade delete account -> calendars -> events -> occurrences
     */
    suspend fun removeCalendar() = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
            ?: return@withContext

        // Delete account with full cleanup
        accountRepository.deleteAccount(account.id)
        Log.i(tag, "Removed ${eventType.name.lowercase()} calendar and account")
    }

    // ========== Sync Operations ==========

    /**
     * Sync events from phone contacts.
     *
     * @return ContactEventSyncResult indicating success or failure
     */
    suspend fun syncEvents(): ContactEventSyncResult = withContext(Dispatchers.IO) {
        try {
            val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
                ?: return@withContext ContactEventSyncResult.Error("${eventType.calendarDisplayName} calendar not created")

            val calendar = calendarsDao.getByAccountIdOnce(account.id).firstOrNull()
                ?: return@withContext ContactEventSyncResult.Error("${eventType.calendarDisplayName} calendar not created")

            val calendarId = calendar.id

            // Read reminder setting
            val reminderMinutes = getReminderMinutes()

            // Read events from contacts
            val contactEvents = readEventsFromContacts()
            Log.d(tag, "Found ${contactEvents.size} contacts with ${eventType.name.lowercase()}s")

            // Get existing events
            val existingEvents = eventsDao.getAllMasterEventsForCalendar(calendarId)
            val existingByCaldavUrl = existingEvents
                .filter { it.caldavUrl != null }
                .associateBy { it.caldavUrl!! }

            var added = 0
            var updated = 0
            var deleted = 0

            // Pre-compute loop-invariant values
            val expectedReminders = if (reminderMinutes > 0) {
                listOf(ContactEventUtils.minutesToIsoDuration(reminderMinutes))
            } else {
                null
            }
            val now = System.currentTimeMillis()
            val oneYearAgo = now - (365L * 24 * 60 * 60 * 1000)
            val twoYearsAhead = now + (2L * 365 * 24 * 60 * 60 * 1000)

            // Process each contact event
            val processedCaldavUrls = mutableSetOf<String>()
            for (contact in contactEvents) {
                val caldavUrl = eventType.getCaldavUrl(contact.lookupKey, contact.date.month, contact.date.day)
                processedCaldavUrls.add(caldavUrl)

                val existingEvent = existingByCaldavUrl[caldavUrl]
                if (existingEvent != null) {
                    // Migration: Fix events with wrong DTSTART (future-year bug or getNextEventTimestamp gap)
                    val expectedStartTs = ContactEventUtils.getStartTimestamp(contact.date.month, contact.date.day, contact.date.year)
                    val startTsNeedsMigration = existingEvent.startTs != expectedStartTs

                    val needsUpdate = existingEvent.title != contact.displayName ||
                            ContactEventUtils.decodeEventYear(existingEvent.description) != contact.date.year ||
                            existingEvent.reminders != expectedReminders ||
                            startTsNeedsMigration

                    if (needsUpdate) {
                        val updatedEvent = createEvent(contact, calendarId, existingEvent.id, reminderMinutes)
                        eventsDao.update(updatedEvent)
                        occurrenceGenerator.regenerateOccurrences(updatedEvent)
                        scheduleRemindersForEvent(updatedEvent, calendar.color, isModified = true)
                        updated++
                        Log.d(tag, "Updated ${eventType.name.lowercase()}: ${contact.displayName}")
                    }
                } else {
                    // Insert new
                    val newEvent = createEvent(contact, calendarId, reminderMinutes = reminderMinutes)
                    val eventId = eventsDao.insert(newEvent)
                    val insertedEvent = newEvent.copy(id = eventId)

                    occurrenceGenerator.generateOccurrences(insertedEvent, oneYearAgo, twoYearsAhead)
                    scheduleRemindersForEvent(insertedEvent, calendar.color, isModified = false)
                    added++
                    Log.d(tag, "Added ${eventType.name.lowercase()}: ${contact.displayName}")
                }
            }

            // Delete orphaned events (contacts removed or event removed)
            for ((caldavUrl, event) in existingByCaldavUrl) {
                if (caldavUrl !in processedCaldavUrls) {
                    reminderScheduler.cancelRemindersForEvent(event.id)
                    eventsDao.deleteById(event.id)
                    deleted++
                    Log.d(tag, "Deleted orphaned ${eventType.name.lowercase()}: ${event.title}")
                }
            }

            Log.i(tag, "Sync complete: $added added, $updated updated, $deleted deleted")
            ContactEventSyncResult.Success(added, updated, deleted)

        } catch (e: SecurityException) {
            Log.e(tag, "Permission denied reading contacts", e)
            ContactEventSyncResult.Error("Contacts permission denied")
        } catch (e: Exception) {
            Log.e(tag, "Error syncing ${eventType.name.lowercase()}s", e)
            ContactEventSyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // ========== Private Helpers ==========

    /**
     * Read events from phone contacts for this event type.
     */
    private fun readEventsFromContacts(): List<ContactEventEntry> {
        val entries = mutableListOf<ContactEventEntry>()

        val projection = arrayOf(
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Event.START_DATE,
            ContactsContract.CommonDataKinds.Event.TYPE
        )

        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            eventType.contactEventTypeId.toString()
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
                val dateIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)

                while (it.moveToNext()) {
                    val lookupKey = it.getString(lookupKeyIndex)
                    val displayName = it.getString(displayNameIndex)
                    val dateString = it.getString(dateIndex)

                    if (lookupKey != null && displayName != null) {
                        val dateInfo = ContactEventUtils.parseContactDate(dateString)
                        if (dateInfo != null) {
                            entries.add(ContactEventEntry(lookupKey, displayName, dateInfo))
                        }
                    }
                }
            }
        } finally {
            cursor?.close()
        }

        return entries
    }

    /**
     * Create an event for a contact.
     *
     * @param contact The contact with event date info
     * @param calendarId The calendar ID
     * @param existingId Existing event ID for updates (0 for new events)
     * @param reminderMinutes Reminder minutes from user preferences (REMINDER_OFF for no reminder)
     */
    private fun createEvent(
        contact: ContactEventEntry,
        calendarId: Long,
        existingId: Long = 0,
        reminderMinutes: Int = KashCalDataStore.REMINDER_OFF
    ): Event {
        val date = contact.date
        val startTs = ContactEventUtils.getStartTimestamp(date.month, date.day, date.year)
        val endTs = DateTimeUtils.utcMidnightToEndOfDay(startTs)

        // Convert reminder minutes to ISO 8601 duration format
        val reminders = if (reminderMinutes > 0) {
            listOf(ContactEventUtils.minutesToIsoDuration(reminderMinutes))
        } else {
            null
        }

        return Event(
            id = existingId,
            uid = if (existingId == 0L) "${UUID.randomUUID()}${eventType.uidSuffix}" else "",
            calendarId = calendarId,
            title = contact.displayName,
            description = ContactEventUtils.encodeEventYear(date.year),
            startTs = startTs,
            endTs = endTs,
            timezone = "UTC",
            isAllDay = true,
            rrule = ContactEventUtils.generateYearlyRRule(),
            caldavUrl = eventType.getCaldavUrl(contact.lookupKey, date.month, date.day),
            syncStatus = SyncStatus.SYNCED, // Read-only, no push needed
            dtstamp = System.currentTimeMillis(),
            reminders = reminders
        )
    }

    /**
     * Schedule reminders for a contact event.
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
            Log.e(tag, "Failed to schedule reminders for event ${event.id}: ${e.message}")
        }
    }
}
