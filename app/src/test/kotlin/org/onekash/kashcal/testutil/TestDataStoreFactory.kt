package org.onekash.kashcal.testutil

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Test utility for creating mock KashCalDataStore instances.
 *
 * Default behavior returns Int.MAX_VALUE for syncPastDays ("All events"),
 * which maintains backward compatibility for existing tests.
 */
object TestDataStoreFactory {

    /**
     * Create a mock DataStore with "All events" sync lookback (default behavior).
     */
    fun createDefault(): KashCalDataStore {
        return mockk<KashCalDataStore>(relaxed = true) {
            every { syncPastDays } returns flowOf(Int.MAX_VALUE)
        }
    }

    /**
     * Create a mock DataStore with a specific sync lookback in days.
     */
    fun createWithSyncLookback(days: Int): KashCalDataStore {
        return mockk<KashCalDataStore>(relaxed = true) {
            every { syncPastDays } returns flowOf(days)
        }
    }
}
