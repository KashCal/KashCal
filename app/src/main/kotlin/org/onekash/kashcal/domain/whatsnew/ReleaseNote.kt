package org.onekash.kashcal.domain.whatsnew

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * One release's section in the What's New sheet. Every resource field except [titleRes] is
 * optional; 0 omits it.
 *
 * @param versionCode the BuildConfig.VERSION_CODE the entry announces. [WhatsNewGate] shows it
 *   once the user runs that version and hasn't seen it yet.
 * @param titleRes section heading, e.g. "Help keep Android open".
 * @param bodyRes paragraph shown above the bullets.
 * @param bulletsRes string array with one item per bullet, so translators can't break the
 *   layout by mishandling newlines.
 * @param captionRes small caption just above the action button, e.g. "Not a supporter yet?".
 * @param actionLabelRes label of the action button below the content.
 * @param actionUrlRes string resource holding the URL the button opens, so the URL can differ
 *   per locale. The button shows only when both this and [actionLabelRes] are set.
 */
@Immutable
data class ReleaseNote(
    val versionCode: Int,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int = 0,
    @ArrayRes val bulletsRes: Int = 0,
    @StringRes val captionRes: Int = 0,
    @StringRes val actionLabelRes: Int = 0,
    @StringRes val actionUrlRes: Int = 0,
)
