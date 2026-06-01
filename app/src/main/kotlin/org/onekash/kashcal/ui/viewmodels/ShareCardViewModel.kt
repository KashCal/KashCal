package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.onekash.kashcal.domain.share.ShareCardStyle
import org.onekash.kashcal.domain.share.ShareCardStylePicker
import javax.inject.Inject

/**
 * Holds the user's currently-selected [ShareCardStyle] for the share-as-card
 * preview sheet.
 *
 * Auto-picks an initial style from the event title via
 * [ShareCardStylePicker]; the user can override via the chip in
 * [org.onekash.kashcal.ui.components.share.ShareCardSheet].
 *
 * Note: per the 2026-05-31 architectural pivot, this ViewModel does NOT own
 * the rendering pipeline. The [GraphicsLayer] used to capture the on-screen
 * preview lives in the sheet itself; the actual PNG write is handled by
 * [org.onekash.kashcal.domain.share.ShareCardRenderer.writePng].
 */
@HiltViewModel
class ShareCardViewModel @Inject constructor() : ViewModel() {

    private val _selectedStyle = MutableStateFlow<ShareCardStyle>(ShareCardStyle.Standard)
    val selectedStyle: StateFlow<ShareCardStyle> = _selectedStyle

    /** Auto-pick the style from the supplied event title. */
    fun loadEventTitle(title: String?) {
        _selectedStyle.value = ShareCardStylePicker.autoPickFor(title)
    }

    /** User override from the chip row. */
    fun setStyle(style: ShareCardStyle) {
        _selectedStyle.value = style
    }
}
