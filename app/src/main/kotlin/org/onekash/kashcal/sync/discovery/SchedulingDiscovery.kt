package org.onekash.kashcal.sync.discovery

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Discovers and persists two RFC 6638 scheduling facts for an account:
 *  - the principal's scheduling Outbox URL (§2.1.1, one per account), which the push's
 *    outbox POST reads, and
 *  - each calendar collection's auto-schedule capability (§2), stored in
 *    [Calendar.autoScheduleSupported], which nothing reads today.
 *
 * Discovery only: it sends no invitations, doesn't POST to the outbox, and reads no
 * SCHEDULE-STATUS.
 *
 * Neither probe fails the sync, as in [persistCalendarUserAddresses]: a failed request (HTTP,
 * network, timeout, malformed reply) logs a warning and persists null (unknown or not
 * advertised). Server deviations belong as data in the quirks layer, not as branches here.
 */
internal suspend fun persistSchedulingDiscovery(
    client: CalDavClient,
    principalUrl: String,
    accountId: Long,
    calendars: List<Calendar>,
    accountRepository: AccountRepository,
    calendarRepository: CalendarRepository,
    tag: String
) {
    // 1. Per principal: schedule-outbox-URL (RFC 6638 §2.1.1). The client call returns a
    //    CalDavResult, but the repository write can throw; the catch keeps that from
    //    aborting the sync.
    try {
        val outboxResult = client.discoverScheduleOutboxUrl(principalUrl)
        val outboxUrl = if (outboxResult.isSuccess()) {
            (outboxResult as CalDavResult.Success).data
        } else {
            val error = outboxResult as CalDavResult.Error
            Log.w(tag, "schedule-outbox-URL discovery failed (HTTP ${error.code}); persisting null")
            null
        }
        Log.i(tag, "Outbox discovery for account $accountId: advertised=${outboxUrl != null}")
        accountRepository.updateScheduleOutboxUrl(accountId, outboxUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Outbox discovery failed for account $accountId: ${e.message}")
    }

    // 2. Per collection: calendar-auto-schedule capability (RFC 6638 §2). Each calendar has
    //    its own catch so one failure doesn't skip the rest.
    for (calendar in calendars) {
        try {
            val capabilityResult = client.supportsAutoSchedule(calendar.caldavUrl)
            val supported = if (capabilityResult.isSuccess()) {
                (capabilityResult as CalDavResult.Success).data
            } else {
                val error = capabilityResult as CalDavResult.Error
                Log.w(
                    tag,
                    "auto-schedule capability probe failed (HTTP ${error.code}) for calendar " +
                        "${calendar.id}; persisting unknown"
                )
                null
            }
            calendarRepository.updateAutoScheduleSupported(calendar.id, supported)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Capability discovery failed for calendar ${calendar.id}: ${e.message}")
        }
    }
}
