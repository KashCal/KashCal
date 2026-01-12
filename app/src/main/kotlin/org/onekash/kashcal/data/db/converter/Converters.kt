package org.onekash.kashcal.data.db.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.data.db.entity.SyncStatus

/**
 * Room TypeConverters for complex types.
 *
 * Handles conversion between Kotlin types and SQLite-compatible types.
 */
class Converters {

    private val gson = Gson()

    // ========== SyncStatus Enum ==========

    /**
     * Convert SyncStatus enum to String for storage.
     */
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String {
        return status.name
    }

    /**
     * Convert String to SyncStatus enum.
     * Falls back to SYNCED if value is invalid (defensive).
     */
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus {
        return try {
            SyncStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            SyncStatus.SYNCED
        }
    }

    // ========== ReminderStatus Enum ==========

    /**
     * Convert ReminderStatus enum to String for storage.
     */
    @TypeConverter
    fun fromReminderStatus(status: ReminderStatus): String {
        return status.name
    }

    /**
     * Convert String to ReminderStatus enum.
     * Falls back to PENDING if value is invalid (defensive).
     */
    @TypeConverter
    fun toReminderStatus(value: String): ReminderStatus {
        return try {
            ReminderStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            ReminderStatus.PENDING
        }
    }

    // ========== List<String> (for reminders, categories, etc.) ==========

    /**
     * Convert List<String> to JSON string for storage.
     */
    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.let { gson.toJson(it) }
    }

    /**
     * Convert JSON string to List<String>.
     * Returns empty list if null or malformed.
     */
    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ========== Map<String, String> (for extra_properties) ==========

    /**
     * Convert Map<String, String> to JSON string for storage.
     * Used for preserving unknown iCal properties (X-APPLE-*, etc.)
     */
    @TypeConverter
    fun fromStringMap(map: Map<String, String>?): String? {
        return map?.let { gson.toJson(it) }
    }

    /**
     * Convert JSON string to Map<String, String>.
     * Returns empty map if null or malformed.
     */
    @TypeConverter
    fun toStringMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(value, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
