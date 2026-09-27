package org.onekash.kashcal.ui.screens.settings

import org.onekash.kashcal.ui.util.UiMessage
import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.shared.maskEmail

/** Holds a display-ready account for [AccountDetailSheet]; built by [toDetailUiModel]. */
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

/** Tracks the account detail sheet's Sync Now action. */
@Immutable
sealed class AccountDetailSyncStatus {
    data object Idle : AccountDetailSyncStatus()
    data object Syncing : AccountDetailSyncStatus()
    data class Done(val success: Boolean) : AccountDetailSyncStatus()
}

/**
 * Carries the short-lived confirmation the account detail sheet shows after a contact sync
 * toggle: its [message] and the [tone] that styles it.
 *
 * Message and tone travel together so the sheet can't render a destructive outcome ("Device
 * contacts removed") with the same checkmark as a benign one ("Syncing contacts"). The
 * ViewModel sets the tone where the outcome is known; it is never re-derived from the text.
 */
@Immutable
data class ContactSyncConfirmation(
    val message: String,
    val tone: Tone,
) {
    enum class Tone {
        /** A benign result: sync enabled, or contacts kept by a sibling login. */
        POSITIVE,

        /** A destructive or unverified result: contacts removed, or some may remain. */
        WARNING,
    }
}

/** Tracks the account detail sheet's Discover Calendars action. */
@Immutable
sealed class AccountDetailDiscoverStatus {
    data object Idle : AccountDetailDiscoverStatus()
    data object Discovering : AccountDetailDiscoverStatus()
    data class Done(val newCount: Int, val totalCount: Int) : AccountDetailDiscoverStatus()
    data class Error(val message: UiMessage) : AccountDetailDiscoverStatus()
}

/**
 * Maps an account to its detail UI model, masking the email and falling back to the
 * provider's display name when the account has none.
 *
 * @param contactCount synced address-book contacts for this account, 0 when contact sync is
 *   off or nothing has synced yet
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
