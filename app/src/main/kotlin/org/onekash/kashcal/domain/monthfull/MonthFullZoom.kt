package org.onekash.kashcal.domain.monthfull

const val MIN_MONTH_FULL_ZOOM = 0.85f
const val MAX_MONTH_FULL_ZOOM = 1.6f

/**
 * Clamps a full-height month view zoom to [MIN_MONTH_FULL_ZOOM]..[MAX_MONTH_FULL_ZOOM]; NaN,
 * infinite or <= 0 is 1.
 */
fun clampMonthFullZoom(value: Float): Float =
    if (value.isNaN() || value.isInfinite() || value <= 0f) 1f else value.coerceIn(MIN_MONTH_FULL_ZOOM, MAX_MONTH_FULL_ZOOM)
