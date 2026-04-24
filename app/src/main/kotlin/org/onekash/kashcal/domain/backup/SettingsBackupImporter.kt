package org.onekash.kashcal.domain.backup

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider

private const val ICS_ACCOUNT_EMAIL = "subscriptions"

/**
 * Reads a backup JSON file and either parses it or applies it to local state.
 *
 * Parse step is pure: validates version, structure, and size. No DB writes occur until the
 * caller explicitly invokes `applyBackup`.
 *
 * Apply step runs subscription writes inside a Room transaction, then writes preferences
 * afterwards. DataStore is a separate storage system and cannot share a Room transaction.
 * In practice DataStore writes do not fail under normal conditions; a mid-loop DataStore
 * failure leaves earlier prefs applied but all Room writes still atomic.
 */
@Singleton
class SettingsBackupImporter @Inject constructor(
    private val database: KashCalDatabase,
    private val dataStore: KashCalDataStore,
    private val accountRepository: AccountRepository,
    private val calendarRepository: CalendarRepository,
    private val icsSubscriptionsDao: IcsSubscriptionsDao,
) {

    private val parser = parser()

    fun parseAndValidate(json: String): BackupParseResult = parser.parseAndValidate(json)

    suspend fun applyBackup(envelope: BackupEnvelope): ImportResult {
        val counts = Counts()

        if (envelope.subscriptions.isNotEmpty()) {
            database.runInTransaction {
                applySubscriptions(envelope.subscriptions, counts)
            }
        }

        val preferencesApplied = applyPreferences(envelope.preferences)
        val deviceCalendarsNoteNeeded =
            envelope.preferences[PreferencesKeys.DEVICE_CALENDARS_ENABLED.name]
                .let { it is BackupPreferenceValue.BoolPref && it.value }

        return ImportResult(
            subscriptionsCreated = counts.subscriptionsCreated,
            subscriptionsUpdated = counts.subscriptionsUpdated,
            preferencesApplied = preferencesApplied,
            deviceCalendarsNoteNeeded = deviceCalendarsNoteNeeded,
        )
    }

    private suspend fun applySubscriptions(
        subscriptions: List<BackupSubscription>,
        counts: Counts,
    ) {
        if (subscriptions.isEmpty()) return

        for (backup in subscriptions) {
            val existing = icsSubscriptionsDao.getByUrl(backup.url)
            if (existing != null) {
                icsSubscriptionsDao.update(
                    existing.copy(
                        name = backup.name,
                        color = backup.color,
                        syncIntervalHours = backup.syncIntervalHours,
                        enabled = backup.enabled,
                        username = backup.username,
                    ),
                )
                counts.subscriptionsUpdated++
            } else {
                val icsAccountId = ensureIcsAccountExists()
                // Reuse a calendar row already present for this URL — may pre-exist on the
                // device. Creating a duplicate would violate the unique index on caldav_url.
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
                        syncIntervalHours = backup.syncIntervalHours,
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
                displayName = "ICS Subscriptions",
                isEnabled = true,
            ),
        )
    }

    private suspend fun applyPreferences(preferences: Map<String, BackupPreferenceValue>): Int {
        val decoded = preferences.mapNotNull { (name, value) ->
            ExportablePreferences.fromBackupValue(name, value)
        }
        if (decoded.isEmpty()) return 0
        dataStore.edit { prefs ->
            for ((key, value) in decoded) {
                @Suppress("UNCHECKED_CAST")
                prefs[key as androidx.datastore.preferences.core.Preferences.Key<Any>] = value
            }
        }
        return decoded.size
    }

    private class Counts {
        var subscriptionsCreated: Int = 0
        var subscriptionsUpdated: Int = 0
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
