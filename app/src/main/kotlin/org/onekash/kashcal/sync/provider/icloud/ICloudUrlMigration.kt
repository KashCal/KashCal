package org.onekash.kashcal.sync.provider.icloud

import android.util.Log
import androidx.room.withTransaction
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites stored iCloud URLs from regional hosts to the canonical host, once
 * ([ICloudUrlNormalizer] says why).
 *
 * Covers account homeSetUrl and principalUrl, calendar and event caldavUrl, and pending
 * operation targetUrl. The rewrite runs in one transaction; the done flag is set only after
 * a check finds no regional URL left, so a failed check reruns it next time (idempotent).
 *
 * Called at sync startup in [org.onekash.kashcal.sync.worker.CalDavSyncWorker].
 */
@Singleton
class ICloudUrlMigration @Inject constructor(
    private val database: KashCalDatabase,
    private val dataStore: KashCalDataStore
) {
    companion object {
        private const val TAG = "ICloudUrlMigration"
    }

    /**
     * Runs the migration unless it is already done.
     *
     * @return true if it ran and verified, false if already done or verification failed
     */
    suspend fun migrateIfNeeded(): Boolean {
        if (dataStore.getICloudUrlMigrationCompleted()) {
            return false
        }

        Log.i(TAG, "Starting iCloud URL normalization migration...")

        var accountCount = 0
        var calendarCount = 0
        var eventCount = 0
        var operationCount = 0

        // Verification runs after commit; re-runs skip already-migrated URLs.
        database.withTransaction {
            accountCount = migrateAccounts()
            calendarCount = migrateCalendars()
            eventCount = migrateEvents()
            operationCount = migratePendingOperations()
        }

        if (!verifyNoRegionalUrls()) {
            Log.e(TAG, "Migration verification failed - some regional URLs remain")
            // Not marked done, so it runs again.
            return false
        }

        Log.i(
            TAG,
            "iCloud URL migration complete: $accountCount accounts, " +
                "$calendarCount calendars, $eventCount events, $operationCount operations"
        )

        dataStore.setICloudUrlMigrationCompleted(true)
        return true
    }

    private suspend fun migrateAccounts(): Int {
        val accountsDao = database.accountsDao()
        val icloudAccounts = accountsDao.getByProvider(AccountProvider.ICLOUD)
        var migrated = 0

        icloudAccounts.forEach { account ->
            val homeSetNeedsUpdate = ICloudUrlNormalizer.isRegionalUrl(account.homeSetUrl)
            val principalNeedsUpdate = ICloudUrlNormalizer.isRegionalUrl(account.principalUrl)

            if (homeSetNeedsUpdate || principalNeedsUpdate) {
                val normalizedHomeSetUrl = ICloudUrlNormalizer.normalize(account.homeSetUrl)
                val normalizedPrincipalUrl = ICloudUrlNormalizer.normalize(account.principalUrl)
                accountsDao.updateCalDavUrls(account.id, normalizedPrincipalUrl, normalizedHomeSetUrl)
                migrated++
                Log.d(TAG, "Migrated account ${account.id}: homeSetUrl updated")
            }
        }

        return migrated
    }

    private suspend fun migrateCalendars(): Int {
        val accountsDao = database.accountsDao()
        val calendarsDao = database.calendarsDao()
        val icloudAccountIds = accountsDao.getByProvider(AccountProvider.ICLOUD).map { it.id }
        var migrated = 0

        icloudAccountIds.forEach { accountId ->
            calendarsDao.getByAccountIdOnce(accountId).forEach { calendar ->
                if (ICloudUrlNormalizer.isRegionalUrl(calendar.caldavUrl)) {
                    val normalized = ICloudUrlNormalizer.normalize(calendar.caldavUrl)
                    if (normalized != null) {
                        calendarsDao.updateCaldavUrl(calendar.id, normalized)
                        migrated++
                        Log.d(TAG, "Migrated calendar ${calendar.id}: ${calendar.displayName}")
                    }
                }
            }
        }

        return migrated
    }

    private suspend fun migrateEvents(): Int {
        val eventsDao = database.eventsDao()
        val eventUrls = eventsDao.getICloudEventUrls()
        var migrated = 0

        eventUrls.forEach { projection ->
            if (ICloudUrlNormalizer.isRegionalUrl(projection.caldavUrl)) {
                val normalized = ICloudUrlNormalizer.normalize(projection.caldavUrl)
                if (normalized != null && normalized != projection.caldavUrl) {
                    eventsDao.updateCaldavUrl(projection.id, normalized)
                    migrated++
                }
            }
        }

        if (migrated > 0) {
            Log.d(TAG, "Migrated $migrated event URLs")
        }

        return migrated
    }

    /** Covers every pending operation; only regional iCloud URLs match. */
    private suspend fun migratePendingOperations(): Int {
        val pendingOpsDao = database.pendingOperationsDao()
        val operations = pendingOpsDao.getAllOnce()
        var migrated = 0

        operations.forEach { op ->
            if (ICloudUrlNormalizer.isRegionalUrl(op.targetUrl)) {
                val normalized = ICloudUrlNormalizer.normalize(op.targetUrl)
                if (normalized != null) {
                    pendingOpsDao.updateTargetUrl(op.id, normalized)
                    migrated++
                }
            }
        }

        if (migrated > 0) {
            Log.d(TAG, "Migrated $migrated pending operation URLs")
        }

        return migrated
    }

    /** Returns true if no account, calendar, event or pending operation has a regional URL. */
    private suspend fun verifyNoRegionalUrls(): Boolean {
        val accountsDao = database.accountsDao()
        val calendarsDao = database.calendarsDao()
        val eventsDao = database.eventsDao()
        val pendingOpsDao = database.pendingOperationsDao()

        val icloudAccounts = accountsDao.getByProvider(AccountProvider.ICLOUD)
        val accountsWithRegional = icloudAccounts.any {
            ICloudUrlNormalizer.isRegionalUrl(it.homeSetUrl) ||
                ICloudUrlNormalizer.isRegionalUrl(it.principalUrl)
        }
        if (accountsWithRegional) {
            Log.e(TAG, "Verification failed: accounts still have regional URLs")
            return false
        }

        val icloudAccountIds = icloudAccounts.map { it.id }
        val calendarsWithRegional = icloudAccountIds.any { accountId ->
            calendarsDao.getByAccountIdOnce(accountId).any {
                ICloudUrlNormalizer.isRegionalUrl(it.caldavUrl)
            }
        }
        if (calendarsWithRegional) {
            Log.e(TAG, "Verification failed: calendars still have regional URLs")
            return false
        }

        val eventsWithRegional = eventsDao.getICloudEventUrls().any {
            ICloudUrlNormalizer.isRegionalUrl(it.caldavUrl)
        }
        if (eventsWithRegional) {
            Log.e(TAG, "Verification failed: events still have regional URLs")
            return false
        }

        val opsWithRegional = pendingOpsDao.getAllOnce().any {
            ICloudUrlNormalizer.isRegionalUrl(it.targetUrl)
        }
        if (opsWithRegional) {
            Log.e(TAG, "Verification failed: pending operations still have regional URLs")
            return false
        }

        return true
    }
}
