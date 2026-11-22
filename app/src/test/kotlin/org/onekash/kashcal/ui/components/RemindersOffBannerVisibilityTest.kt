package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode

/** Tests [showsRemindersOffBanner]: where the banner appears once the view model wants it. */
class RemindersOffBannerVisibilityTest {

    private val shown = HomeUiState(remindersOffBannerCount = 3)

    @Test
    fun `hidden when the view model has no count`() =
        assertFalse(showsRemindersOffBanner(HomeUiState(remindersOffBannerCount = null)))

    @Test
    fun `shown on the calendar views`() {
        ViewMode.entries.filter { it != ViewMode.INSIGHTS }.forEach { mode ->
            assertTrue("$mode", showsRemindersOffBanner(shown.copy(viewMode = mode)))
        }
    }

    @Test
    fun `hidden on Insights`() =
        assertFalse(showsRemindersOffBanner(shown.copy(viewMode = ViewMode.INSIGHTS)))

    @Test
    fun `hidden while search shows results`() =
        assertFalse(showsRemindersOffBanner(shown.copy(isSearchActive = true, searchQuery = "dentist")))

    @Test
    fun `shown while search is open with nothing typed`() =
        assertTrue(showsRemindersOffBanner(shown.copy(isSearchActive = true, searchQuery = "")))
}
