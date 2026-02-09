package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.shared.maskEmail

/**
 * UI model for the account detail bottom sheet.
 *
 * Provides a display-ready view of an account with masked email,
 * sync status, and calendar count. Same layout for all providers.
 */
data class AccountDetailUiModel(
    val accountId: Long,
    val provider: AccountProvider,
    val displayName: String,
    val email: String,
    val principalUrl: String?,
    val calendarCount: Int,
    val isEnabled: Boolean,
    val lastSuccessfulSyncAt: Long?,
    val consecutiveSyncFailures: Int
)

/**
 * Sync status for the account detail sheet's Sync Now action.
 */
sealed class AccountDetailSyncStatus {
    data object Idle : AccountDetailSyncStatus()
    data object Syncing : AccountDetailSyncStatus()
    data class Done(val success: Boolean) : AccountDetailSyncStatus()
}

/**
 * Discovery status for the account detail sheet's Discover Calendars action.
 */
sealed class AccountDetailDiscoverStatus {
    data object Idle : AccountDetailDiscoverStatus()
    data object Discovering : AccountDetailDiscoverStatus()
    data class Done(val newCount: Int, val totalCount: Int) : AccountDetailDiscoverStatus()
    data class Error(val message: String) : AccountDetailDiscoverStatus()
}

/**
 * Map an Account entity to AccountDetailUiModel for display.
 *
 * @param calendarCount Number of calendars for this account
 * @return Display-ready UI model with masked email and fallback display name
 */
fun Account.toDetailUiModel(calendarCount: Int): AccountDetailUiModel {
    return AccountDetailUiModel(
        accountId = id,
        provider = provider,
        displayName = displayName ?: provider.displayName,
        email = maskEmail(email),
        principalUrl = principalUrl,
        calendarCount = calendarCount,
        isEnabled = isEnabled,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        consecutiveSyncFailures = consecutiveSyncFailures
    )
}
