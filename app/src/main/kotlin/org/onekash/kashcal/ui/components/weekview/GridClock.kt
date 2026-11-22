package org.onekash.kashcal.ui.components.weekview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Clock

/**
 * Returns [clock]'s time in epoch ms, updated just after each minute starts: the one "now" the
 * time grid's now line, today, past fade and screen-reader Create event time read.
 *
 * Each update is at least 1 ms past the minute it waited for, so an event ending on that minute
 * has ended at the update ([DateTimeUtils.isEventPast] counts an end before now as past). Against
 * a clock that doesn't move, as a test's may, the value can run up to a minute ahead of it. The
 * wait counts uptime, so after the phone sleeps an update can come up to a minute late.
 */
@Composable
internal fun rememberGridMinuteClock(clock: Clock): State<Long> {
    val currentClock by rememberUpdatedState(clock)
    val now = remember { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val millis = currentClock.millis()
            val nextMinute = (millis / MINUTE_MS + 1) * MINUTE_MS
            delay(nextMinute + 1 - millis)
            now.longValue = maxOf(currentClock.millis(), nextMinute + 1)
        }
    }
    return now
}

private const val MINUTE_MS = 60_000L
