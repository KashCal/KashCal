package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync operation log for debugging and diagnostics.
 *
 * Records all sync operations with results for troubleshooting.
 * Old logs are periodically cleaned up to manage database size.
 */
@Entity(
    tableName = "sync_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["calendar_id"]),
        Index(value = ["event_uid"])
    ]
)
data class SyncLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * When the sync operation occurred.
     */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Calendar involved in sync (nullable for account-level ops).
     */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long? = null,

    /**
     * Event UID involved (nullable for calendar-level ops).
     */
    @ColumnInfo(name = "event_uid")
    val eventUid: String? = null,

    /**
     * Type of sync action performed.
     * Values: "PULL", "PUSH_CREATE", "PUSH_UPDATE", "PUSH_DELETE", "CONFLICT", "DISCOVERY"
     */
    @ColumnInfo(name = "action")
    val action: String,

    /**
     * Result of the sync operation.
     * Values: "SUCCESS", "ERROR_412" (conflict), "ERROR_NETWORK", "ERROR_PARSE", "ERROR_AUTH", "SKIPPED"
     */
    @ColumnInfo(name = "result")
    val result: String,

    /**
     * Additional details or error message.
     */
    @ColumnInfo(name = "details")
    val details: String? = null,

    /**
     * HTTP status code if applicable.
     */
    @ColumnInfo(name = "http_status")
    val httpStatus: Int? = null
) {
    companion object {
        // Actions
        const val ACTION_PULL = "PULL"
        const val ACTION_PUSH_CREATE = "PUSH_CREATE"
        const val ACTION_PUSH_UPDATE = "PUSH_UPDATE"
        const val ACTION_PUSH_DELETE = "PUSH_DELETE"
        const val ACTION_CONFLICT = "CONFLICT"
        const val ACTION_DISCOVERY = "DISCOVERY"

        // Results
        const val RESULT_SUCCESS = "SUCCESS"
        const val RESULT_ERROR_412 = "ERROR_412"  // HTTP 412 Precondition Failed (ETag mismatch)
        const val RESULT_ERROR_NETWORK = "ERROR_NETWORK"
        const val RESULT_ERROR_PARSE = "ERROR_PARSE"
        const val RESULT_ERROR_AUTH = "ERROR_AUTH"
        const val RESULT_SKIPPED = "SKIPPED"

        /**
         * Create a success log entry.
         */
        fun success(
            action: String,
            calendarId: Long? = null,
            eventUid: String? = null,
            details: String? = null,
            httpStatus: Int? = 200
        ) = SyncLog(
            action = action,
            result = RESULT_SUCCESS,
            calendarId = calendarId,
            eventUid = eventUid,
            details = details,
            httpStatus = httpStatus
        )

        /**
         * Create an error log entry.
         */
        fun error(
            action: String,
            result: String,
            calendarId: Long? = null,
            eventUid: String? = null,
            details: String? = null,
            httpStatus: Int? = null
        ) = SyncLog(
            action = action,
            result = result,
            calendarId = calendarId,
            eventUid = eventUid,
            details = details,
            httpStatus = httpStatus
        )
    }
}
