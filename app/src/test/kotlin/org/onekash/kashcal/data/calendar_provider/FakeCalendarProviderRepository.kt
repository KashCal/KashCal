package org.onekash.kashcal.data.calendar_provider

/**
 * Fake CalendarProviderRepository for testing.
 *
 * Returns pre-configured lists of device calendars and instances.
 * Throws SecurityException when configured to simulate permission revocation.
 */
class FakeCalendarProviderRepository : CalendarProviderRepository {

    var calendars: List<DeviceCalendar> = emptyList()
    var instances: List<DeviceCalendarInstance> = emptyList()
    var shouldThrowSecurityException: Boolean = false

    override suspend fun getDeviceCalendars(): List<DeviceCalendar> {
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        return calendars
    }

    override suspend fun getInstancesForDayRange(
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean
    ): List<DeviceCalendarInstance> {
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        return instances.filter { it.calendarId in enabledCalendarIds }
    }

    override suspend fun searchInstances(
        query: String,
        startDayCode: Int,
        endDayCode: Int,
        enabledCalendarIds: Set<Long>,
        hideDeclined: Boolean
    ): List<DeviceCalendarInstance> {
        if (shouldThrowSecurityException) throw SecurityException("Calendar permission revoked")
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return instances.filter { instance ->
            instance.calendarId in enabledCalendarIds &&
                (instance.title.lowercase().contains(lowerQuery) ||
                    instance.description.lowercase().contains(lowerQuery) ||
                    instance.location.lowercase().contains(lowerQuery))
        }
    }

    override suspend fun pruneStaleCalendarIds(
        dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore
    ) {
        val storedIds = dataStore.getEnabledDeviceCalendarIds()
        if (storedIds.isEmpty()) return

        val actualCalendarIds = calendars.map { it.id }.toSet()
        val staleIds = storedIds - actualCalendarIds
        if (staleIds.isNotEmpty()) {
            dataStore.setEnabledDeviceCalendarIds(storedIds - staleIds)
        }
    }
}
