package org.onekash.kashcal.sync.discovery

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Discover and persist the two RFC 6638 scheduling-delivery facts for an
 * account so the later delivery-routing logic can decide how to deliver
 * invitations:
 *  - the principal's scheduling Outbox URL (§2.1.1, one per account), and
 *  - each calendar collection's auto-schedule capability (§2, per collection).
 *
 * Discovery only — it does not send invitations, POST to the outbox, or read
 * back SCHEDULE-STATUS.
 *
 * Both probes are non-fatal, mirroring [persistCalendarUserAddresses]: any
 * HTTP, network, timeout, or malformed-response failure logs a warning and
 * persists null (= unknown / not advertised), never aborting the sync.
 *
 * The routing rule is left to a later step; this helper carries no
 * server-specific branching — server deviations live as data in the quirks
 * layer, not as control flow here.
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
    // 1. Per-principal: schedule-outbox-URL (RFC 6638 §2.1.1). Wrapped so an
    //    unexpected throw (e.g. a DAO write failing) can't abort the sync — the
    //    client call returns a CalDavResult, but the repository write can throw.
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

    // 2. Per-collection: calendar-auto-schedule capability (RFC 6638 §2). Each
    //    collection is isolated so one calendar's failure doesn't skip the rest.
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
