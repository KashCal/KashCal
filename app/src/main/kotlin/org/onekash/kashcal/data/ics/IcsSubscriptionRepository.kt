package org.onekash.kashcal.data.ics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
 * extraProperties key holding the original UID of an ICS-subscription master renamed for
 * duplicate-UID input (#227); the uid column carries `{originalUid}#dup={startTs}`.
 *
 * Nothing reads it yet: it is kept so outbound ICS can restore the original UID, which
 * `IcsPatcher.serialize` doesn't do.
 */
internal const val ORIGINAL_UID_EXTRA_KEY = "X-KASHCAL-ORIGINAL-UID"

/**
 * extraProperties sentinel marking a synthetic master, built by
 * [IcsSubscriptionRepository.synthesizeMastersForOrphanUids] for exceptions whose master VEVENT
 * isn't in the feed.
 *
 * [OccurrenceGenerator] generates no occurrences for such a row and some `EventsDao` queries
 * skip it. CalDAV pull writes the same value (`PULL_SYNTHETIC_MASTER_EXTRA_KEY`).
 */
internal const val SYNTHETIC_MASTER_EXTRA_KEY = "X-KASHCAL-SYNTHETIC-MASTER"

/**
 * Adds, removes and refreshes ICS subscriptions, writing each feed's events into the
 * subscription's read-only calendar.
 *
 * - Events are read-only and overwritten on each refresh.
 * - Events gone from the feed are deleted locally.
 * - The first subscription creates the ICS account, named by `R.string.subscriptions_title`.
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

    /** Observes every subscription. */
    fun getAllSubscriptions(): Flow<List<IcsSubscription>> {
        return icsSubscriptionsDao.getAll()
    }

    suspend fun getSubscriptionById(id: Long): IcsSubscription? {
        return icsSubscriptionsDao.getById(id)
    }

    /**
     * Adds a subscription and its calendar, creating the ICS account if needed, then refreshes it.
     *
     * A failed first refresh still returns success; the error is stored on the subscription.
     * An already subscribed URL returns an error with `isDuplicate` set.
     *
     * @param url the feed URL; webcal:// and webcals:// are rewritten to https://.
     * @param color calendar color (ARGB).
     */
    suspend fun addSubscription(
        url: String,
        name: String,
        color: Int
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        try {
            if (icsSubscriptionsDao.urlExists(normalizeUrl(url))) {
                return@withContext SubscriptionResult.Error(
                    message = "Subscription already exists for this URL",
                    isDuplicate = true
                )
            }

            val accountId = ensureIcsAccountExists()

            val normalizedUrl = normalizeUrl(url)
            val calendar = Calendar(
                accountId = accountId,
                caldavUrl = normalizedUrl, // the feed URL
                displayName = name,
                color = color,
                isReadOnly = true,
                isVisible = true,
                isDefault = false
            )
            val calendarId = calendarsDao.insert(calendar)

            val subscription = IcsSubscription(
                url = normalizeUrl(url),
                name = name,
                color = color,
                calendarId = calendarId
            )
            val subscriptionId = icsSubscriptionsDao.insert(subscription)

            val syncResult = refreshSubscription(subscriptionId)
            if (syncResult is SyncResult.Error) {
                // The subscription exists; keep it and record the error.
                icsSubscriptionsDao.updateSyncError(subscriptionId, syncResult.message)
            }

            SubscriptionResult.Success(subscription.copy(id = subscriptionId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add subscription: $url", e)
            SubscriptionResult.Error(e.message ?: "Unknown error adding subscription")
        }
    }

    /**
     * Deletes a subscription's calendar, which cascades to its events and the subscription row.
     *
     * Cancels the master events' reminders first, or their AlarmManager alarms would outlive the
     * rows. AlarmManager.cancel() on a missing alarm is a no-op.
     */
    suspend fun removeSubscription(subscriptionId: Long) = withContext(Dispatchers.IO) {
        val subscription = icsSubscriptionsDao.getById(subscriptionId) ?: return@withContext

        val events = eventsDao.getAllMasterEventsForCalendar(subscription.calendarId)
        for (event in events) {
            reminderScheduler.cancelRemindersForEvent(event.id)
        }
        Log.i(TAG, "Cancelled reminders for ${events.size} events before removing subscription")

        calendarsDao.deleteById(subscription.calendarId)

        Log.i(TAG, "Removed subscription: ${subscription.name}")
    }

    /** Updates a subscription's name, color and interval, and the calendar's name and color. */
    suspend fun updateSubscriptionSettings(
        subscriptionId: Long,
        name: String,
        color: Int,
        syncIntervalHours: Int
    ) = withContext(Dispatchers.IO) {
        icsSubscriptionsDao.updateSettings(subscriptionId, name, color, syncIntervalHours)

        val subscription = icsSubscriptionsDao.getById(subscriptionId) ?: return@withContext
        calendarsDao.updateDisplayName(subscription.calendarId, name)
        calendarsDao.updateColor(subscription.calendarId, color)
    }

    /**
     * Enables or disables a subscription.
     *
     * Disabling cancels the master events' reminders. Enabling refreshes the feed, which
     * reschedules reminders only when the feed returns a body; a 304 schedules none.
     */
    suspend fun setSubscriptionEnabled(subscriptionId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        val subscription = icsSubscriptionsDao.getById(subscriptionId)

        if (!enabled && subscription != null) {
            val events = eventsDao.getAllMasterEventsForCalendar(subscription.calendarId)
            for (event in events) {
                reminderScheduler.cancelRemindersForEvent(event.id)
            }
            Log.i(TAG, "Cancelled reminders for disabled subscription: ${subscription.name}")
        }

        icsSubscriptionsDao.setEnabled(subscriptionId, enabled)

        if (enabled && subscription != null) {
            refreshSubscription(subscriptionId)
        }
    }

    // ========== Sync Operations ==========

    /**
     * Fetches a subscription's feed with its ETag and Last-Modified, and writes the events.
     *
     * Returns [SyncResult.Skipped] for a disabled subscription. Every error except a missing
     * subscription is also stored on the subscription row.
     */
    suspend fun refreshSubscription(subscriptionId: Long): SyncResult = withContext(Dispatchers.IO) {
        val subscription = icsSubscriptionsDao.getById(subscriptionId)
            ?: return@withContext SyncResult.Error("Subscription not found")

        if (!subscription.enabled) {
            return@withContext SyncResult.Skipped("Subscription is disabled")
        }

        Log.d(TAG, "Refreshing subscription: ${subscription.name}")

        try {
            // Cached validators with zero stored events means an earlier parse or store
            // failed (#219). Drop the validators so the server returns the body again.
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

            val fetchResult = fetchIcsContent(effectiveSubscription)

            when (fetchResult) {
                is FetchResult.NotModified -> {
                    // Unchanged: record the sync time, keep the stored validators.
                    icsSubscriptionsDao.updateSyncSuccess(
                        id = subscriptionId,
                        timestamp = System.currentTimeMillis(),
                        etag = subscription.etag,
                        lastModified = subscription.lastModified
                    )
                    return@withContext SyncResult.NotModified
                }

                is FetchResult.Success -> {
                    val events = IcsParserService.parseIcsContent(
                        content = fetchResult.content,
                        calendarId = subscription.calendarId,
                        subscriptionId = subscriptionId
                    )

                    // Zero events from a feed with VEVENT lines is a parse failure (#219), or a
                    // feed whose events are all CANCELLED. Caching the ETag would make every
                    // later refresh a 304 that never re-parses, so report an error instead.
                    if (events.isEmpty() && fetchResult.content.contains("BEGIN:VEVENT")) {
                        val message = "Parsed 0 events from non-empty feed"
                        Log.w(TAG, "$message: ${subscription.name}")
                        icsSubscriptionsDao.updateSyncError(subscriptionId, message)
                        return@withContext SyncResult.Error(message)
                    }

                    // Calendar color for reminder notifications.
                    val calendar = calendarsDao.getById(subscription.calendarId)

                    val syncCount = syncEventsToDatabase(
                        events = events,
                        calendarId = subscription.calendarId,
                        subscriptionId = subscriptionId,
                        calendarColor = calendar?.color ?: subscription.color
                    )

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
        } catch (e: CancellationException) {
            // A stopped refresh isn't a failed one. Swallowing this would log an error
            // against a feed whose fetch was cut short, and let the caller's loop carry on
            // to the next feed on a cancelled coroutine.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing subscription: ${subscription.name}", e)
            val errorMessage = e.message ?: "Unknown sync error"
            icsSubscriptionsDao.updateSyncError(subscriptionId, errorMessage)
            return@withContext SyncResult.Error(errorMessage)
        }
    }

    /** Refreshes the enabled subscriptions that are due ([IcsSubscription.isDueForSync]). */
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

    /** Refreshes every enabled subscription, due or not. */
    suspend fun forceRefreshAll(): List<SyncResult> = withContext(Dispatchers.IO) {
        val subscriptions = icsSubscriptionsDao.getEnabled()
        subscriptions.map { refreshSubscription(it.id) }
    }

    // ========== Private Helper Methods ==========

    /** Returns the ICS account's id, creating the account if it doesn't exist. */
    private suspend fun ensureIcsAccountExists(): Long {
        val existing = accountRepository.getAccountByProviderAndEmail(
            AccountProvider.ICS,
            IcsSubscription.ACCOUNT_EMAIL
        )

        if (existing != null) {
            return existing.id
        }

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

    /** Fetches through the injected [IcsFetcher], which tests replace. */
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
     * Writes a feed's parsed events to its calendar in one transaction and returns the counts.
     *
     * Exceptions share their master's UID and differ by RECURRENCE-ID (RFC 5545), so rows are
     * matched by importId, which includes the RECURRENCE-ID. In order:
     * - Rename duplicate-UID masters ([disambiguateDuplicateUidMasters]).
     * - In the transaction, build a synthetic master for each UID with exceptions but no master
     *   ([synthesizeMastersForOrphanUids]).
     * - Delete stored rows whose importId is in neither the feed nor the synthetic masters.
     * - Write the synthetic masters, then the real masters (PASS 1), then the exceptions linked to
     *   their master (PASS 2). Each master first sweeps legacy standalone rows for its UID
     *   ([sweepLegacyOrphanStandalones]).
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

        val disambiguatedEvents = disambiguateDuplicateUidMasters(events, subscriptionId)

        database.runInTransaction {
            val sourcePrefix = IcsSubscription.eventSourcePrefix(subscriptionId)
            val existingEvents = eventsDao.getByCalendarIdAndCaldavUrlPrefix(
                calendarId = calendarId,
                urlPrefix = sourcePrefix
            )

            // Mutable so the sweeps can remove what they delete; otherwise PASS 2 would update
            // a deleted row id (a silent no-op) and never write the new exception.
            val existingByImportId = existingEvents
                .associateBy { extractImportIdFromSource(it.caldavUrl) }
                .toMutableMap()

            // Must run before `newImportIds` is computed, so synthetic importIds are in it and
            // the orphan sweep below doesn't delete them.
            val syntheticMasters = synthesizeMastersForOrphanUids(
                feedEvents = disambiguatedEvents,
                existingByImportId = existingByImportId,
                subscriptionId = subscriptionId
            )

            val newImportIds = (
                disambiguatedEvents.map { it.importId } +
                    syntheticMasters.map { it.importId }
                ).toSet()

            // Delete rows gone from the feed, cancelling their reminders first.
            val orphanedImportIds = existingByImportId.keys - newImportIds
            for (importId in orphanedImportIds) {
                val existingEvent = existingByImportId[importId] ?: continue
                reminderScheduler.cancelRemindersForEvent(existingEvent.id)
                eventsDao.deleteById(existingEvent.id)
                existingByImportId.remove(importId)
                deleted++
            }

            // Master row id by UID, for PASS 2 to link exceptions.
            val masterIdByUid = mutableMapOf<String, Long>()

            // Synthetic masters first, so their row ids are in masterIdByUid before PASS 2.
            for (synthetic in syntheticMasters) {
                deleted += sweepLegacyOrphanStandalones(synthetic.uid, existingByImportId)
                val existingEvent = existingByImportId[synthetic.importId]
                val (eventId, isNew) = upsertEvent(synthetic, existingEvent)
                masterIdByUid[synthetic.uid] = eventId
                if (isNew) added++ else updated++
                // No regenerateOccurrences or scheduleRemindersForEvent: a synthetic master
                // has no occurrences and no reminders.
            }

            val realMasters = disambiguatedEvents.filter { it.originalInstanceTime == null }
            val exceptions = disambiguatedEvents.filter { it.originalInstanceTime != null }

            // PASS 1: real masters.
            for (event in realMasters) {
                try {
                    // Before the insert, or it trips trigger_master_event_unique_insert (see
                    // [sweepLegacyOrphanStandalones]).
                    deleted += sweepLegacyOrphanStandalones(event.uid, existingByImportId)

                    val existingEvent = existingByImportId[event.importId]
                    val (eventId, isNew) = upsertEvent(event, existingEvent)
                    masterIdByUid[event.uid] = eventId

                    val savedEvent = event.copy(id = eventId)
                    occurrenceGenerator.regenerateOccurrences(savedEvent)
                    scheduleRemindersForEvent(savedEvent, calendarColor, isModified = !isNew)

                    if (isNew) added++ else updated++
                } catch (e: Exception) {
                    // For example the master-uniqueness trigger on two masters with the same
                    // UID and DTSTART, which disambiguation renames alike (#227). The rest of
                    // the sync goes on.
                    Log.w(
                        TAG,
                        "Master insert aborted: uid=${event.uid.maskUid()} startTs=${event.startTs} cause=${e.message}"
                    )
                }
            }

            // Also map UIDs to stored masters not written above: recurring masters
            // (rrule != null) and synthetic ones. A synthetic master from a prior sync is
            // normally mapped already, by the synthetic loop or, once its real master is in the
            // feed, by PASS 1; putIfAbsent keeps that id.
            for (existingEvent in existingEvents) {
                if (existingEvent.originalEventId == null &&
                    (existingEvent.rrule != null ||
                        existingEvent.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true")
                ) {
                    masterIdByUid.putIfAbsent(existingEvent.uid, existingEvent.id)
                }
            }

            // PASS 2: exceptions, linked to their master. After synthesis a UID lacks a master
            // only when, for example, its master insert failed in PASS 1; the exception is
            // then logged and skipped.
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
     * Deletes legacy standalone rows for [uid] and removes them from [existingByImportId];
     * returns how many.
     *
     * Builds up to v23.7.45 stored an exception with no master as a standalone event (no
     * original_event_id) with importId `{uid}:RECID:{datetime}`. Such a row must go before a
     * master for [uid] is inserted, or the insert trips the master-uniqueness trigger on the
     * (uid, calendar_id, original_event_id IS NULL) collision.
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
     * Returns one synthetic master per UID that has exceptions but no master in the feed or the
     * database, plus any stored synthetic master still needed. Writes nothing.
     *
     * Google's private ICS export emits exception VEVENTs (UID + RECURRENCE-ID) whose master is
     * outside the export window (#227). The synthetic master gives them an FK target, so none is
     * stored as a standalone master and the master-uniqueness trigger sees one master per
     * (uid, calendar). The row is inert:
     * - status CANCELLED (RFC 5545 §3.8.1.11)
     * - rrule null
     * - start and end at the earliest RECURRENCE-ID, so zero duration
     * - [SYNTHETIC_MASTER_EXTRA_KEY] set to "true"
     *
     * When the real master later arrives, it has the synthetic's importId (the UID), so the
     * upsert updates that row in place: the real master's rrule and status replace the
     * placeholder's, the sentinel is gone, and the exceptions' FK references survive.
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
            // A master already stored for this UID:
            // - without the sentinel: no synthesis; the master mapping in
            //   syncEventsToDatabase maps the UID to that row if it is recurring.
            // - a synthetic from a prior sync: return it unchanged so its importId is in
            //   `newImportIds` and the orphan sweep doesn't delete it (which would
            //   cascade-delete every linked exception). The synthesis loop re-writes it
            //   unchanged.
            val existingForUid = existingByImportId[uid]
            if (existingForUid != null && existingForUid.originalEventId == null) {
                return@mapNotNull if (
                    existingForUid.extraProperties?.get(SYNTHETIC_MASTER_EXTRA_KEY) == "true"
                ) existingForUid else null
            }

            // Seed from the earliest RECURRENCE-ID. `orphans.first()` follows feed order
            // (the parser keeps it), so a reordered feed would shift the seed.
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
                // Nothing upstream to push to (ICS subscriptions are read-only), so SYNCED.
                syncStatus = SyncStatus.SYNCED,
                extraProperties = mapOf(SYNTHETIC_MASTER_EXTRA_KEY to "true")
            )
        }
    }

    /**
     * Renames masters that share a UID to `{uid}#dup={startTs}`, with a matching importId and
     * caldavUrl; returns [events] unchanged when no UID repeats.
     *
     * Google's private ICS export sometimes emits two non-exception VEVENTs with one UID (#227),
     * against RFC 5545 §3.8.4.7. `trigger_master_event_unique_insert` (MIGRATION_6_7, against
     * duplicate CalDAV-sync masters, #36) would abort the second master's insert, which the sync
     * catches, so only one of the two events would show. `startTs` belongs to the event, so the
     * name is the same on every sync. The original UID goes in [ORIGINAL_UID_EXTRA_KEY].
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

    /** Updates [existingEvent]'s row with [event], or inserts it; returns (row id, isNew). */
    private suspend fun upsertEvent(event: Event, existingEvent: Event?): Pair<Long, Boolean> {
        return if (existingEvent != null) {
            eventsDao.update(event.copy(id = existingEvent.id))
            Pair(existingEvent.id, false)
        } else {
            Pair(eventsDao.insert(event), true)
        }
    }

    /**
     * Schedules reminders for a synced event's occurrences in the reminder window.
     *
     * An exception gets its one linked occurrence. Failures are logged and don't fail the sync.
     *
     * @param isModified cancels the event's existing reminders first, so a time change doesn't
     *   leave the old alarms.
     */
    private suspend fun scheduleRemindersForEvent(
        event: Event,
        calendarColor: Int,
        isModified: Boolean
    ) {
        if (event.reminders.isNullOrEmpty()) return

        try {
            if (isModified) {
                reminderScheduler.cancelRemindersForEvent(event.id)
            }

            val occurrences = if (event.originalEventId != null) {
                listOfNotNull(eventReader.getOccurrenceByExceptionEventId(event.id))
            } else {
                eventReader.getOccurrencesForEventInScheduleWindow(
                    event.id, ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS
                )
            }

            if (occurrences.isEmpty()) return

            reminderScheduler.scheduleRemindersForEvent(
                event = event,
                occurrences = occurrences,
                calendarColor = calendarColor
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reminders for event ${event.id}: ${e.message}")
        }
    }

    /**
     * Returns the importId from a caldavUrl `ics_subscription:{subscriptionId}:{importId}`
     * ([IcsSubscription.eventSourcePrefix]), or null for null or a value with under three parts.
     *
     * An importId is `{uid}` or `{uid}:RECID:{datetime}`; limit = 3 keeps its colons.
     */
    private fun extractImportIdFromSource(source: String?): String? {
        if (source == null) return null
        val parts = source.split(":", limit = 3)
        return if (parts.size >= 3) parts[2] else null
    }

    /** Trims [url] and rewrites a leading webcal:// or webcals:// to https://. */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        // Only the leading scheme, so a webcal:// inside a query param
        // (e.g. ?redirect=webcal://…) stays intact.
        return when {
            trimmed.startsWith("webcal://") -> "https://" + trimmed.removePrefix("webcal://")
            trimmed.startsWith("webcals://") -> "https://" + trimmed.removePrefix("webcals://")
            else -> trimmed
        }
    }

    // ========== Result Classes ==========

    sealed class SubscriptionResult {
        data class Success(val subscription: IcsSubscription) : SubscriptionResult()
        data class Error(
            val message: String,
            /**
             * True when the URL is already subscribed, so the UI can show a localized message
             * without parsing [message], which is internal English text.
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
