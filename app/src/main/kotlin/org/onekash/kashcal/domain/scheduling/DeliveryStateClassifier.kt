package org.onekash.kashcal.domain.scheduling

/**
 * The delivery decision a CalDAV server recorded for a scheduling object after
 * an organizer PUT, as read back from the stored resource (RFC 6638 §3.2.1).
 *
 * This is the single interpretation of the captured `SCHEDULE-STATUS` /
 * `SCHEDULE-AGENT` parameters — consumers (delivery routing, future delivery
 * badges) classify through here rather than re-deriving the §3.2.9 code rules.
 */
enum class DeliveryState {
    /**
     * The server stamped a `SCHEDULE-STATUS` delivery code, so it processed
     * the scheduling operation itself and the client MUST NOT also send. The
     * code's leading digit (RFC 6638 §3.2.9) further distinguishes the outcome
     * — 1.x pending/sent, 2.x delivered, 3.x rejected (e.g. invalid user),
     * 5.x attempted-but-undeliverable — but for routing they collapse: in
     * every case the server, not the client, owns delivery. The raw code is
     * stored server-faithfully for any consumer (e.g. a badge) that needs the
     * finer distinction.
     */
    ServerOwnsDelivery,

    /**
     * The server declined to deliver via `SCHEDULE-AGENT=CLIENT` (RFC 6638
     * §7.1) and gave no delivering status — the client must deliver.
     */
    ClientMustDeliver,

    /**
     * Neither parameter is present: the server gave no evidence of delivery
     * (inert, or not yet stamped). Distinct from both other states.
     */
    NoReceipt,
}

/**
 * Classify a server's delivery decision from the raw `SCHEDULE-STATUS` /
 * `SCHEDULE-AGENT` parameter values stored on a scheduling object resource.
 *
 * Pure: no DB or network access. The raw values are stored server-faithfully
 * elsewhere; this only interprets them.
 *
 * @param scheduleStatus raw `SCHEDULE-STATUS` value (a single statcode or a
 *   comma-separated list per RFC 6638 §7.3); classified off the leading code.
 * @param scheduleAgent raw `SCHEDULE-AGENT` value (`SERVER`/`CLIENT`/`NONE`,
 *   RFC 6638 §7.1), matched case-insensitively.
 */
fun classifyDelivery(scheduleStatus: String?, scheduleAgent: String?): DeliveryState {
    // A delivering status is authoritative — it wins even over an agent that
    // says CLIENT, because the server demonstrably acted on delivery.
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
 * The client action implied by a server's [DeliveryState] plus whether the
 * account advertises a usable scheduling-outbox URL. This is the single home
 * for the RFC 6638 delivery routing rule (§3 implicit PUT → §6 outbox fallback);
 * the push path consumes [routeDelivery] rather than re-deriving the branches,
 * so the rule cannot drift.
 */
enum class DeliveryAction {
    /** The server took ownership of delivery on the PUT — do nothing more. */
    ServerHandles,

    /** The server declined (SCHEDULE-AGENT=CLIENT) and the account has an
     *  outbox — POST a METHOD:REQUEST there (RFC 6638 §6). */
    ClientOutboxPost,

    /** No client-side CalDAV channel can deliver: either the server stamped no
     *  receipt at all, or it declined but exposes no usable outbox. Delivery is
     *  the server's (or the user's) responsibility — a documented limitation,
     *  not a client action. */
    NoRemedy,
}

/**
 * Map the captured [state] (+ whether the account has a usable outbox URL) to
 * the client's delivery action. Pure: no DB or network.
 *
 * @param state the per-attendee delivery state from [classifyDelivery].
 * @param hasOutboxUrl true when the account has a discovered
 *   `schedule-outbox-URL` to POST to.
 */
fun routeDelivery(state: DeliveryState, hasOutboxUrl: Boolean): DeliveryAction = when (state) {
    DeliveryState.ServerOwnsDelivery -> DeliveryAction.ServerHandles
    DeliveryState.ClientMustDeliver ->
        if (hasOutboxUrl) DeliveryAction.ClientOutboxPost else DeliveryAction.NoRemedy
    DeliveryState.NoReceipt -> DeliveryAction.NoRemedy
}
