package org.onekash.kashcal.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.ZoneId

/**
 * The device zone the home screen is laid out in, provided from
 * [org.onekash.kashcal.ui.viewmodels.HomeViewModel.displayZone].
 *
 * Static, so a change recomposes everything below the provider without skipping. A `remember`
 * whose block reads the zone (directly or through a helper's `ZoneId.systemDefault()` default)
 * re-runs on a zone change only when it takes this value as a key; the block still reads the
 * device zone itself.
 */
val LocalDisplayZone = staticCompositionLocalOf<ZoneId> { ZoneId.systemDefault() }
