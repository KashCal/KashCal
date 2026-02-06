package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CalendarRepository.
 *
 * Provides calendar CRUD operations with proper abstraction over CalendarsDao.
 * Handles visibility management and sync metadata updates.
 *
 * Note: Calendar deletion cascades automatically via Room FK constraints
 * (events → occurrences → scheduled_reminders).
 */
@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val calendarsDao: CalendarsDao
) : CalendarRepository {

    // ========== Reactive Queries (Flow) ==========

    override fun getAllCalendarsFlow(): Flow<List<Calendar>> {
        return calendarsDao.getAll()
    }

    override fun getVisibleCalendarsFlow(): Flow<List<Calendar>> {
        return calendarsDao.getVisibleCalendars()
    }

    override fun getCalendarsForAccountFlow(accountId: Long): Flow<List<Calendar>> {
        return calendarsDao.getByAccountId(accountId)
    }

    override fun getCalendarCountByProviderFlow(provider: AccountProvider): Flow<Int> {
        return calendarsDao.getCalendarCountByProvider(provider.name)
    }

    // ========== One-Shot Queries ==========

    override suspend fun getCalendarById(id: Long): Calendar? {
        return calendarsDao.getById(id)
    }

    override suspend fun getCalendarsByIds(ids: List<Long>): List<Calendar> {
        return calendarsDao.getByIds(ids)
    }

    override suspend fun getCalendarByUrl(caldavUrl: String): Calendar? {
        return calendarsDao.getByCaldavUrl(caldavUrl)
    }

    override suspend fun getCalendarsForAccountOnce(accountId: Long): List<Calendar> {
        return calendarsDao.getByAccountIdOnce(accountId)
    }

    override suspend fun getAllCalendars(): List<Calendar> {
        return calendarsDao.getAllOnce()
    }

    override suspend fun getDefaultCalendar(): Calendar? {
        return calendarsDao.getAnyDefaultCalendar()
    }

    override suspend fun getDefaultCalendarForAccount(accountId: Long): Calendar? {
        return calendarsDao.getDefaultCalendar(accountId)
    }

    override suspend fun getEnabledCalendars(): List<Calendar> {
        return calendarsDao.getEnabledCalendars()
    }

    // ========== Write Operations ==========

    override suspend fun createCalendar(calendar: Calendar): Long {
        return calendarsDao.insert(calendar)
    }

    override suspend fun updateCalendar(calendar: Calendar) {
        calendarsDao.update(calendar)
    }

    override suspend fun deleteCalendar(calendarId: Long) {
        calendarsDao.deleteById(calendarId)
    }

    override suspend fun setVisibility(calendarId: Long, visible: Boolean) {
        calendarsDao.setVisible(calendarId, visible)
    }

    override suspend fun setAllVisible(accountId: Long, visible: Boolean) {
        val calendars = calendarsDao.getByAccountIdOnce(accountId)
        for (calendar in calendars) {
            calendarsDao.setVisible(calendar.id, visible)
        }
    }

    override suspend fun setDefaultCalendar(accountId: Long, calendarId: Long) {
        calendarsDao.setDefaultCalendar(accountId, calendarId)
    }

    // ========== Sync Metadata ==========

    override suspend fun updateSyncToken(calendarId: Long, syncToken: String?, ctag: String?) {
        calendarsDao.updateSyncToken(calendarId, syncToken, ctag)
    }

    override suspend fun updateCtag(calendarId: Long, ctag: String?) {
        calendarsDao.updateCtag(calendarId, ctag)
    }
}
