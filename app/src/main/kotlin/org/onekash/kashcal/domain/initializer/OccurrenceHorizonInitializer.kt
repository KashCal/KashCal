package org.onekash.kashcal.domain.initializer

import dagger.Lazy
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extends every repeating series to the expansion window ahead of now once per process start
 * ([EventCoordinator.ensureOccurrenceHorizon]), so a phone whose daily reminder refresh was
 * held back still has this week's occurrences for reminders, Insights and share availability.
 * The coordinator is built on first use, off the main thread.
 */
@Singleton
class OccurrenceHorizonInitializer @Inject constructor(
    private val eventCoordinator: Lazy<EventCoordinator>,
) {
    /** Runs the pass; failures are logged by the pass itself. */
    suspend fun run(nowMs: Long = System.currentTimeMillis()) {
        eventCoordinator.get().ensureOccurrenceHorizon(nowMs)
    }
}
