package org.onekash.kashcal.ui.screens.settings

import androidx.compose.runtime.Immutable

/**
 * What the Calendar Feeds "Add from account" sheet shows. The ViewModel holds null while the
 * sheet is closed; [sections] is empty when no account that can list feeds is left.
 */
@Immutable
data class AccountFeedsSheetState(val sections: List<AccountFeedsSection>)

/** One account's section: its header and what its feed listing produced so far. */
@Immutable
data class AccountFeedsSection(
    val accountId: Long,
    val displayName: String,
    val maskedEmail: String,
    val content: AccountFeedsContent,
)

/** The state of one account's feed listing. */
@Immutable
sealed interface AccountFeedsContent {
    /** The listing is running. */
    data object Checking : AccountFeedsContent

    /** The server listed at least one usable feed. */
    data class Feeds(val rows: List<AccountFeedRow>) : AccountFeedsContent

    /** The server answered with no usable feed. */
    data object NoFeeds : AccountFeedsContent

    /** The listing failed or took too long; Retry lists again. */
    data object Failed : AccountFeedsContent
}

/**
 * One feed in a section.
 *
 * @property url the cleaned feed URL, which is also the switch's key.
 * @property color ARGB: the server's color, or a palette color picked once per listing.
 * @property isOn whether a Calendar Feeds entry has this URL.
 * @property isBusy whether an add or removal for this URL is running; the switch is disabled.
 */
@Immutable
data class AccountFeedRow(
    val url: String,
    val name: String,
    val host: String,
    val color: Int,
    val isOn: Boolean = false,
    val isBusy: Boolean = false,
)
