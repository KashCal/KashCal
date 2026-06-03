package org.onekash.kashcal.domain.whatsnew

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * One release's notes shown by the What's New sheet.
 *
 * @param versionCode the BuildConfig.VERSION_CODE this entry was authored for.
 *   Used both as a stable identity for "have I seen this?" tracking and to
 *   filter out entries authored ahead of the user's installed version.
 * @param titleRes per-version section heading (e.g. "Help keep Android open").
 * @param bodyRes optional prose paragraph rendered above the bullets.
 *   Pass 0 when the section is bullet-only.
 * @param bulletsRes optional string-array resource — one <item> per bullet —
 *   so translators can't break rendering by mishandling newlines. Pass 0
 *   when the section has no bullets.
 * @param actionLabelRes optional CTA button label rendered below the
 *   content. Pass 0 to omit the button.
 * @param actionUrlRes optional string resource holding the URL the CTA
 *   button opens. Stored as a resource (not a hardcoded string) so the URL
 *   can be branded/redirected per locale if needed. Pass 0 to omit.
 */
@Immutable
data class ReleaseNote(
    val versionCode: Int,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int = 0,
    @ArrayRes val bulletsRes: Int = 0,
    @StringRes val actionLabelRes: Int = 0,
    @StringRes val actionUrlRes: Int = 0,
)
