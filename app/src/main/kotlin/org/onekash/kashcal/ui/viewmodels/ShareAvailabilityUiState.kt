package org.onekash.kashcal.ui.viewmodels

import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.availability.FreeBlock

data class ShareAvailabilityUiState(
    val days: Int = 7,
    val workStartMin: Int = 9 * 60,
    val workEndMin: Int = 17 * 60,
    val includeAllDay: Boolean = false,
    val blocks: List<FreeBlock> = emptyList(),
    val previewText: String = "",
    val isShareEnabled: Boolean = false,
    val isLoading: Boolean = true,
    /**
     * App TIME_FORMAT preference ("system", "12h", "24h"). Combined with the
     * device's 24h setting via DateTimeUtils.isUse24Hour to produce the
     * effective is24Hour value used for slider labels and shared text.
     */
    val timeFormatPref: String = KashCalDataStore.TIME_FORMAT_SYSTEM
)
