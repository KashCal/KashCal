package org.onekash.kashcal.domain.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for the delivery routing decision (RFC 6638 §3 / §6): given the
 * server's captured [DeliveryState] and whether the account has a usable
 * scheduling-outbox URL, what action does the client take?
 *
 * This is the single home for the routing rule the push path turns on — the
 * production send gate ([routeDelivery]) is asserted here so the rule cannot
 * drift between a test copy and the code.
 */
class DeliveryRouteTest {

    @Test
    fun `server-owned delivery needs no client action regardless of outbox`() {
        assertEquals(DeliveryAction.ServerHandles, routeDelivery(DeliveryState.ServerOwnsDelivery, hasOutboxUrl = true))
        assertEquals(DeliveryAction.ServerHandles, routeDelivery(DeliveryState.ServerOwnsDelivery, hasOutboxUrl = false))
    }

    @Test
    fun `client-must-deliver with an outbox routes to the outbox POST`() {
        assertEquals(DeliveryAction.ClientOutboxPost, routeDelivery(DeliveryState.ClientMustDeliver, hasOutboxUrl = true))
    }

    @Test
    fun `client-must-deliver without an outbox has no remedy`() {
        // The server declined (SCHEDULE-AGENT=CLIENT) but there is no channel to
        // POST through — nothing the client can do over CalDAV.
        assertEquals(DeliveryAction.NoRemedy, routeDelivery(DeliveryState.ClientMustDeliver, hasOutboxUrl = false))
    }

    @Test
    fun `no receipt has no remedy whether or not an outbox is advertised`() {
        // NoReceipt = server stamped nothing and didn't decline-as-client. Even
        // with an advertised outbox (SOGo), a plain PUT sent nothing and the
        // outbox doesn't accept event REQUESTs — no client remedy.
        assertEquals(DeliveryAction.NoRemedy, routeDelivery(DeliveryState.NoReceipt, hasOutboxUrl = true))
        assertEquals(DeliveryAction.NoRemedy, routeDelivery(DeliveryState.NoReceipt, hasOutboxUrl = false))
    }
}
