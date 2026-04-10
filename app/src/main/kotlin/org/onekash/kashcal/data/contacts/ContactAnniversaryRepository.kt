package org.onekash.kashcal.data.contacts

import android.content.ContentResolver
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing contact anniversary calendar.
 *
 * Thin subclass of [BaseContactEventRepository] for Hilt DI.
 * All logic lives in the base class; this class provides the anniversary-specific
 * DataStore reminder accessor.
 */
@Singleton
class ContactAnniversaryRepository @Inject constructor(
    accountRepository: AccountRepository,
    calendarsDao: CalendarsDao,
    eventsDao: EventsDao,
    occurrenceGenerator: OccurrenceGenerator,
    reminderScheduler: ReminderScheduler,
    eventReader: EventReader,
    contentResolver: ContentResolver,
    dataStore: KashCalDataStore
) : BaseContactEventRepository(
    accountRepository, calendarsDao, eventsDao, occurrenceGenerator,
    reminderScheduler, eventReader, contentResolver, dataStore,
    ContactEventType.ANNIVERSARY
) {
    override suspend fun getReminderMinutes(): Int = dataStore.getAnniversaryReminder()
}
