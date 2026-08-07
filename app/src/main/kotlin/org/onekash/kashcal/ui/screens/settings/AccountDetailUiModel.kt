package org.onekash.kashcal.ui.screens.settings

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.shared.maskEmail

/**
 * UI model for the account detail bottom sheet.
 *
 * Provides a display-ready view of an account with masked email,
 * sync status, and calendar count. Same layout for all providers.
 */
@Immutable
data class AccountDetailUiModel(
    val accountId: Long,
    val provider: AccountProvider,
    val displayName: String,
    val email: String,
    val principalUrl: String?,
    val calendarCount: Int,
    val isEnabled: Boolean,
    val contactSyncEnabled: Boolean,
    val contactCount: Int,
    val lastSuccessfulSyncAt: Long?,
    val consecutiveSyncFailures: Int
)

/**
 * Sync status for the account detail sheet's Sync Now action.
 */
@Immutable
sealed class AccountDetailSyncStatus {
    data object Idle : AccountDetailSyncStatus()
    data object Syncing : AccountDetailSyncStatus()
    data class Done(val success: Boolean) : AccountDetailSyncStatus()
}

/**
 * A short-lived inline confirmation shown inside the account detail sheet after a
 * contact-sync toggle, carrying its [message] and the [tone] that should style it.
 *
 * Message and tone travel together so the sheet can't drift into rendering a
 * destructive outcome ("Device contacts removed") with the same celebratory glyph
 * as a benign one ("Syncing contacts"). The tone is decided where the outcome is
 * known (the ViewModel), not re-derived from the message text downstream.
 */
@Immutable
data class ContactSyncConfirmation(
    val message: String,
    val tone: Tone,
) {
    enum class Tone {
        /** A benign result — sync enabled, or contacts kept by a sibling. */
        POSITIVE,

        /** A destructive or unverified result — contacts removed, or may remain. */
        WARNING,
    }
}

/**
 * Discovery status for the account detail sheet's Discover Calendars action.
 */
@Immutable
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
 * @param contactCount Number of synced address-book contacts for this account
 *   (0 when contact sync is off or nothing has synced yet)
 * @return Display-ready UI model with masked email and fallback display name
 */
fun Account.toDetailUiModel(calendarCount: Int, contactCount: Int = 0): AccountDetailUiModel {
    return AccountDetailUiModel(
        accountId = id,
        provider = provider,
        displayName = displayName ?: provider.displayName,
        email = maskEmail(email),
        principalUrl = principalUrl,
        calendarCount = calendarCount,
        isEnabled = isEnabled,
        contactSyncEnabled = contactSyncEnabled,
        contactCount = contactCount,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        consecutiveSyncFailures = consecutiveSyncFailures
    )
}
