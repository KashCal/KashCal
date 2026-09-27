package org.onekash.kashcal.domain.scheduling

/**
 * Names the delivery decision a CalDAV server recorded for an attendee after an organizer PUT, as
 * read back from the stored resource (RFC 6638 §3.2.1).
 *
 * The one interpretation of the captured `SCHEDULE-STATUS` and `SCHEDULE-AGENT` parameters:
 * consumers classify through [classifyDelivery] instead of re-deriving the §3.2.9 code rules.
 */
enum class DeliveryState {
    /**
     * The server stamped a `SCHEDULE-STATUS` code, so it processed the scheduling operation
     * itself and the client must not also send. The leading digit (RFC 6638 §3.2.9) tells 1.x
     * pending or sent, 2.x delivered, 3.x rejected (e.g. invalid user) and 5.x undeliverable
     * apart, but for routing they collapse: the server owns delivery in every case. The stored
     * code keeps the finer distinction for any consumer that needs it.
     */
    ServerOwnsDelivery,

    /**
     * The server declined to deliver via `SCHEDULE-AGENT=CLIENT` (RFC 6638 §7.1) and gave no
     * status code, so the client must deliver.
     */
    ClientMustDeliver,

    /**
     * Neither parameter is present: the server gave no evidence of delivery (inert, or not yet
     * stamped).
     */
    NoReceipt,
}

/**
 * Classifies a server's delivery decision from the stored `SCHEDULE-STATUS` and `SCHEDULE-AGENT`
 * values of one attendee. Pure: no DB or network access.
 *
 * @param scheduleStatus a single statcode or a comma-separated list (RFC 6638 §7.3); only the
 *   leading code is read. The pull stores only the first code.
 * @param scheduleAgent `SERVER`, `CLIENT` or `NONE` (RFC 6638 §7.1), matched case-insensitively.
 */
fun classifyDelivery(scheduleStatus: String?, scheduleAgent: String?): DeliveryState {
    // A status code wins even over an agent that says CLIENT: the server acted on delivery.
    val leadingCode = scheduleStatus
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (leadingCode != null) {
        return DeliveryState.ServerOwnsDelivery
    }

    val declinedByClient = scheduleAgent?.trim().equals("CLIENT", ignoreCase = true)
    if (declinedByClient) {
        return DeliveryState.ClientMustDeliver
    }

    return DeliveryState.NoReceipt
}

/**
 * Names the client action implied by a [DeliveryState] and whether the account has a usable
 * scheduling-outbox URL.
 *
 * [routeDelivery] is the one home of the RFC 6638 routing rule (§3 implicit PUT, §6 outbox
 * fallback); the push path calls it instead of re-deriving the branches.
 */
enum class DeliveryAction {
    /** The server took ownership of delivery on the PUT; do nothing more. */
    ServerHandles,

    /**
     * The server declined (SCHEDULE-AGENT=CLIENT) and the account has an outbox: POST the iTIP
     * message there (RFC 6638 §6), a REQUEST for an invite or a CANCEL for a removed guest.
     */
    ClientOutboxPost,

    /**
     * No client-side CalDAV channel can deliver: the server stamped no receipt, or it declined
     * but exposes no usable outbox. Delivery is the server's or the user's responsibility, a
     * known limitation.
     */
    NoRemedy,
}

/**
 * Maps [state] and whether the account has an outbox to the client's delivery action. Pure: no
 * DB or network.
 *
 * @param state one attendee's state from [classifyDelivery].
 * @param hasOutboxUrl true when the account has a discovered `schedule-outbox-URL` to POST to.
 */
fun routeDelivery(state: DeliveryState, hasOutboxUrl: Boolean): DeliveryAction = when (state) {
    DeliveryState.ServerOwnsDelivery -> DeliveryAction.ServerHandles
    DeliveryState.ClientMustDeliver ->
        if (hasOutboxUrl) DeliveryAction.ClientOutboxPost else DeliveryAction.NoRemedy
    DeliveryState.NoReceipt -> DeliveryAction.NoRemedy
}
