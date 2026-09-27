package org.onekash.kashcal.data.db.converter

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider

/** Converts enums, lists and maps to the TEXT columns Room stores them in. */
class Converters {

    // ========== SyncStatus Enum ==========

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String {
        return status.name
    }

    /** Falls back to SYNCED for an unknown value. */
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus {
        return try {
            SyncStatus.valueOf(value)
        } catch (_: IllegalArgumentException) {
            SyncStatus.SYNCED
        }
    }

    // ========== ReminderStatus Enum ==========

    @TypeConverter
    fun fromReminderStatus(status: ReminderStatus): String {
        return status.name
    }

    /** Falls back to PENDING for an unknown value. */
    @TypeConverter
    fun toReminderStatus(value: String): ReminderStatus {
        return try {
            ReminderStatus.valueOf(value)
        } catch (_: IllegalArgumentException) {
            ReminderStatus.PENDING
        }
    }

    // ========== AccountProvider Enum ==========

    /** Stores the lowercase name ("icloud", "local"), the form the database uses. */
    @TypeConverter
    fun fromAccountProvider(provider: AccountProvider): String {
        return provider.name.lowercase()
    }

    /** Throws IllegalArgumentException for an unknown value, failing fast on corrupt data. */
    @TypeConverter
    fun toAccountProvider(value: String): AccountProvider {
        return AccountProvider.fromString(value)
    }

    // ========== List<String> as a JSON array (for example reminders, categories) ==========

    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.let { Json.encodeToString(it) }
    }

    /** Returns an empty list for null, blank or malformed JSON. */
    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            Json.decodeFromString<List<String>>(value)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ========== Map<String, String> as a JSON object (extra_properties) ==========
    // extra_properties holds unknown iCal properties (X-APPLE-* and the like) and the app's
    // X-KASHCAL- markers ([org.onekash.kashcal.data.db.entity.Event.extraProperties]).

    @TypeConverter
    fun fromStringMap(map: Map<String, String>?): String? {
        return map?.let { Json.encodeToString(it) }
    }

    /** Returns an empty map for null, blank or malformed JSON. */
    @TypeConverter
    fun toStringMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(value)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
