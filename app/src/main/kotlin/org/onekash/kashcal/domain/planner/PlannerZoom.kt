package org.onekash.kashcal.domain.planner

const val MIN_PLANNER_ZOOM = 0.85f
const val MAX_PLANNER_ZOOM = 1.6f

/** Clamps a Planner zoom to [MIN_PLANNER_ZOOM]..[MAX_PLANNER_ZOOM]; NaN, infinite or <= 0 is 1. */
fun clampPlannerZoom(value: Float): Float =
    if (value.isNaN() || value.isInfinite() || value <= 0f) 1f else value.coerceIn(MIN_PLANNER_ZOOM, MAX_PLANNER_ZOOM)
