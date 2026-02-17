package org.onekash.kashcal.data.calendar_provider

/**
 * Repository interface for reading device calendars from Android's CalendarProvider.
 *
 * Implementations must handle SecurityException (permission revoked)
 * by returning empty results.
 */
interface CalendarProviderRepository {

    /**
     * Get all visible device calendars.
     *
     * @return List of device calendars, or empty list if permission denied
     */
    suspend fun getDeviceCalendars(): List<DeviceCalendar>

    /**
     * Get calendar instances (pre-expanded occurrences) for a day range.
     *
     * @param startDayCode Start day in YYYYMMDD format (inclusive)
     * @param endDayCode End day in YYYYMMDD format (inclusive)
     * @param enabledCalendarIds Set of calendar IDs to include
     * @param hideDeclined Whether to hide declined events
     * @return List of instances, or empty list if permission denied
     */
    suspend fun getInstancesForDayRange(
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean = false
    ): List<DeviceCalendarInstance>

    /**
     * Search calendar instances by text query within a day range.
     *
     * Uses Instances.CONTENT_SEARCH_URI for CalendarProvider text search.
     *
     * @param query Search text (matched against title, description, location)
     * @param startDayCode Start day in YYYYMMDD format (inclusive)
     * @param endDayCode End day in YYYYMMDD format (inclusive)
     * @param enabledCalendarIds Set of calendar IDs to include
     * @param hideDeclined Whether to hide declined events
     * @return List of matching instances, or empty list if permission denied
     */
    suspend fun searchInstances(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean = false
    ): List<DeviceCalendarInstance>

    /**
     * Remove stored enabled calendar IDs that no longer exist in CalendarProvider.
     *
     * Handles uninstalled sync adapters, removed accounts, and deleted calendars.
     * Compares stored enabledDeviceCalendarIds against actual calendars from
     * [getDeviceCalendars] and removes stale IDs.
     *
     * @param dataStore KashCalDataStore to read/write enabled calendar IDs
     */
    suspend fun pruneStaleCalendarIds(dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore)
}
