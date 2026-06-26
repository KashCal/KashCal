package org.onekash.kashcal.domain.whatsnew

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.onekash.kashcal.R

/**
 * Authored release notes shown by the What's New sheet.
 *
 * To announce a release: append a [ReleaseNote] for that release's
 * versionCode here, add the matching string + (optional) string-array
 * resources, and generate the translations for them. Releases without an
 * entry stay silent.
 *
 * Entries can be in any order; [WhatsNewGate] sorts by versionCode.
 */
val ALL_RELEASE_NOTES: ImmutableList<ReleaseNote> = persistentListOf(
    ReleaseNote(
        versionCode = 598,
        titleRes = R.string.whats_new_v598_title,
        bodyRes = R.string.whats_new_v598_body,
        actionLabelRes = R.string.whats_new_v598_action_label,
        actionUrlRes = R.string.whats_new_v598_action_url,
    ),
)
