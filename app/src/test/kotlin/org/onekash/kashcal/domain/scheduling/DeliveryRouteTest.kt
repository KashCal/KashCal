package org.onekash.kashcal.domain.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the full truth table of [routeDelivery] (RFC 6638 §3, §6): the client action for each
 * captured [DeliveryState], with and without a usable scheduling-outbox URL. It asserts the
 * production function the push path calls, so there is no test copy of the rule to drift.
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
        // The server declined (SCHEDULE-AGENT=CLIENT) but there is no outbox to POST to, so
        // the client can do nothing over CalDAV.
        assertEquals(DeliveryAction.NoRemedy, routeDelivery(DeliveryState.ClientMustDeliver, hasOutboxUrl = false))
    }

    @Test
    fun `no receipt has no remedy whether or not an outbox is advertised`() {
        // NoReceipt: the server stamped nothing and didn't decline as CLIENT. Even with an
        // advertised outbox (SOGo), a plain PUT sent nothing and the outbox doesn't accept
        // event REQUESTs, so there is no client remedy.
        assertEquals(DeliveryAction.NoRemedy, routeDelivery(DeliveryState.NoReceipt, hasOutboxUrl = true))
        assertEquals(DeliveryAction.NoRemedy, routeDelivery(DeliveryState.NoReceipt, hasOutboxUrl = false))
    }
}
