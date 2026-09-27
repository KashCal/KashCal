package org.onekash.kashcal.domain.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CategoryDao
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.scheduler.IcsRefreshScheduleReconciler
import javax.inject.Inject
import javax.inject.Singleton

private const val ICS_ACCOUNT_EMAIL = "subscriptions"

/**
 * Parses a backup JSON file, or applies a parsed one to local state.
 *
 * [parseAndValidate] checks size, structure and version and writes nothing; nothing is written
 * until the caller invokes [applyBackup].
 *
 * [applyBackup] writes subscriptions and tags in one Room transaction, then all preferences in
 * one DataStore edit. DataStore can't share a Room transaction, so a failed preference write
 * leaves the committed Room writes in place with no preference from the bundle applied.
 */
@Singleton
class SettingsBackupImporter @Inject constructor(
    private val database: KashCalDatabase,
    private val dataStore: KashCalDataStore,
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository,
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val categoryDao: CategoryDao,
    private val icsRefreshScheduleReconciler: IcsRefreshScheduleReconciler,
    @ApplicationContext private val context: Context,
) {

    private val parser = parser()

    fun parseAndValidate(json: String): BackupParseResult = parser.parseAndValidate(json)

    suspend fun applyBackup(envelope: BackupEnvelope): ImportResult {
        val counts = Counts()

        if (envelope.subscriptions.isNotEmpty() || envelope.categories.isNotEmpty()) {
            database.runInTransaction {
                applySubscriptions(envelope.subscriptions, counts)
                applyCategories(envelope.categories, counts)
            }
        }

        // Restored feeds need the periodic refresh job armed, or a restore to a new device
        // leaves feeds that never refresh. Kept outside the transaction: it reads the feeds
        // back and talks to WorkManager.
        if (envelope.subscriptions.isNotEmpty()) {
            icsRefreshScheduleReconciler.reconcile()
        }

        val preferencesApplied = applyPreferences(envelope.preferences)
        val deviceCalendarsNoteNeeded =
            envelope.preferences[PreferencesKeys.DEVICE_CALENDARS_ENABLED.name]
                .let { it is BackupPreferenceValue.BoolPref && it.value }

        return ImportResult(
            subscriptionsCreated = counts.subscriptionsCreated,
            subscriptionsUpdated = counts.subscriptionsUpdated,
            categoriesRestored = counts.categoriesRestored,
            preferencesApplied = preferencesApplied,
            deviceCalendarsNoteNeeded = deviceCalendarsNoteNeeded,
        )
    }

    private suspend fun applyCategories(categories: List<BackupCategory>, counts: Counts) {
        for (backup in categories) {
            categoryDao.restoreFromBackup(backup.name, backup.color, backup.lastUsedAt)
            counts.categoriesRestored++
        }
    }

    private suspend fun applySubscriptions(
        subscriptions: List<BackupSubscription>,
        counts: Counts,
    ) {
        if (subscriptions.isEmpty()) return

        for (backup in subscriptions) {
            // A backup file is untrusted input. isDueForSync floors the interval too, so
            // refresh is safe either way; flooring here also keeps a 0 or negative interval
            // out of storage.
            val syncIntervalHours = backup.syncIntervalHours
                .coerceAtLeast(IcsSubscription.MIN_SYNC_INTERVAL_HOURS)
            val existing = icsSubscriptionsDao.getByUrl(backup.url)
            if (existing != null) {
                icsSubscriptionsDao.update(
                    existing.copy(
                        name = backup.name,
                        color = backup.color,
                        syncIntervalHours = syncIntervalHours,
                        enabled = backup.enabled,
                        username = backup.username,
                    ),
                )
                counts.subscriptionsUpdated++
            } else {
                val icsAccountId = ensureIcsAccountExists()
                // Reuse a calendar row already present for this URL: a duplicate would violate
                // the unique index on caldav_url.
                val calendarId = calendarRepository.getCalendarByUrl(backup.url)?.id
                    ?: calendarRepository.createCalendar(
                        Calendar(
                            accountId = icsAccountId,
                            caldavUrl = backup.url,
                            displayName = backup.name,
                            color = backup.color,
                            isReadOnly = true,
                            isVisible = true,
                            isDefault = false,
                        ),
                    )
                icsSubscriptionsDao.insert(
                    IcsSubscription(
                        url = backup.url,
                        name = backup.name,
                        color = backup.color,
                        calendarId = calendarId,
                        syncIntervalHours = syncIntervalHours,
                        enabled = backup.enabled,
                        username = backup.username,
                    ),
                )
                counts.subscriptionsCreated++
            }
        }
    }

    private suspend fun ensureIcsAccountExists(): Long {
        val existing = accountRepository.getAccountByProviderAndEmail(
            AccountProvider.ICS,
            ICS_ACCOUNT_EMAIL,
        )
        if (existing != null) return existing.id
        return accountRepository.createAccount(
            Account(
                provider = AccountProvider.ICS,
                email = ICS_ACCOUNT_EMAIL,
                displayName = context.getString(R.string.subscriptions_title),
                isEnabled = true,
            ),
        )
    }

    private suspend fun applyPreferences(preferences: Map<String, BackupPreferenceValue>): Int {
        val decoded = preferences.mapNotNull { (name, value) ->
            ExportablePreferences.fromBackupValue(name, value)
        }
        if (decoded.isEmpty()) return 0

        // Sanitize share-availability values before writing, so a malformed backup can't
        // store values that would crash the sheet on first open. The window must be at least
        // 60 minutes, so both endpoints are resolved together.
        val byName = decoded.associate { it.first.name to it.second }
        val rawStart = (byName[PreferencesKeys.SHARE_AVAILABILITY_WORK_START_MIN.name] as? Int)
            ?: KashCalDataStore.SHARE_AVAILABILITY_DEFAULT_WORK_START_MIN
        val rawEnd = (byName[PreferencesKeys.SHARE_AVAILABILITY_WORK_END_MIN.name] as? Int)
            ?: KashCalDataStore.SHARE_AVAILABILITY_DEFAULT_WORK_END_MIN
        val safeStart = KashCalDataStore.sanitizeWorkStartMin(rawStart, rawEnd)
        val safeEnd = KashCalDataStore.sanitizeWorkEndMin(rawEnd, safeStart)

        val sanitized = decoded.map { (key, value) ->
            val coerced: Any = when (key.name) {
                PreferencesKeys.SHARE_AVAILABILITY_DAYS.name ->
                    KashCalDataStore.sanitizeShareAvailabilityDays(value as Int)
                PreferencesKeys.SHARE_AVAILABILITY_WORK_START_MIN.name -> safeStart
                PreferencesKeys.SHARE_AVAILABILITY_WORK_END_MIN.name -> safeEnd
                else -> value
            }
            key to coerced
        }

        dataStore.edit { prefs ->
            for ((key, value) in sanitized) {
                @Suppress("UNCHECKED_CAST")
                prefs[key as androidx.datastore.preferences.core.Preferences.Key<Any>] = value
            }
        }
        return sanitized.size
    }

    private class Counts {
        var subscriptionsCreated: Int = 0
        var subscriptionsUpdated: Int = 0
        var categoriesRestored: Int = 0
    }

    companion object {
        fun parser(): Parser = Parser()
    }

    class Parser internal constructor() {

        fun parseAndValidate(json: String): BackupParseResult {
            if (json.length.toLong() > MAX_BACKUP_FILE_BYTES) {
                return BackupParseResult.Error(
                    BackupImportError.MalformedJson("file size exceeds cap of $MAX_BACKUP_FILE_BYTES bytes"),
                )
            }
            val envelope: BackupEnvelope = try {
                BackupJson.decodeFromString(BackupEnvelope.serializer(), json)
            } catch (e: SerializationException) {
                return BackupParseResult.Error(BackupImportError.MalformedJson(e.message))
            } catch (e: IllegalArgumentException) {
                return BackupParseResult.Error(BackupImportError.InvalidValue(e.message))
            }
            if (envelope.fileFormatVersion > BACKUP_FILE_FORMAT_VERSION) {
                return BackupParseResult.Error(
                    BackupImportError.VersionTooNew(
                        foundVersion = envelope.fileFormatVersion,
                        supportedVersion = BACKUP_FILE_FORMAT_VERSION,
                    ),
                )
            }
            return BackupParseResult.Ok(envelope)
        }
    }
}
