package org.onekash.kashcal.data.ics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.util.maskUid
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IcsSubscriptionRepo"

/**
 * extraProperties key used to preserve the original UID of an ICS-subscription
 * master that was disambiguated due to duplicate-UID input (issue #227). The
 * stored uid column carries the synthetic `{originalUid}#dup={startTs}`. A
 * future export-fidelity PR can teach IcsPatcher.serialize to restore the
 * original UID from this key on outbound ICS.
 */
internal const val ORIGINAL_UID_EXTRA_KEY = "X-KASHCAL-ORIGINAL-UID"

/**
 * Repository for managing ICS calendar subscriptions.
 *
 * Handles:
 * - Adding/removing ICS subscriptions
 * - Fetching and parsing ICS feeds
 * - Syncing events to database
 * - Deleting orphaned events (removed from feed)
 *
 * Industry standard behavior:
 * - Events are read-only (overwritten on sync)
 * - Deleted events from feed are removed locally
 * - Auto-creates "ICS Subscriptions" account on first subscription
 */
@Singleton
class IcsSubscriptionRepository @Inject constructor(
    private val database: KashCalDatabase,
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val accountRepository: AccountRepository,
    private val calendarsDao: CalendarsDao,
    private val eventsDao: EventsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val icsFetcher: IcsFetcher,
    private val reminderScheduler: ReminderScheduler,
    private val eventReader: EventReader
) {

    // ========== Subscription Management ==========

    /**
     * Get all subscriptions as reactive Flow.
     */
    fun getAllSubscriptions(): Flow<List<IcsSubscription>> {
        return icsSubscriptionsDao.getAll()
    }

    /**
     * Get subscription by ID.
     */
    suspend fun getSubscriptionById(id: Long): IcsSubscription? {
        return icsSubscriptionsDao.getById(id)
    }

    /**
     * Add a new ICS subscription.
     *
     * Creates the ICS account and calendar if needed, then fetches and parses
     * the ICS feed to populate events.
     *
     * @param url The ICS feed URL (supports webcal:// and https://)
     * @param name Display name for the subscription
     * @param color Calendar color (ARGB integer)
     * @return Result containing the subscription or error
     */
    suspend fun addSubscription(
        url: String,
        name: String,
        color: Int
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            // Check for duplicate URL
            if (icsSubscriptionsDao.urlExists(normalizeUrl(url))) {
                return@withContext SubscriptionResult.Error(
                    message = "Subscription already exists for this URL",
                    isDuplicate = true
                )
            }

            // Ensure ICS account exists (auto-create on first subscription)
            val accountId = ensureIcsAccountExists()

            // Create calendar for this subscription
            val normalizedUrl = normalizeUrl(url)
            val calendar = Calendar(
                accountId = accountId,
                caldavUrl = normalizedUrl, // Use ICS URL as caldav_url for subscriptions
                displayName = name,
                color = color,
                isReadOnly = true, // ICS subscriptions are read-only
                isVisible = true,
                isDefault = false
            )
            val calendarId = calendarsDao.insert(calendar)

            // Create subscription record
            val subscription = IcsSubscription(
                url = normalizeUrl(url),
                name = name,
                color = color,
                calendarId = calendarId
            )
            val subscriptionId = icsSubscriptionsDao.insert(subscription)

            // Fetch and sync events
            val syncResult = refreshSubscription(subscriptionId)
            if (syncResult is SyncResult.Error) {
                // Update subscription with error but don't fail - subscription is created
                icsSubscriptionsDao.updateSyncError(subscriptionId, syncResult.message)
            }

            SubscriptionResult.Success(subscription.copy(id = subscriptionId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add subscription: $url", e)
            SubscriptionResult.Error(e.message ?: "Unknown error adding subscription")
        }
    }

    /**
     * Remove an ICS subscription.
     *
     * Deletes the subscription, its calendar, and all associated events.
     * Calendar deletion cascades to events via FK.
     *
     * IMPORTANT: Cancels reminders BEFORE cascade delete to prevent orphaned
     * AlarmManager alarms. This is Android best practice - AlarmManager.cancel()
     * is safe on non-existent alarms (no-op).
     */
    suspend fun removeSubscription(subscriptionId: Long) = withContext(Dispatchers.IO) {
        val subscription = icsSubscriptionsDao.getById(subscriptionId) ?: return@withContext

        // Cancel reminders for all events BEFORE cascade delete
        val events = eventsDao.getAllMasterEventsForCalendar(subscription.calendarId)
        for (event in events) {
            reminderScheduler.cancelRemindersForEvent(event.id)
        }
        Log.i(TAG, "Cancelled reminders for ${events.size} events before removing subscription")

        // Delete calendar (cascades to events and subscription via FK)
        calendarsDao.deleteById(subscription.calendarId)

        Log.i(TAG, "Removed subscription: ${subscription.name}")
    }

    /**
     * Update subscription settings.
     */
    suspend fun updateSubscriptionSettings(
        subscriptionId: Long,
        name: String,
        color: Int,
        syncIntervalHours: Int
    ) = withContext(Dispatchers.IO) {
        icsSubscriptionsDao.updateSettings(subscriptionId, name, color, syncIntervalHours)

        // Also update the associated calendar
        val subscription = icsSubscriptionsDao.getById(subscriptionId) ?: return@withContext
        calendarsDao.updateDisplayName(subscription.calendarId, name)
        calendarsDao.updateColor(subscription.calendarId, color)
    }

    /**
     * Enable or disable a subscription.
     *
     * When disabling, cancels all reminders for the subscription's events.
     * When enabling, triggers a refresh which will reschedule reminders.
     */
    suspend fun setSubscriptionEnabled(subscriptionId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        val subscription = icsSubscriptionsDao.getById(subscriptionId)

        if (!enabled && subscription != null) {
            // Cancel reminders when disabling
            val events = eventsDao.getAllMasterEventsForCalendar(subscription.calendarId)
            for (event in events) {
                reminderScheduler.cancelRemindersForEvent(event.id)
            }
            Log.i(TAG, "Cancelled reminders for disabled subscription: ${subscription.name}")
        }

        icsSubscriptionsDao.setEnabled(subscriptionId, enabled)

        if (enabled && subscription != null) {
            // Reschedule reminders when enabling by refreshing subscription
            // This will re-sync and schedule reminders for all events
            refreshSubscription(subscriptionId)
        }
    }

    // ========== Sync Operations ==========

    /**
     * Refresh a single subscription.
     *
     * Fetches the ICS feed, parses events, and updates the database.
     * Handles ETag/Last-Modified for conditional requests.
     */
    suspend fun refreshSubscription(subscriptionId: Long): SyncResult = withContext(Dispatchers.IO) {
        val subscription = icsSubscriptionsDao.getById(subscriptionId)
            ?: return@withContext SyncResult.Error("Subscription not found")

        if (!subscription.enabled) {
            return@withContext SyncResult.Skipped("Subscription is disabled")
        }

        Log.d(TAG, "Refreshing subscription: ${subscription.name}")

        try {
            // Self-heal stuck subscriptions (#219 follow-up): if we hold
            // conditional headers but have zero events stored locally, the
            // local interpretation must have failed at some point (parser
            // regression, schema reset, etc.). Drop the conditionals so the
            // server returns 200 + body and we re-parse from scratch.
            val hasCachedConditionals = subscription.etag != null || subscription.lastModified != null
            val isStuckWithStaleConditionals = hasCachedConditionals && !eventsDao.anyByCalendarIdAndCaldavUrlPrefix(
                calendarId = subscription.calendarId,
                urlPrefix = IcsSubscription.eventSourcePrefix(subscriptionId)
            )
            val effectiveSubscription = if (isStuckWithStaleConditionals) {
                Log.i(TAG, "Subscription ${subscription.name} has cached conditional headers but 0 events; forcing full fetch")
                subscription.copy(etag = null, lastModified = null)
            } else {
                subscription
            }

            // Fetch ICS content
            val fetchResult = fetchIcsContent(effectiveSubscription)

            when (fetchResult) {
                is FetchResult.NotModified -> {
                    // Content unchanged, update last sync time
                    icsSubscriptionsDao.updateSyncSuccess(
                        id = subscriptionId,
                        timestamp = System.currentTimeMillis(),
                        etag = subscription.etag,
                        lastModified = subscription.lastModified
                    )
                    return@withContext SyncResult.NotModified
                }

                is FetchResult.Success -> {
                    // Parse ICS content
                    val events = IcsParserService.parseIcsContent(
                        content = fetchResult.content,
                        calendarId = subscription.calendarId,
                        subscriptionId = subscriptionId
                    )

                    // Don't cache the ETag for a parse failure (#219 follow-up,
                    // durable fix). If the feed has BEGIN:VEVENT lines but our
                    // parser returned zero events, we have a parser regression
                    // — caching the ETag would make the next refresh hit 304
                    // and never retry. Surface as an error instead.
                    if (events.isEmpty() && fetchResult.content.contains("BEGIN:VEVENT")) {
                        val message = "Parsed 0 events from non-empty feed"
                        Log.w(TAG, "$message: ${subscription.name}")
                        icsSubscriptionsDao.updateSyncError(subscriptionId, message)
                        return@withContext SyncResult.Error(message)
                    }

                    // Get calendar for color (needed for reminders)
                    val calendar = calendarsDao.getById(subscription.calendarId)

                    // Sync events to database
                    val syncCount = syncEventsToDatabase(
                        events = events,
                        calendarId = subscription.calendarId,
                        subscriptionId = subscriptionId,
                        calendarColor = calendar?.color ?: subscription.color
                    )

                    // Update subscription sync status
                    icsSubscriptionsDao.updateSyncSuccess(
                        id = subscriptionId,
                        timestamp = System.currentTimeMillis(),
                        etag = fetchResult.etag,
                        lastModified = fetchResult.lastModified
                    )

                    Log.i(TAG, "Synced ${syncCount.added} new, ${syncCount.updated} updated, ${syncCount.deleted} deleted events for ${subscription.name}")
                    return@withContext SyncResult.Success(syncCount)
                }

                is FetchResult.Error -> {
                    icsSubscriptionsDao.updateSyncError(subscriptionId, fetchResult.message)
                    return@withContext SyncResult.Error(fetchResult.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing subscription: ${subscription.name}", e)
            val errorMessage = e.message ?: "Unknown sync error"
            icsSubscriptionsDao.updateSyncError(subscriptionId, errorMessage)
            return@withContext SyncResult.Error(errorMessage)
        }
    }

    /**
     * Refresh all enabled subscriptions that are due for sync.
     */
    suspend fun refreshAllDueSubscriptions(): List<SyncResult> = withContext(Dispatchers.IO) {
        val subscriptions = icsSubscriptionsDao.getEnabled()
        val results = mutableListOf<SyncResult>()

        for (subscription in subscriptions) {
            if (subscription.isDueForSync()) {
                results.add(refreshSubscription(subscription.id))
            }
        }

        results
    }

    /**
     * Force refresh all enabled subscriptions.
     */
    suspend fun forceRefreshAll(): List<SyncResult> = withContext(Dispatchers.IO) {
        val subscriptions = icsSubscriptionsDao.getEnabled()
        subscriptions.map { refreshSubscription(it.id) }
    }

    // ========== Private Helper Methods ==========

    /**
     * Ensure ICS provider account exists, create if not.
     * Returns the account ID.
     */
    private suspend fun ensureIcsAccountExists(): Long {
        val existing = accountRepository.getAccountByProviderAndEmail(
            AccountProvider.ICS,
            IcsSubscription.ACCOUNT_EMAIL
        )

        if (existing != null) {
            return existing.id
        }

        // Create ICS account
        val account = Account(
            provider = AccountProvider.ICS,
            email = IcsSubscription.ACCOUNT_EMAIL,
            displayName = "ICS Subscriptions",
            isEnabled = true
        )

        val accountId = accountRepository.createAccount(account)
        Log.i(TAG, "Created ICS account with ID: $accountId")
        return accountId
    }

    /**
     * Fetch ICS content from URL with conditional request support.
     * Delegates to injected IcsFetcher for testability.
     */
    private suspend fun fetchIcsContent(subscription: IcsSubscription): FetchResult {
        return when (val result = icsFetcher.fetch(subscription)) {
            is IcsFetcher.FetchResult.Success -> FetchResult.Success(
                content = result.content,
                etag = result.etag,
                lastModified = result.lastModified
            )
            is IcsFetcher.FetchResult.NotModified -> FetchResult.NotModified
            is IcsFetcher.FetchResult.Error -> FetchResult.Error(result.message)
        }
    }

    /**
     * Sync parsed events to database with atomic transaction.
     *
     * Three-pass processing:
     * - Pre-pass: disambiguate duplicate-UID master groups (issue #227 —
     *   Google's private ICS export sometimes emits two non-exception
     *   VEVENTs sharing a UID, which would trip the master-uniqueness
     *   trigger). Mutates uid/importId/caldavUrl for affected rows and
     *   stashes the original UID in extraProperties.
     * - Pass 1: Process master events. Sweeps previously-promoted
     *   standalone orphan rows for any incoming master's UID before insert.
     * - Pass 2: Process exception events, linking to masters. Falls through
     *   to a standalone insert when no master is found in the feed (issue
     *   #227 — Google emits exceptions whose master is sliced out of the
     *   export window). The :RECID: marker in the standalone's importId
     *   is what the next sync's sweep keys on.
     *
     * Per RFC 5545, exception events share the same UID as their master
     * but differ by RECURRENCE-ID. We use importId (which includes RECURRENCE-ID)
     * for unique identification.
     *
     * CLAUDE.md Pattern #1: @Transaction for multi-step operations.
     * CLAUDE.md Pattern #13: Model B occurrence linking via linkException().
     */
    private suspend fun syncEventsToDatabase(
        events: List<Event>,
        calendarId: Long,
        subscriptionId: Long,
        calendarColor: Int
    ): SyncCount {
        var added = 0
        var updated = 0
        var deleted = 0

        // Pre-pass: disambiguate duplicate-UID masters before they enter
        // the transaction. Google's private ICS export sometimes emits two
        // non-exception VEVENTs sharing the same UID; without this, the
        // second master's INSERT would trip trigger_master_event_unique_insert.
        val disambiguatedEvents = disambiguateDuplicateUidMasters(events, subscriptionId)

        database.runInTransaction {
            val sourcePrefix = IcsSubscription.eventSourcePrefix(subscriptionId)
            val existingEvents = eventsDao.getByCalendarIdAndCaldavUrlPrefix(
                calendarId = calendarId,
                urlPrefix = sourcePrefix
            )

            // Mutable so the master-pass orphan sweep can invalidate stale
            // entries; without this Pass 2 would update a deleted row id
            // (silent no-op) and the new exception would never be written.
            val existingByImportId = existingEvents
                .associateBy { extractImportIdFromSource(it.caldavUrl) }
                .toMutableMap()
            val newImportIds = disambiguatedEvents.map { it.importId }.toSet()

            // Delete orphaned events (cancel reminders first!)
            val orphanedImportIds = existingByImportId.keys - newImportIds
            for (importId in orphanedImportIds) {
                val existingEvent = existingByImportId[importId] ?: continue
                reminderScheduler.cancelRemindersForEvent(existingEvent.id)
                eventsDao.deleteById(existingEvent.id)
                existingByImportId.remove(importId)
                deleted++
            }

            // Separate masters and exceptions
            val masters = disambiguatedEvents.filter { it.originalInstanceTime == null }
            val exceptions = disambiguatedEvents.filter { it.originalInstanceTime != null }

            // Track master IDs for exception linking
            val masterIdByUid = mutableMapOf<String, Long>()

            // PASS 1: Process masters
            for (event in masters) {
                try {
                    // Sweep previously-promoted standalone orphans with this
                    // UID before inserting the master — otherwise the master's
                    // INSERT trips trigger_master_event_unique_insert on the
                    // (uid, calendar_id, original_event_id IS NULL) collision.
                    // Sync N's standalone is the row with the same uid AND a
                    // :RECID: marker in importId. The inbound exception in the
                    // same feed will be re-inserted as a fresh exception row
                    // in Pass 2.
                    val staleOrphanKeys = existingByImportId
                        .filter { (key, value) ->
                            key != null &&
                                key.contains(":RECID:") &&
                                value.uid == event.uid &&
                                value.originalEventId == null
                        }
                        .keys
                        .toList()
                    for (staleKey in staleOrphanKeys) {
                        val staleRow = existingByImportId[staleKey] ?: continue
                        reminderScheduler.cancelRemindersForEvent(staleRow.id)
                        eventsDao.deleteById(staleRow.id)
                        existingByImportId.remove(staleKey)
                        deleted++
                    }

                    val existingEvent = existingByImportId[event.importId]
                    val (eventId, isNew) = upsertEvent(event, existingEvent)
                    masterIdByUid[event.uid] = eventId

                    val savedEvent = event.copy(id = eventId)
                    occurrenceGenerator.regenerateOccurrences(savedEvent)
                    scheduleRemindersForEvent(savedEvent, calendarColor, isModified = !isNew)

                    if (isNew) added++ else updated++
                } catch (e: Exception) {
                    // Most often this is the master-uniqueness trigger
                    // aborting on a degenerate same-UID-same-DTSTART input
                    // that survived the disambiguation pre-pass (issue #227).
                    Log.w(
                        TAG,
                        "Master insert aborted: uid=${event.uid.maskUid()} startTs=${event.startTs} cause=${e.message}"
                    )
                }
            }

            // Also include existing masters for exceptions referencing pre-existing masters
            for (existingEvent in existingEvents) {
                if (existingEvent.rrule != null && existingEvent.originalEventId == null) {
                    masterIdByUid.putIfAbsent(existingEvent.uid, existingEvent.id)
                }
            }

            // PASS 2: Process exceptions with master linkage
            for (event in exceptions) {
                try {
                    val masterId = masterIdByUid[event.uid]
                    if (masterId == null) {
                        // Issue #227: orphaned RECURRENCE-ID — no master in
                        // this feed. Promote to standalone so the user sees
                        // the event. Keep :RECID: in importId so a future
                        // sync can sweep this row when the master arrives.
                        val standalone = event.copy(
                            originalEventId = null,
                            originalInstanceTime = null
                        )
                        val existingEvent = existingByImportId[event.importId]
                        val (eventId, isNew) = upsertEvent(standalone, existingEvent)
                        val savedEvent = standalone.copy(id = eventId)
                        occurrenceGenerator.regenerateOccurrences(savedEvent)
                        scheduleRemindersForEvent(savedEvent, calendarColor, isModified = !isNew)
                        if (isNew) added++ else updated++
                        continue
                    }

                    // Link exception to master
                    val linkedEvent = event.copy(originalEventId = masterId)
                    val existingEvent = existingByImportId[event.importId]
                    val (eventId, isNew) = upsertEvent(linkedEvent, existingEvent)

                    val savedEvent = linkedEvent.copy(id = eventId)

                    // Use linkException for Model B occurrence handling
                    val originalTime = savedEvent.originalInstanceTime
                    if (originalTime != null) {
                        occurrenceGenerator.linkException(masterId, originalTime, savedEvent)
                    }

                    scheduleRemindersForEvent(savedEvent, calendarColor, isModified = !isNew)
                    if (isNew) added++ else updated++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process exception event ${event.uid}: ${e.message}")
                }
            }
        }

        return SyncCount(added, updated, deleted)
    }

    /**
     * Disambiguate duplicate-UID master events from a parsed feed.
     *
     * Issue #227: Google's private ICS export sometimes emits two
     * non-exception VEVENTs sharing the same UID. RFC 5545 §3.8.4.7 says
     * UID should be unique per calendar; Google's feed violates that, but
     * `trigger_master_event_unique_insert` enforces it at the DB layer
     * (added in MIGRATION_8_9 to prevent CalDAV-sync duplicates from issue
     * #36). Without disambiguation the second master's INSERT would be
     * caught silently and the user would see only one of the two events.
     *
     * The disambiguator is `event.startTs` — event-intrinsic, deterministic
     * across syncs. The original UID is preserved in extraProperties under
     * [ORIGINAL_UID_EXTRA_KEY] so a future export-fidelity PR can restore it.
     */
    internal fun disambiguateDuplicateUidMasters(
        events: List<Event>,
        subscriptionId: Long
    ): List<Event> {
        val masterUidCounts = events
            .filter { it.originalInstanceTime == null }
            .groupingBy { it.uid }
            .eachCount()
        if (masterUidCounts.values.none { it > 1 }) return events

        val sourcePrefix = IcsSubscription.eventSourcePrefix(subscriptionId)
        return events.map { event ->
            val needsMutation = event.originalInstanceTime == null &&
                (masterUidCounts[event.uid] ?: 0) > 1
            if (!needsMutation) return@map event

            val originalUid = event.uid
            val mutatedUid = "$originalUid#dup=${event.startTs}"
            val mutatedImportId = mutatedUid
            val mutatedCaldavUrl = "$sourcePrefix$mutatedImportId"
            val updatedExtras = (event.extraProperties ?: emptyMap()) +
                (ORIGINAL_UID_EXTRA_KEY to originalUid)
            event.copy(
                uid = mutatedUid,
                importId = mutatedImportId,
                caldavUrl = mutatedCaldavUrl,
                extraProperties = updatedExtras
            )
        }
    }

    /**
     * Insert or update an event.
     *
     * @return Pair of (event ID, isNew)
     */
    private suspend fun upsertEvent(event: Event, existingEvent: Event?): Pair<Long, Boolean> {
        return if (existingEvent != null) {
            eventsDao.update(event.copy(id = existingEvent.id))
            Pair(existingEvent.id, false)
        } else {
            Pair(eventsDao.insert(event), true)
        }
    }

    /**
     * Schedule reminders for a synced event.
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

            // Get occurrences - handle exception events specially
            val occurrences = if (event.originalEventId != null) {
                // Exception event - get the linked occurrence by exception event ID
                listOfNotNull(eventReader.getOccurrenceByExceptionEventId(event.id))
            } else {
                // Regular/master event - get all occurrences in schedule window
                eventReader.getOccurrencesForEventInScheduleWindow(event.id)
            }

            if (occurrences.isEmpty()) return

            reminderScheduler.scheduleRemindersForEvent(
                event = event,
                occurrences = occurrences,
                calendarColor = calendarColor
            )
        } catch (e: Exception) {
            // Log but don't fail sync for reminder scheduling errors
            Log.e(TAG, "Failed to schedule reminders for event ${event.id}: ${e.message}")
        }
    }

    /**
     * Extract importId from caldavUrl.
     *
     * Format: "ics_subscription:{subscriptionId}:{importId}"
     * ImportId format: "{uid}" or "{uid}:RECID:{timestamp}"
     *
     * Uses limit=3 to preserve colons within the importId itself.
     */
    private fun extractImportIdFromSource(source: String?): String? {
        if (source == null) return null
        val parts = source.split(":", limit = 3)
        return if (parts.size >= 3) parts[2] else null
    }

    /**
     * Normalize URL (webcal:// → https://).
     */
    private fun normalizeUrl(url: String): String {
        return url.trim()
            .replace("webcal://", "https://")
            .replace("webcals://", "https://")
    }

    // ========== Result Classes ==========

    sealed class SubscriptionResult {
        data class Success(val subscription: IcsSubscription) : SubscriptionResult()
        data class Error(
            val message: String,
            /**
             * True when the URL is already subscribed. Lets the UI distinguish
             * "duplicate" from generic errors and show a localized message
             * without parsing [message] (which is internal English text).
             */
            val isDuplicate: Boolean = false
        ) : SubscriptionResult()
    }

    sealed class SyncResult {
        data class Success(val count: SyncCount) : SyncResult()
        data object NotModified : SyncResult()
        data class Skipped(val reason: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    data class SyncCount(
        val added: Int,
        val updated: Int,
        val deleted: Int
    )

    private sealed class FetchResult {
        data class Success(
            val content: String,
            val etag: String?,
            val lastModified: String?
        ) : FetchResult()

        data object NotModified : FetchResult()
        data class Error(val message: String) : FetchResult()
    }
}
