package org.onekash.kashcal.domain.initializer

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.parser.icaldav.EventToICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.util.maskUid
import org.onekash.kashcal.widget.WidgetUpdateManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repairs, once and offline, the series stored before a series whose start is a UTC time got
 * the zone "UTC" ([ICalEventMapper.storedZoneOf]). Such a row holds no zone, so its occurrences
 * expanded in the device zone and moved an hour at each clock change.
 *
 * Series pass: a series row with no zone, a stored copy and not waiting to be deleted
 * ([EventsDao.getUtcSeriesRepairCandidates]) is repaired when its copy's series VEVENT has a
 * UTC start: the zone is set and the occurrences regenerated in one transaction, then the
 * reminders re-armed. Only the zone column changes. Nothing is queued for upload: a row holding
 * "UTC" writes the text it wrote with no zone ([EventToICalEventMapper.writeZone]). A floating
 * or zoned start, a row without a copy and a copy that doesn't parse are left alone. A row a
 * write changed between the read and the update counts as failed while it still has no zone.
 * The pass is marked done only when no row failed, so a failed row is retried at the next
 * launch; a repaired row is no longer a candidate, so a repeated pass changes nothing.
 *
 * Feed pass, under its own flag so it runs once whatever the series pass does: feed rows keep no
 * stored copy, so every feed's cached ETag and Last-Modified are dropped and its next scheduled
 * refresh downloads and maps it again. No refresh is started here.
 *
 * Run from [org.onekash.kashcal.KashCalApplication] at startup.
 */
@Singleton
class UtcSeriesZoneRepair @Inject constructor(
    private val database: KashCalDatabase,
    private val eventsDao: EventsDao,
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val occurrenceGenerator: OccurrenceGenerator,
    private val reminderScheduler: ReminderScheduler,
    private val eventReader: EventReader,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val dataStore: KashCalDataStore,
) {
    /** Counts of one [run]: rows repaired, rows that failed, and whether the feed pass ran. */
    data class Outcome(val repaired: Int, val failed: Int, val feedsCleared: Boolean)

    private val parser = ICalParser()

    /** Runs each pass that isn't marked done; a failure is logged, only cancellation is thrown. */
    suspend fun run(): Outcome = try {
        runPasses()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "UTC series repair stopped: ${e.javaClass.simpleName}")
        Outcome(repaired = 0, failed = 1, feedsCleared = false)
    }

    private suspend fun runPasses(): Outcome {
        var repaired = 0
        var failed = 0
        if (!dataStore.getUtcSeriesZoneRepaired()) {
            for (row in eventsDao.getUtcSeriesRepairCandidates()) {
                try {
                    val fixed = repairRow(row) ?: continue
                    repaired++
                    rearmReminders(fixed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "Series ${row.uid.maskUid()} not repaired: ${e.javaClass.simpleName}")
                }
            }
            if (failed == 0) dataStore.setUtcSeriesZoneRepaired(true)
        }

        var feedsCleared = false
        if (!dataStore.getFeedValidatorsClearedForUtcSeries()) {
            icsSubscriptionsDao.clearAllValidators()
            dataStore.setFeedValidatorsClearedForUtcSeries(true)
            feedsCleared = true
        }

        if (repaired > 0) widgetUpdateManager.updateAllWidgets()
        Log.i(TAG, "Repaired $repaired series, $failed failed, feeds cleared: $feedsCleared")
        return Outcome(repaired, failed, feedsCleared)
    }

    /**
     * Gives [row] the UTC zone and regenerates its occurrences, in one transaction, when its
     * stored copy says the series starts at a UTC time. Returns the repaired row, or null when the
     * copy says otherwise or a write since the read gave the row a zone. Throws when a write
     * changed the row but left it without a zone, so the caller counts it as failed.
     */
    private suspend fun repairRow(row: Event): Event? {
        val copy = row.rawIcal ?: return null
        val vevents = parser.parseAllEvents(copy).getOrNull() ?: return null
        val series = vevents.firstOrNull { it.recurrenceId == null && it.uid == row.uid }
            ?: vevents.firstOrNull { it.recurrenceId == null }
            ?: return null
        if (ICalEventMapper.storedZoneOf(series) != ICalEventMapper.UTC_SERIES_ZONE) return null
        return database.withTransaction {
            if (eventsDao.setTimezoneIfUnset(row.id, ICalEventMapper.UTC_SERIES_ZONE, copy) != 1) {
                val now = eventsDao.getById(row.id) ?: return@withTransaction null
                check(now.timezone != null) { "changed while repairing" }
                return@withTransaction null
            }
            val fixed = requireNotNull(eventsDao.getById(row.id))
            occurrenceGenerator.regenerateOccurrences(fixed)
            fixed
        }
    }

    /**
     * Reconciles [row]'s reminders with its regenerated occurrences and arms the missing ones, as
     * a sync does for a changed series. A failure is logged; the daily reminder scan re-arms what
     * is missing.
     */
    private suspend fun rearmReminders(row: Event) {
        try {
            reminderScheduler.reconcileRemindersForEvent(row)
            if (row.reminders.isNullOrEmpty()) return
            val calendar = eventReader.getCalendarById(row.calendarId) ?: return
            val occurrences = eventReader.getOccurrencesForEventInScheduleWindow(
                row.id,
                ReminderScheduler.OCCURRENCE_LOOKAHEAD_DAYS,
                ReminderScheduler.STARTED_LOOKBACK_MS
            )
            reminderScheduler.scheduleRemindersForEvent(row, occurrences, calendar.color)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reminders of ${row.uid.maskUid()} not re-armed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "UtcSeriesZoneRepair"
    }
}
