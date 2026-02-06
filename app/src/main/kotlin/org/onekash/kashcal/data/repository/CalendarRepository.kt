package org.onekash.kashcal.data.repository

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Single source of truth for Calendar operations.
 *
 * Replaces direct DAO access for calendars.
 *
 * Usage:
 * ```kotlin
 * class MyService @Inject constructor(
 *     private val calendarRepository: CalendarRepository
 * )
 * ```
 */
interface CalendarRepository {

    // ========== Reactive Queries (Flow) ==========

    /**
     * Get all calendars as reactive Flow.
     */
    fun getAllCalendarsFlow(): Flow<List<Calendar>>

    /**
     * Get visible calendars as Flow.
     */
    fun getVisibleCalendarsFlow(): Flow<List<Calendar>>

    /**
     * Get calendars for account as Flow.
     */
    fun getCalendarsForAccountFlow(accountId: Long): Flow<List<Calendar>>

    /**
     * Get calendar count by provider as Flow.
     */
    fun getCalendarCountByProviderFlow(provider: AccountProvider): Flow<Int>

    // ========== One-Shot Queries ==========

    /**
     * Get calendar by ID.
     */
    suspend fun getCalendarById(id: Long): Calendar?

    /**
     * Get calendars by IDs (batch operation).
     * Used for efficient batch loading in sync operations.
     */
    suspend fun getCalendarsByIds(ids: List<Long>): List<Calendar>

    /**
     * Get calendar by CalDAV URL.
     */
    suspend fun getCalendarByUrl(caldavUrl: String): Calendar?

    /**
     * Get calendars for account (one-shot).
     */
    suspend fun getCalendarsForAccountOnce(accountId: Long): List<Calendar>

    /**
     * Get all calendars (one-shot).
     */
    suspend fun getAllCalendars(): List<Calendar>

    /**
     * Get any default calendar for quick event creation.
     */
    suspend fun getDefaultCalendar(): Calendar?

    /**
     * Get default calendar for specific account.
     */
    suspend fun getDefaultCalendarForAccount(accountId: Long): Calendar?

    /**
     * Get enabled calendars for sync.
     */
    suspend fun getEnabledCalendars(): List<Calendar>

    // ========== Write Operations ==========

    /**
     * Create new calendar. Returns row ID.
     */
    suspend fun createCalendar(calendar: Calendar): Long

    /**
     * Update existing calendar.
     */
    suspend fun updateCalendar(calendar: Calendar)

    /**
     * Delete calendar by ID.
     */
    suspend fun deleteCalendar(calendarId: Long)

    /**
     * Set calendar visibility.
     */
    suspend fun setVisibility(calendarId: Long, visible: Boolean)

    /**
     * Set all calendars for account visible/hidden.
     */
    suspend fun setAllVisible(accountId: Long, visible: Boolean)

    /**
     * Set default calendar for account.
     */
    suspend fun setDefaultCalendar(accountId: Long, calendarId: Long)

    // ========== Sync Metadata ==========

    /**
     * Update sync token and ctag after sync.
     */
    suspend fun updateSyncToken(calendarId: Long, syncToken: String?, ctag: String?)

    /**
     * Update ctag only.
     */
    suspend fun updateCtag(calendarId: Long, ctag: String?)
}
