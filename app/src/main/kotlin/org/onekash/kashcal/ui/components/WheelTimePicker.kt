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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val CIRCULAR_MULTIPLIER = 1000

/** Map virtual index → actual index. Handles modulo wrap. */
internal fun virtualToActualIndex(virtualIndex: Int, itemCount: Int, isCircular: Boolean): Int {
    if (!isCircular || itemCount <= 0) return virtualIndex.coerceIn(0, maxOf(0, itemCount - 1))
    return ((virtualIndex % itemCount) + itemCount) % itemCount  // Safe for negative
}

/** Find virtual index nearest to current scroll that maps to target actual index. */
internal fun actualToNearestVirtualIndex(
    targetActualIndex: Int,
    currentVirtualIndex: Int,
    itemCount: Int,
    isCircular: Boolean
): Int {
    if (!isCircular) return targetActualIndex
    val currentActual = virtualToActualIndex(currentVirtualIndex, itemCount, true)
    var delta = targetActualIndex - currentActual
    // Choose shorter path (wrap if needed)
    if (delta > itemCount / 2) delta -= itemCount
    else if (delta < -itemCount / 2) delta += itemCount
    return currentVirtualIndex + delta
}

/**
 * iOS-style vertical wheel picker component.
 * Uses LazyColumn with snap behavior for smooth scrolling and center-item selection.
 *
 * Best practices applied:
 * - State hoisting: receives selectedItem, emits onItemSelected
 * - derivedStateOf: for computed centerIndex from scroll position
 * - rememberSnapFlingBehavior: for iOS-style snapping
 *
 * @param isCircular If true, enables infinite/circular scrolling (wraps around)
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
    // Disable circular for tiny lists
    val effectiveCircular = isCircular && items.size >= 3

    // Virtual list sizing
    val virtualCount = if (effectiveCircular) items.size * CIRCULAR_MULTIPLIER else items.size
    val middleOffset = if (effectiveCircular) (CIRCULAR_MULTIPLIER / 2) * items.size else 0

    // Initial scroll position (start in middle for circular)
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val initialIndex = middleOffset + selectedIndex

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Track previous actual index for wrap detection
    var previousActualIndex by remember { mutableIntStateOf(selectedIndex) }

    // Calculate center index based on scroll position using derivedStateOf
    val centerIndex by remember {
        derivedStateOf {
            val firstVisible = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val itemHeightPx = if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                listState.layoutInfo.visibleItemsInfo.first().size
            } else {
                itemHeight.value.toInt()
            }

            // Determine center item - if scrolled more than half, next item is center
            if (offset > itemHeightPx / 2) {
                firstVisible + 1
            } else {
                firstVisible
            }
        }
    }

    // Detect when scrolling settles and notify selection + handle recentering
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerVirtualIndex = listState.firstVisibleItemIndex
            val actualIndex = virtualToActualIndex(centerVirtualIndex, items.size, effectiveCircular)

            // 1. Selection callback with wrap-aware haptic
            items.getOrNull(actualIndex)?.let { item ->
                if (item != selectedItem) {
                    val wrapped = effectiveCircular && abs(actualIndex - previousActualIndex) > items.size / 2
                    if (wrapped) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    previousActualIndex = actualIndex
                    onItemSelected(item)
                }
            }

            // 2. Edge recentering (if needed) - AFTER callback
            if (effectiveCircular) {
                val middleStart = (CIRCULAR_MULTIPLIER / 2) * items.size
                if (abs(centerVirtualIndex - middleStart) > (CIRCULAR_MULTIPLIER / 4) * items.size) {
                    listState.scrollToItem(middleStart + actualIndex)
                }
            }
        }
    }

    // Scroll to selected item when it changes externally
    LaunchedEffect(selectedItem) {
        val targetActualIndex = items.indexOf(selectedItem)
        if (targetActualIndex >= 0) {
            val currentVirtualIndex = listState.firstVisibleItemIndex
            val targetVirtualIndex = actualToNearestVirtualIndex(
                targetActualIndex, currentVirtualIndex, items.size, effectiveCircular
            )
            if (targetVirtualIndex != currentVirtualIndex) {
                coroutineScope.launch {
                    listState.animateScrollToItem(targetVirtualIndex)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .height(itemHeight * visibleItems)
            .fillMaxWidth()
            .semantics {
                contentDescription = "Wheel picker with ${items.size} options"
                if (effectiveCircular) {
                    stateDescription = "Circular scrolling enabled"
                }
            }
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * (visibleItems / 2)),
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

        // Center selection highlight
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

        // Top fade gradient
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

        // Bottom fade gradient
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
 * iOS-style time picker with separate wheels for hours, minutes, and optionally AM/PM.
 *
 * @param selectedHour Hour in 24-hour format (0-23) - internal state
 * @param selectedMinute Minute (0-59)
 * @param onTimeSelected Callback with (hour24, minute)
 * @param use24Hour If true, shows 2 wheels (00-23, minutes). If false, shows 3 wheels (1-12, minutes, AM/PM)
 * @param minuteInterval Interval for minute options (default 5)
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
    // State for minute (same in both modes)
    var currentMinute by remember(selectedMinute) { mutableIntStateOf(selectedMinute) }

    // Generate minute options (same for both modes)
    val minuteOptions = (0..55 step minuteInterval).toList()
    val closestMinute = minuteOptions.minByOrNull { abs(it - currentMinute) } ?: 0

    LaunchedEffect(selectedMinute) {
        if (currentMinute != closestMinute) {
            currentMinute = closestMinute
        }
    }

    if (use24Hour) {
        // ========== 24-HOUR MODE: 2 wheels ==========
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
                // Hour wheel (00-23) - CIRCULAR
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
                        text = String.format("%02d", hour),  // Zero-padded
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                // Colon separator
                Text(
                    text = ":",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Minute wheel - CIRCULAR
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
                        text = String.format("%02d", minute),
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
        // ========== 12-HOUR MODE: 3 wheels ==========
        // Convert 24-hour to 12-hour format
        val hour12 = when {
            selectedHour == 0 -> 12
            selectedHour > 12 -> selectedHour - 12
            else -> selectedHour
        }
        val isPM = selectedHour >= 12

        var currentHour12 by remember(selectedHour) { mutableIntStateOf(hour12) }
        var currentIsPM by remember(selectedHour) { mutableStateOf(isPM) }

        val hourOptions = (1..12).toList()
        // Use localized AM/PM strings
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
                // Hour wheel (1-12) - CIRCULAR
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

                // Colon separator
                Text(
                    text = ":",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Minute wheel - CIRCULAR
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
                        text = String.format("%02d", minute),
                        fontSize = if (isSelected) 18.sp else 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                // AM/PM wheel - NOT CIRCULAR (only 2 items)
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
                    isCircular = false
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
