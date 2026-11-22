package org.onekash.kashcal.domain.rrule

/**
 * Thrown by a series save when the repeat rule can't express the move ([SeriesMove.plan]
 * returns null, or a cancelled date or changed occurrence has no occurrence to land on).
 * Nothing is written. Callers that check availability first ([RruleShift.dragAvailability] for a
 * drop on another day, [SeriesMove.planSave] and [SeriesMove.canSplit] for the form and a same-day
 * drop) grey the scope out instead of reaching it.
 */
class MoveNotExpressibleException : IllegalStateException("The repeat rule can't express this move")
