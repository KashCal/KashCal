package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores a sync log row for debugging.
 *
 * The sync engine writes one row per calendar sync, with action "SYNC_COMPLETE" and result
 * "SUCCESS" or "ERROR". No screen shows the rows: `AccountSettingsViewModel.loadSyncLogs`,
 * which reads them through [org.onekash.kashcal.domain.reader.SyncLogReader], has no caller.
 * The sync worker deletes rows past their retention.
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

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /** Calendar synced, or null for an account-level row. */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long? = null,

    /** Event UID, or null for a calendar-level row. */
    @ColumnInfo(name = "event_uid")
    val eventUid: String? = null,

    /** Sync action: the ACTION_ constants, or the engine's "SYNC_COMPLETE". */
    @ColumnInfo(name = "action")
    val action: String,

    /** Outcome: the RESULT_ constants, or the engine's "ERROR". */
    @ColumnInfo(name = "result")
    val result: String,

    /** Details or error message. */
    @ColumnInfo(name = "details")
    val details: String? = null,

    /** HTTP status code, when there is one. */
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

        /** Returns a [RESULT_SUCCESS] row. */
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

        /** Returns a row with the error [result]. */
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
