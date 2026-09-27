package org.onekash.kashcal.domain.reader

import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.SyncLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the debugging sync log rows for ViewModels, which don't access DAOs.
 *
 * The sync layer writes and prunes the rows through
 * [org.onekash.kashcal.data.db.dao.SyncLogsDao] directly.
 */
@Singleton
class SyncLogReader @Inject constructor(
    private val database: KashCalDatabase
) {
    private val syncLogsDao by lazy { database.syncLogsDao() }

    /** Emits up to [limit] sync logs, most recent first. */
    fun getRecentLogs(limit: Int = 100): Flow<List<SyncLog>> {
        return syncLogsDao.getRecentLogs(limit)
    }
}
