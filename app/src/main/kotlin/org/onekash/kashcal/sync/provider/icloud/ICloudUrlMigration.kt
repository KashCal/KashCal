package org.onekash.kashcal.sync.provider.icloud

import android.util.Log
import androidx.room.withTransaction
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time migration to normalize iCloud URLs from regional to canonical form.
 *
 * iCloud uses regional servers (p180-caldav.icloud.com) that can become unreachable
 * when Apple rotates server assignments. By normalizing all URLs to canonical form
 * (caldav.icloud.com), server rotation becomes transparent.
 *
 * Migration scope:
 * - Account homeSetUrl, principalUrl
 * - Calendar caldavUrl
 * - Event caldavUrl
 * - PendingOperation targetUrl
 *
 * The migration is:
 * - Idempotent: Safe to run multiple times (checks completion flag)
 * - Atomic: Uses database transaction (all or nothing)
 * - Verified: Confirms no regional URLs remain after migration
 *
 * Called at sync startup in [CalDavSyncWorker].
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
     * Run migration if not already completed.
     *
     * @return true if migration was performed, false if already completed
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

        // Transaction ensures atomicity: verification runs after commit
        // is safe due to idempotency (re-runs skip already-migrated URLs)
        database.withTransaction {
            accountCount = migrateAccounts()
            calendarCount = migrateCalendars()
            eventCount = migrateEvents()
            operationCount = migratePendingOperations()
        }

        // Verify migration completed successfully
        if (!verifyNoRegionalUrls()) {
            Log.e(TAG, "Migration verification failed - some regional URLs remain")
            // Don't mark as completed so it runs again
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

    /**
     * Migrate iCloud account URLs to canonical form.
     */
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

    /**
     * Migrate iCloud calendar URLs to canonical form.
     */
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

    /**
     * Migrate iCloud event URLs to canonical form.
     */
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

    /**
     * Migrate pending operation target URLs to canonical form.
     */
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

    /**
     * Verify no regional URLs remain after migration.
     *
     * Checks all entity types: accounts, calendars, events, pending operations.
     */
    private suspend fun verifyNoRegionalUrls(): Boolean {
        val accountsDao = database.accountsDao()
        val calendarsDao = database.calendarsDao()
        val eventsDao = database.eventsDao()
        val pendingOpsDao = database.pendingOperationsDao()

        // Check accounts
        val icloudAccounts = accountsDao.getByProvider(AccountProvider.ICLOUD)
        val accountsWithRegional = icloudAccounts.any {
            ICloudUrlNormalizer.isRegionalUrl(it.homeSetUrl) ||
                ICloudUrlNormalizer.isRegionalUrl(it.principalUrl)
        }
        if (accountsWithRegional) {
            Log.e(TAG, "Verification failed: accounts still have regional URLs")
            return false
        }

        // Check calendars
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

        // Check events
        val eventsWithRegional = eventsDao.getICloudEventUrls().any {
            ICloudUrlNormalizer.isRegionalUrl(it.caldavUrl)
        }
        if (eventsWithRegional) {
            Log.e(TAG, "Verification failed: events still have regional URLs")
            return false
        }

        // Check pending operations
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
