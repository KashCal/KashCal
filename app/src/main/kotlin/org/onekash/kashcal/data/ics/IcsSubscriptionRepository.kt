package org.onekash.kashcal.data.ics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IcsSubscriptionRepo"

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
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val accountsDao: AccountsDao,
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
                return@withContext SubscriptionResult.Error("Subscription already exists for this URL")
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
            // Fetch ICS content
            val fetchResult = fetchIcsContent(subscription)

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
        val existing = accountsDao.getByProviderAndEmail(
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

        val accountId = accountsDao.insert(account)
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
     * Sync parsed events to database.
     *
     * Strategy:
     * 1. Get existing event UIDs for this subscription
     * 2. Upsert all parsed events (add new, update existing)
     * 3. Delete events that no longer exist in feed (orphaned)
     * 4. Regenerate occurrences for all events
     * 5. Schedule reminders for events with reminders configured
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

        // Get existing events for this subscription (by caldavUrl source identifier)
        val sourcePrefix = "${IcsSubscription.SOURCE_PREFIX}:${subscriptionId}:"
        val existingEvents = eventsDao.getByCalendarIdInRange(
            calendarId = calendarId,
            startTs = Long.MIN_VALUE,
            endTs = Long.MAX_VALUE
        ).filter { it.caldavUrl?.startsWith(sourcePrefix) == true }

        val existingUids = existingEvents.map { extractUidFromSource(it.caldavUrl) }.toSet()
        val newUids = events.map { it.uid }.toSet()

        // Delete orphaned events (in feed before but not now)
        val orphanedUids = existingUids - newUids
        for (existingEvent in existingEvents) {
            val uid = extractUidFromSource(existingEvent.caldavUrl)
            if (uid in orphanedUids) {
                eventsDao.deleteById(existingEvent.id)
                deleted++
            }
        }

        // Upsert events
        for (event in events) {
            val existingEvent = existingEvents.find {
                extractUidFromSource(it.caldavUrl) == event.uid
            }

            if (existingEvent != null) {
                // Update existing event (preserve local ID)
                val updatedEvent = event.copy(id = existingEvent.id)
                eventsDao.update(updatedEvent)
                updated++

                // Regenerate occurrences
                occurrenceGenerator.regenerateOccurrences(updatedEvent)

                // Reschedule reminders (cancel old, schedule new) for modified events
                scheduleRemindersForEvent(updatedEvent, calendarColor, isModified = true)
            } else {
                // Insert new event
                val eventId = eventsDao.insert(event)
                added++

                // Generate occurrences for new event
                val insertedEvent = event.copy(id = eventId)
                occurrenceGenerator.regenerateOccurrences(insertedEvent)

                // Schedule reminders for new event
                scheduleRemindersForEvent(insertedEvent, calendarColor, isModified = false)
            }
        }

        return SyncCount(added, updated, deleted)
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
     * Extract UID from source identifier.
     * Source format: "ics_subscription:{subscriptionId}:{uid}"
     */
    private fun extractUidFromSource(source: String?): String? {
        if (source == null) return null
        val parts = source.split(":")
        return if (parts.size >= 3) parts.drop(2).joinToString(":") else null
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
        data class Error(val message: String) : SubscriptionResult()
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