package org.onekash.kashcal.domain.backup

/**
 * Counts of entities applied during a restore, plus a flag for whether the post-restore UI
 * should remind the user to re-select device calendars.
 */
data class ImportResult(
    val subscriptionsCreated: Int,
    val subscriptionsUpdated: Int,
    val preferencesApplied: Int,
    val deviceCalendarsNoteNeeded: Boolean,
)

/**
 * Pre-apply summary shown in the confirmation dialog. Derived entirely from a parsed envelope.
 */
data class BackupSummary(
    val appVersion: String,
    val exportedAt: String,
    val subscriptions: Int,
)

fun BackupEnvelope.toSummary(): BackupSummary = BackupSummary(
    appVersion = appVersion,
    exportedAt = exportedAt,
    subscriptions = subscriptions.size,
)

sealed class BackupImportError {
    data class VersionTooNew(val foundVersion: Int, val supportedVersion: Int) : BackupImportError()
    data class MalformedJson(val detail: String? = null) : BackupImportError()
    data class InvalidValue(val detail: String? = null) : BackupImportError()
    data class ApplyFailed(val detail: String? = null) : BackupImportError()
}

sealed class BackupParseResult {
    data class Ok(val envelope: BackupEnvelope) : BackupParseResult()
    data class Error(val error: BackupImportError) : BackupParseResult()
}
