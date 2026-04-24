package org.onekash.kashcal.ui.screens

import org.onekash.kashcal.domain.backup.BackupEnvelope
import org.onekash.kashcal.domain.backup.BackupImportError
import org.onekash.kashcal.domain.backup.BackupSummary
import org.onekash.kashcal.domain.backup.ImportResult

sealed class BackupRestoreUiState {
    data object Idle : BackupRestoreUiState()

    data class PendingConfirmation(
        val envelope: BackupEnvelope,
        val summary: BackupSummary,
    ) : BackupRestoreUiState()

    data class Error(val error: BackupImportError) : BackupRestoreUiState()

    data class Success(val result: ImportResult) : BackupRestoreUiState()
}
