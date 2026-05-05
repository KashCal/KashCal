package org.onekash.kashcal.domain.backup

import kotlinx.coroutines.flow.first
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.preferences.KashCalDataStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces a KashCal settings backup JSON.
 *
 * The envelope carries only items that can't be recovered elsewhere: exportable preferences
 * and ICS subscription URLs (plus their user-chosen metadata). CalDAV/iCloud/LOCAL accounts
 * and calendars are rebuilt on restore via first-launch initialization and server sync, so
 * they are deliberately not part of the file.
 */
@Singleton
class SettingsBackupExporter(
    private val dataStore: KashCalDataStore,
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
    private val appVersionProvider: () -> String = { BuildConfig.VERSION_NAME },
    private val nowProvider: () -> Instant = { Instant.now() },
) {
    @Inject
    constructor(
        dataStore: KashCalDataStore,
        icsSubscriptionsDao: IcsSubscriptionsDao,
    ) : this(dataStore, icsSubscriptionsDao, { BuildConfig.VERSION_NAME }, { Instant.now() })


    suspend fun exportSettings(): String {
        val preferences = collectPreferences()
        val subscriptionDtos = icsSubscriptionsDao.getAllOnce().map { it.toBackupSubscription() }

        val envelope = BackupEnvelope(
            fileFormatVersion = BACKUP_FILE_FORMAT_VERSION,
            appVersion = appVersionProvider(),
            exportedAt = BackupFilename.generateIsoUtc(nowProvider()),
            preferences = preferences,
            subscriptions = subscriptionDtos,
        )
        return BackupJson.encodeToString(BackupEnvelope.serializer(), envelope)
    }

    private suspend fun collectPreferences(): Map<String, BackupPreferenceValue> {
        val prefs = dataStore.dataStore.data.first()
        val result = LinkedHashMap<String, BackupPreferenceValue>()
        for (key in ExportablePreferences.KEYS) {
            val raw = prefs[key] ?: continue
            val value = ExportablePreferences.toBackupValue(key, raw) ?: continue
            result[key.name] = value
        }
        return result
    }

    private fun org.onekash.kashcal.data.db.entity.IcsSubscription.toBackupSubscription(): BackupSubscription =
        BackupSubscription(
            url = url,
            name = name,
            color = color,
            syncIntervalHours = syncIntervalHours,
            enabled = enabled,
            username = username,
        )
}
