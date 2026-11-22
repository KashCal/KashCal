package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Returns whether the home screen shows the "Reminders are off" banner: the view model has a
 * count for it, and a calendar view is on screen (not Insights, and not search results).
 */
fun showsRemindersOffBanner(uiState: HomeUiState): Boolean =
    uiState.remindersOffBannerCount != null &&
        uiState.viewMode != ViewMode.INSIGHTS &&
        !(uiState.isSearchActive && uiState.searchQuery.isNotEmpty())
