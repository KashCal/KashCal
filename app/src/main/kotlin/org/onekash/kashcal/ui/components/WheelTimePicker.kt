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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * iOS-style vertical wheel picker component.
 * Uses LazyColumn with snap behavior for smooth scrolling and center-item selection.
 *
 * Best practices applied:
 * - State hoisting: receives selectedItem, emits onItemSelected
 * - derivedStateOf: for computed centerIndex from scroll position
 * - rememberSnapFlingBehavior: for iOS-style snapping
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
    itemContent: @Composable (item: T, isSelected: Boolean) -> Unit
) {
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()

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

    // Detect when scrolling settles and notify selection
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerItem = listState.firstVisibleItemIndex
            items.getOrNull(centerItem)?.let { item ->
                if (item != selectedItem) {
                    onItemSelected(item)
                }
            }
        }
    }

    // Scroll to selected item when it changes externally
    LaunchedEffect(selectedItem) {
        val targetIndex = items.indexOf(selectedItem)
        if (targetIndex >= 0 && targetIndex != listState.firstVisibleItemIndex) {
            coroutineScope.launch {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    Box(
        modifier = modifier
            .height(itemHeight * visibleItems)
            .fillMaxWidth()
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * (visibleItems / 2)),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items, key = { index, _ -> index }) { index, item ->
                val distanceFromCenter = abs(index - centerIndex)
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
 * iOS-style time picker with separate wheels for hours, minutes, and AM/PM.
 *
 * @param selectedHour Hour in 24-hour format (0-23) - internal state
 * @param selectedMinute Minute (0-59)
 * @param onTimeSelected Callback with (hour24, minute)
 * @param minuteInterval Interval for minute options (default 5)
 */
@Composable
fun WheelTimePicker(
    selectedHour: Int,
    selectedMinute: Int,
    onTimeSelected: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    minuteInterval: Int = 5,
    visibleItems: Int = 5,
    itemHeight: Dp = 36.dp
) {
    // Convert 24-hour to 12-hour format
    val hour12 = when {
        selectedHour == 0 -> 12
        selectedHour > 12 -> selectedHour - 12
        else -> selectedHour
    }
    val isPM = selectedHour >= 12

    // State for each wheel
    var currentHour12 by remember(selectedHour) { mutableIntStateOf(hour12) }
    var currentMinute by remember(selectedMinute) { mutableIntStateOf(selectedMinute) }
    var currentIsPM by remember(selectedHour) { mutableStateOf(isPM) }

    // Generate options
    val hourOptions = (1..12).toList()
    val minuteOptions = (0..55 step minuteInterval).toList()
    val amPmOptions = listOf("AM", "PM")

    // Snap minute to nearest interval
    val closestMinute = minuteOptions.minByOrNull { abs(it - currentMinute) } ?: 0
    LaunchedEffect(selectedMinute) {
        if (currentMinute != closestMinute) {
            currentMinute = closestMinute
        }
    }

    // Callback when any wheel changes
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
        // Three wheels in a row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hour wheel
            VerticalWheelPicker(
                items = hourOptions,
                selectedItem = currentHour12,
                onItemSelected = { hour ->
                    currentHour12 = hour
                    notifyTimeChange()
                },
                modifier = Modifier.weight(1f),
                visibleItems = visibleItems,
                itemHeight = itemHeight
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

            // Minute wheel
            VerticalWheelPicker(
                items = minuteOptions,
                selectedItem = currentMinute,
                onItemSelected = { minute ->
                    currentMinute = minute
                    notifyTimeChange()
                },
                modifier = Modifier.weight(1f),
                visibleItems = visibleItems,
                itemHeight = itemHeight
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

            // AM/PM wheel
            VerticalWheelPicker(
                items = amPmOptions,
                selectedItem = if (currentIsPM) "PM" else "AM",
                onItemSelected = { amPm ->
                    currentIsPM = amPm == "PM"
                    notifyTimeChange()
                },
                modifier = Modifier.weight(0.8f),
                visibleItems = visibleItems,
                itemHeight = itemHeight
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
