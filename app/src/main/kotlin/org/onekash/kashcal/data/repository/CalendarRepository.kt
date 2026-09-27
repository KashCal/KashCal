package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/** Reads and writes Room calendars and their sync metadata in place of the calendars DAO. */
interface CalendarRepository {

    // ========== Reactive Queries (Flow) ==========

    fun getAllCalendarsFlow(): Flow<List<Calendar>>

    fun getVisibleCalendarsFlow(): Flow<List<Calendar>>

    fun getCalendarsForAccountFlow(accountId: Long): Flow<List<Calendar>>

    fun getCalendarCountByProviderFlow(provider: AccountProvider): Flow<Int>

    // ========== One-Shot Queries ==========

    suspend fun getCalendarById(id: Long): Calendar?

    /** Loads [ids] in one query; the push loads its calendars this way. */
    suspend fun getCalendarsByIds(ids: List<Long>): List<Calendar>

    suspend fun getCalendarByUrl(caldavUrl: String): Calendar?

    suspend fun getCalendarsForAccountOnce(accountId: Long): List<Calendar>

    suspend fun getAllCalendars(): List<Calendar>

    /** Returns the visible calendars, read-only ones included. */
    suspend fun getEnabledCalendars(): List<Calendar>

    // ========== Write Operations ==========

    /** Inserts [calendar] and returns its row ID. */
    suspend fun createCalendar(calendar: Calendar): Long

    suspend fun updateCalendar(calendar: Calendar)

    suspend fun deleteCalendar(calendarId: Long)

    suspend fun setVisibility(calendarId: Long, visible: Boolean)

    /** Shows or hides every calendar of the account. */
    suspend fun setAllVisible(accountId: Long, visible: Boolean)

    // ========== Sync Metadata ==========

    /** Stores the sync-token and ctag after a sync. */
    suspend fun updateSyncToken(calendarId: Long, syncToken: String?, ctag: String?)

    /**
     * Stores the ctag only, leaving the sync-token. The sync engine clears it to null after
     * abandoning a conflicted operation, so the next pull can't skip the calendar as unchanged.
     */
    suspend fun updateCtag(calendarId: Long, ctag: String?)

    /** Stores [Calendar.autoScheduleSupported] (RFC 6638 §2); its tri-state is documented there. */
    suspend fun updateAutoScheduleSupported(calendarId: Long, supported: Boolean?)

    /**
     * Updates color, display name and read-only flag in one statement; a null argument leaves
     * that column unchanged, so a server that returns no RFC 7986 color or omits the
     * privilege-set element keeps the local value. The pull calls it when a server probe changed
     * any of them.
     *
     * Never touches [Calendar.localColorOverride].
     */
    suspend fun updateMetadata(
        calendarId: Long,
        color: Int?,
        displayName: String?,
        isReadOnly: Boolean?
    )
}
