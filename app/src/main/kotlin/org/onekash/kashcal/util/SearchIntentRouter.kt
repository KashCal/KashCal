package org.onekash.kashcal.util

import android.content.Context
import android.content.Intent
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.ui.viewmodels.PendingAction
import org.onekash.kashcal.widget.ACTION_OPEN_SEARCH
import org.onekash.kashcal.widget.EXTRA_ACTION

/**
 * Reads the intents that open search: the Search app shortcut (`EXTRA_ACTION` set to
 * `ACTION_OPEN_SEARCH`) and, with [EXTRA_TAG], a search on one tag (the Tags screen's "Show
 * events"), which [tagSearchIntent] builds.
 */
object SearchIntentRouter {

    /** The tag a search opens on; read only beside `ACTION_OPEN_SEARCH`. */
    const val EXTRA_TAG = "search_tag"

    /** Longest tag text read from an intent; a valid tag is far shorter. */
    const val MAX_TAG_LENGTH = 200

    /**
     * Maps an open-search intent to [PendingAction.OpenTagSearch] when it names a non-blank tag
     * (cut to [MAX_TAG_LENGTH]), else [PendingAction.OpenSearch]; any other intent to null.
     */
    fun route(intent: Intent?): PendingAction? {
        if (intent?.getStringExtra(EXTRA_ACTION) != ACTION_OPEN_SEARCH) return null
        val tag = intent.getStringExtra(EXTRA_TAG)?.take(MAX_TAG_LENGTH)?.takeIf { it.isNotBlank() }
        return if (tag != null) PendingAction.OpenTagSearch(tag) else PendingAction.OpenSearch
    }

    /**
     * Removes the open-search extras after a tag search was handled, so a recreation (a theme
     * or language change re-reads the intent) routes it to nothing and keeps the search shown.
     */
    fun consume(intent: Intent) {
        intent.removeExtra(EXTRA_TAG)
        intent.removeExtra(EXTRA_ACTION)
    }

    /** Builds the explicit intent that brings [MainActivity] back with search open on [tag]. */
    fun tagSearchIntent(context: Context, tag: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ACTION, ACTION_OPEN_SEARCH)
            .putExtra(EXTRA_TAG, tag)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
