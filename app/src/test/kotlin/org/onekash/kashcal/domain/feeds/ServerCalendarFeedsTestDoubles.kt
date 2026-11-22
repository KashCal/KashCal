package org.onekash.kashcal.domain.feeds

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * A strict [ServerCalendarFeeds] with no feed account, for tests that build the settings
 * ViewModel but never open the "Add from account" sheet. Any other call throws.
 */
fun noFeedAccountsServerCalendarFeeds(): ServerCalendarFeeds = mockk {
    every { observeFeedAccounts() } returns flowOf(emptyList())
}
