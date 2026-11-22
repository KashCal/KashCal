package org.onekash.kashcal.domain.planner

import android.util.Log
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.reader.EventReader

private const val TAG = "SeriesAllDayLookup"

/**
 * Returns the all-day flag of each Room series with a changed occurrence among [events], keyed
 * by the series row id, for [movedCue]. One read for all of them, none when there is no changed
 * Room occurrence. A failed read is logged and returns an empty map, so the events still show
 * and the cue falls back to what the occurrence alone says.
 */
suspend fun EventReader.lookUpRoomSeriesAllDay(events: Iterable<DisplayEvent>): ImmutableMap<Long, Boolean> {
    val seriesIds = events
        .filterIsInstance<DisplayEvent.Room>()
        .filter { it.isChangedOccurrence() }
        .map { it.occurrence.eventId }
        .distinct()
    if (seriesIds.isEmpty()) return persistentMapOf()
    return try {
        getEventsByIds(seriesIds).mapValues { (_, event) -> event.isAllDay }.toPersistentMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Series look-up failed: ${e.javaClass.simpleName}")
        persistentMapOf()
    }
}
