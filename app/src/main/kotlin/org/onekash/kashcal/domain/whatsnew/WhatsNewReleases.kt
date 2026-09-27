package org.onekash.kashcal.domain.whatsnew

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.onekash.kashcal.R

/**
 * Every release note the What's New sheet can show.
 *
 * To announce a release, add a [ReleaseNote] for its versionCode, add its strings (and any
 * string array), and generate their translations. A release without an entry shows nothing.
 * Order doesn't matter; [WhatsNewGate] sorts by versionCode.
 */
val ALL_RELEASE_NOTES: ImmutableList<ReleaseNote> = persistentListOf(
    ReleaseNote(
        versionCode = 598,
        titleRes = R.string.whats_new_v598_title,
        bodyRes = R.string.whats_new_v598_body,
        actionLabelRes = R.string.whats_new_v598_action_label,
        actionUrlRes = R.string.whats_new_v598_action_url,
    ),
    ReleaseNote(
        versionCode = 732,
        titleRes = R.string.whats_new_v732_title,
        bodyRes = R.string.whats_new_v732_body,
        captionRes = R.string.whats_new_v732_caption,
        actionLabelRes = R.string.whats_new_v732_action_label,
        actionUrlRes = R.string.whats_new_v732_action_url,
    ),
)
