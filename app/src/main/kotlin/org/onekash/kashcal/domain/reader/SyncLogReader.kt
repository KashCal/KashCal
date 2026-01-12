package org.onekash.kashcal.domain.reader

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.SyncLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles sync log read operations for UI display.
 *
 * Provides read-only access to sync logs for debugging and diagnostics.
 * This is the domain layer entry point for sync log queries from ViewModels.
 *
 * Note: SyncLogsDao is also used directly by CalDavSyncEngine for WRITING logs,
 * which is appropriate for the sync layer. This reader is specifically for
 * UI layer read access following the EventCoordinator pattern.
 */
@Singleton
class SyncLogReader @Inject constructor(
    private val database: KashCalDatabase
) {
    private val syncLogsDao by lazy { database.syncLogsDao() }

    /**
     * Get recent sync logs as a reactive Flow.
     *
     * @param limit Maximum number of logs to return (default 100)
     * @return Flow of sync logs, most recent first
     */
    fun getRecentLogs(limit: Int = 100): Flow<List<SyncLog>> {
        return syncLogsDao.getRecentLogs(limit)
    }
}
