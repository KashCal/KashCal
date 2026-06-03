package org.onekash.kashcal.data.ics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.db.entity.SyncStatus
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
 * extraProperties sentinel marking a master row as a synthetic placeholder
 * synthesized by ICS sync because the feed contained orphan exception events
 * (UID + RECURRENCE-ID) but no master VEVENT for that UID. Synthetic masters
 * exist solely as FK targets so orphan exceptions can link to a master and
 * survive the master-uniqueness trigger; they have status=CANCELLED, no
 * RRULE, zero duration, and produce no occurrences. When a real master
 * later arrives in the feed, the upsert path matches by importId=uid and
 * the synthetic row is mutated in place into a real master (rrule populated,
 * status flipped, sentinel cleared).
 */
internal const val SYNTHETIC_MASTER_EXTRA_KEY = "X-KASHCAL-SYNTHETIC-MASTER"

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
    private val eventReader: EventReader,
    @ApplicationContext private val context: Context
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
            displayName = context.getString(R.string.subscriptions_title),
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
     * Pipeline:
     * - Pre-pass: disambiguate duplicate-UID master groups (issue #227 Bug
     *   B — Google's private ICS export sometimes emits two non-exception
     *   VEVENTs sharing a UID, which would trip the master-uniqueness
     *   trigger). Mutates uid/importId/caldavUrl for affected rows and
     *   stashes the original UID in extraProperties.
     * - Inside the transaction:
     *   - Synthesize one placeholder master per orphan UID (issue #227 Bug
     *     A — feed contains exceptions whose master VEVENT is sliced out
     *     of the export window). Synthetic masters have rrule=null,
     *     status=CANCELLED, importId=uid, zero duration, and are tagged
     *     with [SYNTHETIC_MASTER_EXTRA_KEY] in extraProperties. They exist
     *     solely as FK targets so orphan exceptions can link to a master
     *     row and the master-uniqueness trigger sees only one master per
     *     (uid, calendar) combination. Synthetic masters never call
     *     `regenerateOccurrences` or `scheduleRemindersForEvent`.
     *   - Orphan-cleanup sweep deletes existing rows whose importIds are
     *     not in the union of (real importIds, synthetic importIds).
     *   - PASS 1: process master events. Sweeps legacy `:RECID:`-marked
     *     standalone rows from prior versions (pre-synthesis builds) when
     *     a real master arrives.
     *   - PASS 2: process exception events, linking each to its master via
     *     `masterIdByUid` (now populated for every UID in the feed).
     *
     * Per RFC 5545, exception events share the same UID as their master
     * but differ by RECURRENCE-ID. We use importId (which includes
     * RECURRENCE-ID) for unique identification.
     *
     * Self-heal: when a real master later arrives in the feed for a UID
     * that previously had only orphan exceptions, the upsert path matches
     * the existing synthetic by `existingByImportId[uid]` and mutates the
     * row in place into a real master — exception FK references survive.
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

            // Synthesize one placeholder master per orphan UID (no master in
            // the feed, no master in DB). Must run inside the transaction:
            // we depend on `existingByImportId` (built here) and the inserts
            // must roll back atomically on failure. Must run BEFORE
            // `newImportIds` is computed so synthetic importIds are folded
            // in and the orphan-cleanup sweep doesn't immediately delete them.
            val syntheticMasters = synthesizeMastersForOrphanUids(
                feedEvents = disambiguatedEvents,
                existingByImportId = existingByImportId,
                subscriptionId = subscriptionId
            )

            val newImportIds = (
                disambiguatedEvents.map { it.importId } +
                    syntheticMasters.map { it.importId }
                ).toSet()

            // Delete orphaned events (cancel reminders first!)
            val orphanedImportIds = existingByImportId.keys - newImportIds
            for (importId in orphanedImportIds) {
                val existingEvent = existingByImportId[importId] ?: continue
                reminderScheduler.cancelRemindersForEvent(existingEvent.id)
                eventsDao.deleteById(existingEvent.id)
                existingByImportId.remove(importId)
                deleted++
            }

            // Track master IDs for exception linking. Pre-populated from
            // synthesis so PASS 2 finds a master for every orphan UID.
            val masterIdByUid = mutableMapOf<String, Long>()

            // Insert synthetic masters first so their row ids are recorded
            // in masterIdByUid before PASS 2 looks them up.
            for (synthetic in syntheticMasters) {
                deleted += sweepLegacyOrphanStandalones(synthetic.uid, existingByImportId)
                val existingEvent = existingByImportId[synthetic.importId]
                val (eventId, isNew) = upsertEvent(synthetic, existingEvent)
                masterIdByUid[synthetic.uid] = eventId
                if (isNew) added++ else updated++
                // Synthetic masters intentionally skip both
                // `regenerateOccurrences` and `scheduleRemindersForEvent`:
                // they have no occurrences (no rrule, status=CANCELLED) and
                // no reminders to schedule.
            }

            val realMasters = disambiguatedEvents.filter { it.originalInstanceTime == null }
            val exceptions = disambiguatedEvents.filter { it.originalInstanceTime != null }

            // PASS 1: Process real masters
            for (event in realMasters) {
                try {
                    // Sweep previously-promoted standalone orphans with this
                    // UID before inserting the master — otherwise the master's
                    // INSERT trips trigger_master_event_unique_insert on the
                    // (uid, calendar_id, original_event_id IS NULL) collision.
                    deleted += sweepLegacyOrphanStandalones(event.uid, existingByImportId)

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

            // Also include existing masters for exceptions referencing
            // pre-existing masters. Two shapes qualify: real recurring
            // masters (rrule != null) and synthetic placeholders inserted
            // by a prior sync (rrule == null, sentinel set). Including the
            // synthetic shape is what makes idempotent re-syncs of an
            // orphan-only feed work: synthesis short-circuits when the
            // synthetic already exists, so PASS 2 must find its id here
            // or it would skip every exception.
            for (existingEvent in existingEvents) {
                if (existingEvent.originalEventId == null &&
                    (existingEvent.rrule != null ||
                        existingEvent.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true")
                ) {
                    masterIdByUid.putIfAbsent(existingEvent.uid, existingEvent.id)
                }
            }

            // PASS 2: Process exceptions with master linkage. Every UID has
            // a master after synthesis, so the standalone-fallback path is
            // gone — `masterIdByUid[event.uid]` is always non-null here
            // unless something pathological happened (e.g. synthesis aborted
            // at the trigger), in which case we log + skip.
            for (event in exceptions) {
                try {
                    val masterId = masterIdByUid[event.uid]
                    if (masterId == null) {
                        Log.w(
                            TAG,
                            "Exception with no master after synthesis: uid=${event.uid.maskUid()} — skipping"
                        )
                        continue
                    }

                    val linkedEvent = event.copy(originalEventId = masterId)
                    val existingEvent = existingByImportId[event.importId]
                    val (eventId, isNew) = upsertEvent(linkedEvent, existingEvent)

                    val savedEvent = linkedEvent.copy(id = eventId)

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
     * Sweep legacy `:RECID:`-marked standalone rows for [uid] from
     * [existingByImportId]. These are rows from older builds (v23.7.45 and
     * earlier) that promoted orphan exceptions to standalone events with
     * importId `{uid}:RECID:{datetime}`. When a master arrives — real or
     * synthetic — those legacy rows must be deleted before the master
     * inserts, otherwise the master's INSERT trips the master-uniqueness
     * trigger on the (uid, calendar_id, original_event_id IS NULL) collision.
     *
     * Returns the count of rows swept (for the deletion total).
     */
    private suspend fun sweepLegacyOrphanStandalones(
        uid: String,
        existingByImportId: MutableMap<String?, Event>
    ): Int {
        val staleOrphanKeys = existingByImportId
            .filter { (key, value) ->
                key != null &&
                    key.contains(":RECID:") &&
                    value.uid == uid &&
                    value.originalEventId == null
            }
            .keys
            .toList()
        var swept = 0
        for (staleKey in staleOrphanKeys) {
            val staleRow = existingByImportId[staleKey] ?: continue
            reminderScheduler.cancelRemindersForEvent(staleRow.id)
            eventsDao.deleteById(staleRow.id)
            existingByImportId.remove(staleKey)
            swept++
        }
        return swept
    }

    /**
     * Build synthetic placeholder masters for orphan-exception UIDs.
     *
     * Issue #227 Bug A: Google's private ICS export emits exception VEVENTs
     * (UID + RECURRENCE-ID) whose master VEVENT is sliced out of the
     * export window. Pre-fix, only the first orphan per UID survived (the
     * second-and-subsequent orphan-promote-to-standalone INSERTs tripped
     * `trigger_master_event_unique_insert`). Now we synthesize one master
     * per orphan UID up front, exceptions link to it via the existing
     * PASS 2 path, and the trigger sees exactly one master per (uid,
     * calendar) combination — the trigger has nothing to fire on.
     *
     * Synthesis is purely additive: only kicks in for UIDs that have
     * exception events but no master in either the new feed or the
     * existing DB rows. The master row itself is "inert":
     * - status = "CANCELLED" (semantically inactive per RFC 5545 §3.8.1.11)
     * - rrule = null (not recurring)
     * - dtstart == dtend == earliestRecurrenceId (zero duration; produces
     *   no occurrences)
     * - extraProperties[SYNTHETIC_MASTER_EXTRA_KEY] = "true" (sentinel for
     *   future code to identify these rows)
     *
     * Self-heal: when a real master later arrives in the feed for a UID
     * that previously had only orphan exceptions, the upsert path matches
     * the existing synthetic at `existingByImportId[uid]` and mutates the
     * row in place — rrule populates, status flips to CONFIRMED, sentinel
     * clears, exception FK references survive.
     */
    private fun synthesizeMastersForOrphanUids(
        feedEvents: List<Event>,
        existingByImportId: Map<String?, Event>,
        subscriptionId: Long
    ): List<Event> {
        val mastersInFeedByUid = feedEvents
            .filter { it.originalInstanceTime == null }
            .map { it.uid }
            .toSet()
        val orphansByUid = feedEvents
            .filter { it.originalInstanceTime != null }
            .groupBy { it.uid }

        if (orphansByUid.isEmpty()) return emptyList()

        val sourcePrefix = IcsSubscription.eventSourcePrefix(subscriptionId)
        val now = System.currentTimeMillis()

        return orphansByUid.mapNotNull { (uid, orphans) ->
            // Skip UIDs that already have a master in this feed.
            if (uid in mastersInFeedByUid) return@mapNotNull null
            // If a master already exists in the DB for this UID, branch:
            // - real master (rrule != null OR not our sentinel): synthesis
            //   isn't needed — exceptions will link to it via the master
            //   backfill below.
            // - synthetic from a prior sync (sentinel set): return the
            //   existing row unchanged so its importId flows into
            //   `newImportIds` and the orphan-cleanup sweep doesn't delete
            //   it (which would CASCADE-delete every linked exception).
            //   The synthesis loop then no-op-updates the row in place.
            val existingForUid = existingByImportId[uid]
            if (existingForUid != null && existingForUid.originalEventId == null) {
                return@mapNotNull if (
                    existingForUid.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true"
                ) existingForUid else null
            }

            // Deterministic seed: the orphan with the earliest
            // RECURRENCE-ID. `orphans.first()` would be deterministic too
            // (parser preserves feed order) but a feed reorder would shift
            // the seed; minBy here pins it to a feed-intrinsic value.
            val seedOrphan = orphans.minBy { it.originalInstanceTime!! }
            val earliestRecurrenceId = seedOrphan.originalInstanceTime!!

            Event(
                uid = uid,
                importId = uid,
                calendarId = seedOrphan.calendarId,
                title = seedOrphan.title,
                startTs = earliestRecurrenceId,
                endTs = earliestRecurrenceId,
                dtstamp = now,
                status = "CANCELLED",
                rrule = null,
                caldavUrl = "$sourcePrefix$uid",
                // Synthetic placeholder is local-only; no upstream server
                // exists for it (ICS subscriptions are read-only). SYNCED
                // is the correct steady-state value.
                syncStatus = SyncStatus.SYNCED,
                extraProperties = mapOf(SYNTHETIC_MASTER_EXTRA_KEY to "true")
            )
        }
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
