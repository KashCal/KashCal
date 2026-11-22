package org.onekash.kashcal.util

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.ui.viewmodels.PendingAction
import org.onekash.kashcal.widget.ACTION_GO_TO_TODAY
import org.onekash.kashcal.widget.ACTION_OPEN_SEARCH
import org.onekash.kashcal.widget.EXTRA_ACTION
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SearchIntentRouterTest {

    private fun openSearch(tag: String? = null) = Intent().putExtra(EXTRA_ACTION, ACTION_OPEN_SEARCH).apply {
        if (tag != null) putExtra(SearchIntentRouter.EXTRA_TAG, tag)
    }

    @Test
    fun `the shortcut opens plain search`() {
        assertEquals(PendingAction.OpenSearch, SearchIntentRouter.route(openSearch()))
    }

    @Test
    fun `a tag opens a search on it`() {
        assertEquals(PendingAction.OpenTagSearch("Team meeting"), SearchIntentRouter.route(openSearch("Team meeting")))
    }

    @Test
    fun `a blank tag opens plain search`() {
        assertEquals(PendingAction.OpenSearch, SearchIntentRouter.route(openSearch("  ")))
    }

    @Test
    fun `a long tag is cut to the limit`() {
        val action = SearchIntentRouter.route(openSearch("a".repeat(500))) as PendingAction.OpenTagSearch
        assertEquals(SearchIntentRouter.MAX_TAG_LENGTH, action.name.length)
    }

    @Test
    fun `a tag beside another action or with no action is not read`() {
        assertNull(SearchIntentRouter.route(Intent().putExtra(SearchIntentRouter.EXTRA_TAG, "Work")))
        assertNull(
            SearchIntentRouter.route(
                Intent().putExtra(EXTRA_ACTION, ACTION_GO_TO_TODAY).putExtra(SearchIntentRouter.EXTRA_TAG, "Work")
            )
        )
        assertNull(SearchIntentRouter.route(null))
    }

    @Test
    fun `a handled tag search routes to nothing afterwards`() {
        val intent = openSearch("Work")
        SearchIntentRouter.route(intent)

        SearchIntentRouter.consume(intent)

        assertNull(SearchIntentRouter.route(intent))
    }

    @Test
    fun `the built intent targets MainActivity and reads back as the tag search`() {
        val intent = SearchIntentRouter.tagSearchIntent(ApplicationProvider.getApplicationContext(), "Work")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
        assertEquals(PendingAction.OpenTagSearch("Work"), SearchIntentRouter.route(intent))
    }
}
