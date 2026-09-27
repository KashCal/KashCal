package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import android.content.Context
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

/** A contact's birthday or anniversary as read from the Contacts Provider. */
data class ContactEventEntry(
    val lookupKey: String,
    val displayName: String,
    val date: ContactEventDate
)

/**
 * Manages a read-only contact event calendar (birthdays or anniversaries): creates and removes
 * it, and syncs its events from the phone's contacts.
 *
 * Subclasses exist only to give Hilt a birthday and an anniversary instance and to supply
 * [getReminderMinutes]; per-type differences live on [ContactEventType].
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
    protected val eventType: ContactEventType,
    protected val context: Context
) {
    private val tag: String get() = eventType.logTag

    /** Returns this type's reminder setting in minutes; each type has its own DataStore key. */
    protected abstract suspend fun getReminderMinutes(): Int

    // ========== Calendar Management ==========

    /** Returns true if this type's contacts account has a calendar. */
    suspend fun calendarExists(): Boolean = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
        account != null && calendarsDao.getByAccountIdOnce(account.id).isNotEmpty()
    }

    /** Returns the calendar ID, or null if the calendar isn't created. */
    suspend fun getCalendarId(): Long? = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
            ?: return@withContext null
        calendarsDao.getByAccountIdOnce(account.id).firstOrNull()?.id
    }

    /**
     * Returns the calendar ID, creating the contacts account and the read-only calendar if
     * missing. [color] applies only to a newly created calendar.
     */
    suspend fun ensureCalendarExists(color: Int = eventType.defaultColor): Long = withContext(Dispatchers.IO) {
        var account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
        if (account == null) {
            account = Account(
                provider = AccountProvider.CONTACTS,
                email = eventType.accountEmail,
                displayName = eventType.calendarDisplayName(context.resources)
            )
            val accountId = accountRepository.createAccount(account)
            account = account.copy(id = accountId)
            Log.i(tag, "Created contacts account: $accountId")
        }

        val existingCalendars = calendarsDao.getByAccountIdOnce(account.id)
        if (existingCalendars.isNotEmpty()) {
            return@withContext existingCalendars.first().id
        }

        val calendar = Calendar(
            accountId = account.id,
            caldavUrl = eventType.localCalendarUrl,
            displayName = eventType.calendarDisplayName(context.resources),
            color = color,
            isReadOnly = true,
            isVisible = true,
            isDefault = false
        )
        val calendarId = calendarsDao.insert(calendar)
        Log.i(tag, "Created ${eventType.name.lowercase()} calendar: $calendarId")

        calendarId
    }

    /** Sets the calendar color; a no-op when the calendar doesn't exist. */
    suspend fun updateCalendarColor(color: Int) = withContext(Dispatchers.IO) {
        val calendarId = getCalendarId() ?: return@withContext
        calendarsDao.updateColor(calendarId, color)
    }

    /** Returns the calendar color, or null if the calendar doesn't exist. */
    suspend fun getCalendarColor(): Int? = withContext(Dispatchers.IO) {
        val calendarId = getCalendarId() ?: return@withContext null
        calendarsDao.getById(calendarId)?.color
    }

    /**
     * Removes the calendar, its events and its account; [AccountRepository.deleteAccount] lists
     * the cleanup.
     */
    suspend fun removeCalendar() = withContext(Dispatchers.IO) {
        val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
            ?: return@withContext

        accountRepository.deleteAccount(account.id)
        Log.i(tag, "Removed ${eventType.name.lowercase()} calendar and account")
    }

    // ========== Sync Operations ==========

    /**
     * Syncs the calendar's events from the phone's contacts: inserts new ones, updates changed
     * ones and deletes events whose contact is gone or whose date changed or is gone.
     *
     * Returns [ContactEventSyncResult.Error] when the calendar isn't created, the contacts
     * permission is denied, or anything else throws; it doesn't throw.
     */
    suspend fun syncEvents(): ContactEventSyncResult = withContext(Dispatchers.IO) {
        try {
            val account = accountRepository.getAccountByProviderAndEmail(AccountProvider.CONTACTS, eventType.accountEmail)
                ?: return@withContext ContactEventSyncResult.Error("${eventType.calendarDisplayName(context.resources)} calendar not created")

            val calendar = calendarsDao.getByAccountIdOnce(account.id).firstOrNull()
                ?: return@withContext ContactEventSyncResult.Error("${eventType.calendarDisplayName(context.resources)} calendar not created")

            val calendarId = calendar.id

            val reminderMinutes = getReminderMinutes()

            val contactEvents = readEventsFromContacts()
            Log.d(tag, "Found ${contactEvents.size} contacts with ${eventType.name.lowercase()}s")

            val existingEvents = eventsDao.getAllMasterEventsForCalendar(calendarId)
            val existingByCaldavUrl = existingEvents
                .filter { it.caldavUrl != null }
                .associateBy { it.caldavUrl!! }

            var added = 0
            var updated = 0
            var deleted = 0

            val expectedReminders = if (reminderMinutes != KashCalDataStore.REMINDER_OFF) {
                listOf(ContactEventUtils.minutesToIsoDuration(reminderMinutes))
            } else {
                null
            }
            val now = System.currentTimeMillis()
            val oneYearAgo = now - (365L * 24 * 60 * 60 * 1000)
            val twoYearsAhead = now + (2L * 365 * 24 * 60 * 60 * 1000)

            val processedCaldavUrls = mutableSetOf<String>()
            for (contact in contactEvents) {
                val caldavUrl = eventType.getCaldavUrl(contact.lookupKey, contact.date.month, contact.date.day)
                processedCaldavUrls.add(caldavUrl)

                val existingEvent = existingByCaldavUrl[caldavUrl]
                if (existingEvent != null) {
                    // A DTSTART that differs from [ContactEventUtils.getStartTimestamp] is
                    // rewritten, which repairs events stored with a later start year.
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
                    val newEvent = createEvent(contact, calendarId, reminderMinutes = reminderMinutes)
                    val eventId = eventsDao.insert(newEvent)
                    val insertedEvent = newEvent.copy(id = eventId)

                    occurrenceGenerator.generateOccurrences(insertedEvent, oneYearAgo, twoYearsAhead)
                    scheduleRemindersForEvent(insertedEvent, calendar.color, isModified = false)
                    added++
                    Log.d(tag, "Added ${eventType.name.lowercase()}: ${contact.displayName}")
                }
            }

            // An event whose contact is gone, or whose date on that contact changed or is gone.
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
     * Reads this type's Event rows from the Contacts Provider, sorted by display name. Rows
     * without a lookup key or name, or with a date [ContactEventUtils.parseContactDate] rejects,
     * are skipped.
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
     * Builds the yearly all-day event for [contact]; the year, if known, is encoded in the
     * description.
     *
     * @param existingId the event to update, or 0 for a new event (only a new event gets a UID)
     * @param reminderMinutes the reminder setting; [KashCalDataStore.REMINDER_OFF] for none
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

        val reminders = if (reminderMinutes != KashCalDataStore.REMINDER_OFF) {
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
            rrule = ContactEventUtils.YEARLY_RRULE,
            caldavUrl = eventType.getCaldavUrl(contact.lookupKey, date.month, date.day),
            syncStatus = SyncStatus.SYNCED, // Read-only, no push needed
            dtstamp = System.currentTimeMillis(),
            reminders = reminders
        )
    }

    /**
     * Schedules [event]'s reminders over the reminder lookahead window. Failures are logged and
     * don't fail the sync.
     *
     * @param isModified cancel the event's existing reminders first
     */
    private suspend fun scheduleRemindersForEvent(
        event: Event,
        calendarColor: Int,
        isModified: Boolean
    ) {
        try {
            // Cancel before the empty-reminders return, or turning the reminder off would
            // leave the old AlarmManager alarm scheduled.
            if (isModified) {
                reminderScheduler.cancelRemindersForEvent(event.id)
            }

            if (event.reminders.isNullOrEmpty()) return

            val occurrences = eventReader.getOccurrencesForEventInScheduleWindow(
                event.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS
            )
            if (occurrences.isEmpty()) return

            reminderScheduler.scheduleRemindersForEvent(
                event = event,
                occurrences = occurrences,
                calendarColor = calendarColor
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to schedule reminders for event ${event.id}: ${e.message}")
        }
    }
}
