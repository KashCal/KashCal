package org.onekash.kashcal.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the device zone the app last saw, so open screens can re-read and re-lay out when it
 * changes.
 *
 * A zone change rewrites no Room row, so the Room flows don't re-emit on their own. The zone is
 * read again by [refresh]: from [org.onekash.kashcal.widget.TimezoneChangeHandler] on the
 * TIMEZONE_CHANGED broadcast, when the home screen's ViewModel starts, and on its resume in
 * case that broadcast was missed. Code that computes days or times still reads
 * [ZoneId.systemDefault] when it runs; [zone] only says when to run it again.
 */
@Singleton
class ZoneSource @Inject constructor() {

    private val _zone = MutableStateFlow(ZoneId.systemDefault())

    /** The device zone as of the last [refresh]; emits only when it changes. */
    val zone: StateFlow<ZoneId> = _zone.asStateFlow()

    /** Reads the device zone again and emits it if it differs from [zone]. */
    fun refresh() {
        _zone.value = ZoneId.systemDefault()
    }
}
