package org.onekash.kashcal.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import kotlin.math.abs

private const val CIRCULAR_MULTIPLIER = 1000

/** Maps a virtual list index to an index into the items, wrapping when [isCircular]. */
internal fun virtualToActualIndex(virtualIndex: Int, itemCount: Int, isCircular: Boolean): Int {
    if (!isCircular || itemCount <= 0) return virtualIndex.coerceIn(0, maxOf(0, itemCount - 1))
    return ((virtualIndex % itemCount) + itemCount) % itemCount  // Safe for negative
}

/** Returns the virtual index nearest [currentVirtualIndex] that maps to [targetActualIndex]. */
internal fun actualToNearestVirtualIndex(
    targetActualIndex: Int,
    currentVirtualIndex: Int,
    itemCount: Int,
    isCircular: Boolean
): Int {
    if (!isCircular) return targetActualIndex
    val currentActual = virtualToActualIndex(currentVirtualIndex, itemCount, true)
    var delta = targetActualIndex - currentActual
    // Take the shorter way round the wheel
    if (delta > itemCount / 2) delta -= itemCount
    else if (delta < -itemCount / 2) delta += itemCount
    return currentVirtualIndex + delta
}

/**
 * Shows a vertical wheel that snaps to its center item and reports the centered item through
 * [onItemSelected], mid-fling included.
 *
 * @param isCircular wraps around endlessly; ignored for fewer than two items.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> VerticalWheelPicker(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 36.dp,
    visibleItems: Int = 5,
    isCircular: Boolean = false,
    itemContent: @Composable (item: T, isSelected: Boolean) -> Unit
) {
    val effectiveCircular = isCircular && items.size >= 2

    // A circular wheel is a long virtual list that starts in the middle.
    val virtualCount = if (effectiveCircular) items.size * CIRCULAR_MULTIPLIER else items.size
    val middleOffset = if (effectiveCircular) (CIRCULAR_MULTIPLIER / 2) * items.size else 0

    // Offset scroll targets so the selected item appears at viewport center.
    // contentPadding cannot center items in the middle of a large virtual list.
    val centeringOffset = visibleItems / 2

    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val initialIndex = if (effectiveCircular) {
        middleOffset + selectedIndex - centeringOffset
    } else {
        (selectedIndex - centeringOffset).coerceAtLeast(0)
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Last emitted index; a jump over half the wheel is a wrap
    var previousActualIndex by remember { mutableIntStateOf(selectedIndex) }

    // The item whose center is closest to the viewport's center pixel, which holds whatever
    // the contentPadding or scroll position.
    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenterPx = layoutInfo.viewportSize.height / 2

            layoutInfo.visibleItemsInfo.minByOrNull { itemInfo ->
                val itemCenterPx = itemInfo.offset + itemInfo.size / 2
                abs(itemCenterPx - viewportCenterPx)
            }?.index ?: (listState.firstVisibleItemIndex + centeringOffset)
        }
    }

    // Emits the centered item as it changes, mid-fling included, so a Done tap before the snap
    // settles commits the value the user sees. The collector outlives a single composition,
    // so it reads the parameters through rememberUpdatedState.
    val currentItems by rememberUpdatedState(items)
    val currentSelected by rememberUpdatedState(selectedItem)
    val currentEffectiveCircular by rememberUpdatedState(effectiveCircular)
    val currentOnItemSelected by rememberUpdatedState(onItemSelected)
    LaunchedEffect(listState) {
        snapshotFlow { centerIndex }
            .distinctUntilChanged()
            .collect { virtualIdx ->
                val itemList = currentItems
                val isCirc = currentEffectiveCircular
                if (itemList.isEmpty()) return@collect
                val actualIndex = virtualToActualIndex(virtualIdx, itemList.size, isCirc)
                itemList.getOrNull(actualIndex)?.let { item ->
                    if (item != currentSelected) {
                        val wrapped = isCirc &&
                            abs(actualIndex - previousActualIndex) > itemList.size / 2
                        if (wrapped) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        previousActualIndex = actualIndex
                        currentOnItemSelected(item)
                    }
                }
            }
    }

    // Recenters a circular wheel that drifted toward a virtual-list edge, only once scrolling
    // stops: a scrollToItem mid-fling would fight the snap fling.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && effectiveCircular) {
            val centerVirtualIndex = centerIndex
            val actualIndex = virtualToActualIndex(centerVirtualIndex, items.size, true)
            val middleStart = (CIRCULAR_MULTIPLIER / 2) * items.size
            if (abs(centerVirtualIndex - middleStart) > (CIRCULAR_MULTIPLIER / 4) * items.size) {
                listState.scrollToItem(middleStart + actualIndex - centeringOffset)
            }
        }
    }

    // Scrolls to selectedItem when it changes from outside
    LaunchedEffect(selectedItem) {
        // Wait out a fling first. The emission above keeps selectedItem on the centered item,
        // so after a user fling the target is usually centered and nothing scrolls; an outside
        // update that raced the fling is still applied by the check after it settles.
        if (listState.isScrollInProgress) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
        }
        val targetActualIndex = items.indexOf(selectedItem)
        if (targetActualIndex >= 0) {
            // No scroll when the target is already at the pixel center
            val currentCenterActual = virtualToActualIndex(centerIndex, items.size, effectiveCircular)
            if (targetActualIndex != currentCenterActual) {
                val currentVirtualIndex = listState.firstVisibleItemIndex
                val targetVirtualIndex = actualToNearestVirtualIndex(
                    targetActualIndex, currentVirtualIndex, items.size, effectiveCircular
                )
                coroutineScope.launch {
                    val scrollTarget = if (effectiveCircular) {
                        targetVirtualIndex - centeringOffset
                    } else {
                        (targetVirtualIndex - centeringOffset).coerceAtLeast(0)
                    }
                    listState.animateScrollToItem(scrollTarget)
                }
            }
        }
    }

    val wheelPickerDescription = stringResource(R.string.cd_wheel_picker, items.size)
    val circularScrollingDescription = stringResource(R.string.cd_circular_scrolling)
    Box(
        modifier = modifier
            .height(itemHeight * visibleItems)
            .fillMaxWidth()
            .semantics {
                contentDescription = wheelPickerDescription
                if (effectiveCircular) {
                    stateDescription = circularScrollingDescription
                }
            }
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = if (effectiveCircular) PaddingValues(0.dp)
                else PaddingValues(vertical = itemHeight * (visibleItems / 2)),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                count = virtualCount,
                key = { it }
            ) { virtualIndex ->
                val actualIndex = virtualToActualIndex(virtualIndex, items.size, effectiveCircular)
                val item = items[actualIndex]
                val distanceFromCenter = abs(virtualIndex - centerIndex)
                val alpha = when (distanceFromCenter) {
                    0 -> 1f
                    1 -> 0.6f
                    else -> 0.3f
                }

                Box(
                    modifier = Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        .alpha(alpha),
                    contentAlignment = Alignment.Center
                ) {
                    itemContent(item, distanceFromCenter == 0)
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(itemHeight * 1.5f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(itemHeight * 1.5f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        )
    }
}

/**
 * Shows circular wheels for hour, minute and, in 12-hour mode, AM/PM, reporting each change as
 * a 24-hour time.
 *
 * @param selectedHour hour in 24-hour form (0-23) in either mode.
 * @param selectedMinute minute (0-59), snapped to the nearest minute option.
 * @param onTimeSelected receives the hour in 24-hour form (0-23) and the minute.
 * @param use24Hour shows 00-23 and minutes; otherwise 1-12, minutes and AM/PM.
 * @param minuteInterval step between minute options, which run from 0 up to 55.
 */
@Composable
fun WheelTimePicker(
    selectedHour: Int,
    selectedMinute: Int,
    onTimeSelected: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    use24Hour: Boolean = false,
    minuteInterval: Int = 5,
    visibleItems: Int = 5,
    itemHeight: Dp = 36.dp
) {
    var currentMinute by remember(selectedMinute) { mutableIntStateOf(selectedMinute) }

    val minuteOptions = (0..55 step minuteInterval).toList()
    val closestMinute = minuteOptions.minByOrNull { abs(it - currentMinute) } ?: 0

    LaunchedEffect(selectedMinute) {
        if (currentMinute != closestMinute) {
            currentMinute = closestMinute
        }
    }

    if (use24Hour) {
        var currentHour24 by remember(selectedHour) { mutableIntStateOf(selectedHour) }
        val hourOptions = (0..23).toList()

        fun notifyTimeChange() {
            onTimeSelected(currentHour24, currentMinute)
        }

        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VerticalWheelPicker(
                    items = hourOptions,
                    selectedItem = currentHour24,
                    onItemSelected = { hour ->
                        currentHour24 = hour
                        notifyTimeChange()
                    },
                    modifier = Modifier.weight(1f),
                    visibleItems = visibleItems,
                    itemHeight = itemHeight,
                    isCircular = true
                ) { hour, isSelected ->
                    Text(
                        text = String.format(java.util.Locale.ROOT, "%02d", hour),
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = ":",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                VerticalWheelPicker(
                    items = minuteOptions,
                    selectedItem = currentMinute,
                    onItemSelected = { minute ->
                        currentMinute = minute
                        notifyTimeChange()
                    },
                    modifier = Modifier.weight(1f),
                    visibleItems = visibleItems,
                    itemHeight = itemHeight,
                    isCircular = true
                ) { minute, isSelected ->
                    Text(
                        text = String.format(java.util.Locale.ROOT, "%02d", minute),
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    } else {
        val hour12 = when {
            selectedHour == 0 -> 12
            selectedHour > 12 -> selectedHour - 12
            else -> selectedHour
        }
        val isPM = selectedHour >= 12

        var currentHour12 by remember(selectedHour) { mutableIntStateOf(hour12) }
        var currentIsPM by remember(selectedHour) { mutableStateOf(isPM) }

        val hourOptions = (1..12).toList()
        // The locale's AM/PM labels
        val amPmStrings = remember { java.text.DateFormatSymbols.getInstance().amPmStrings }
        val amPmOptions = amPmStrings.toList()

        fun notifyTimeChange() {
            val hour24 = when {
                currentHour12 == 12 && !currentIsPM -> 0      // 12 AM = 0
                currentHour12 == 12 && currentIsPM -> 12      // 12 PM = 12
                currentIsPM -> currentHour12 + 12             // 1-11 PM = 13-23
                else -> currentHour12                          // 1-11 AM = 1-11
            }
            onTimeSelected(hour24, currentMinute)
        }

        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VerticalWheelPicker(
                    items = hourOptions,
                    selectedItem = currentHour12,
                    onItemSelected = { hour ->
                        currentHour12 = hour
                        notifyTimeChange()
                    },
                    modifier = Modifier.weight(1f),
                    visibleItems = visibleItems,
                    itemHeight = itemHeight,
                    isCircular = true
                ) { hour, isSelected ->
                    Text(
                        text = hour.toString(),
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = ":",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                VerticalWheelPicker(
                    items = minuteOptions,
                    selectedItem = currentMinute,
                    onItemSelected = { minute ->
                        currentMinute = minute
                        notifyTimeChange()
                    },
                    modifier = Modifier.weight(1f),
                    visibleItems = visibleItems,
                    itemHeight = itemHeight,
                    isCircular = true
                ) { minute, isSelected ->
                    Text(
                        text = String.format(java.util.Locale.ROOT, "%02d", minute),
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                // Circular like the other wheels so it centers the same way
                VerticalWheelPicker(
                    items = amPmOptions,
                    selectedItem = if (currentIsPM) amPmOptions[1] else amPmOptions[0],
                    onItemSelected = { amPm ->
                        currentIsPM = amPm == amPmOptions[1]
                        notifyTimeChange()
                    },
                    modifier = Modifier.weight(0.8f),
                    visibleItems = visibleItems,
                    itemHeight = itemHeight,
                    isCircular = true
                ) { amPm, isSelected ->
                    Text(
                        text = amPm,
                        fontSize = if (isSelected) 16.sp else 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "WheelTimePicker - Light")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "WheelTimePicker - Dark")
@Composable
private fun WheelTimePickerPreview() {
    MaterialTheme {
        WheelTimePicker(
            selectedHour = 14,  // 2:00 PM
            selectedMinute = 30,
            onTimeSelected = { _, _ -> }
        )
    }
}
