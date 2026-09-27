package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.onekash.kashcal.domain.share.ShareCardStyle
import org.onekash.kashcal.domain.share.ShareCardStylePicker
import javax.inject.Inject

/**
 * Holds the selected [ShareCardStyle] for the share-as-card preview sheet.
 *
 * The initial style is picked from the event title by [ShareCardStylePicker]; the user can
 * change it with the style chips in [org.onekash.kashcal.ui.components.share.ShareCardSheet].
 *
 * This ViewModel doesn't render. The `GraphicsLayer` that captures the on-screen preview lives
 * in the sheet, and [org.onekash.kashcal.domain.share.ShareCardRenderer.writePng] writes the PNG.
 */
@HiltViewModel
class ShareCardViewModel @Inject constructor() : ViewModel() {

    private val _selectedStyle = MutableStateFlow<ShareCardStyle>(ShareCardStyle.Standard)
    val selectedStyle: StateFlow<ShareCardStyle> = _selectedStyle

    /** Picks the style for the event [title]. */
    fun loadEventTitle(title: String?) {
        _selectedStyle.value = ShareCardStylePicker.autoPickFor(title)
    }

    /** Sets the style the user chose in the chip row. */
    fun setStyle(style: ShareCardStyle) {
        _selectedStyle.value = style
    }
}
