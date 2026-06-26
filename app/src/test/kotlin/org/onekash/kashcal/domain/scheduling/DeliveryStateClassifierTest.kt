package org.onekash.kashcal.domain.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [classifyDelivery] against the RFC 6638 §3.2.9 delivery
 * status codes and the §7.1 SCHEDULE-AGENT semantics. The classifier is the
 * single interpretation home for "what did the server decide about delivery,"
 * so every consumer agrees without re-deriving the code rules.
 */
class DeliveryStateClassifierTest {

    @Test
    fun `pending 1x is delivered or attempted`() {
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("1.0", null))
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("1.1", null))
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("1.2", null))
    }

    @Test
    fun `success 2x is delivered or attempted`() {
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("2.0", null))
    }

    @Test
    fun `delivery failure 5x is delivered or attempted (the server tried)`() {
        // 5.x = the server attempted delivery but the recipient was
        // undeliverable. It is still a positive "the server owns delivery"
        // signal — the client must NOT also send.
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("5.1", null))
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("5.0", null))
    }

    @Test
    fun `rejection 3x is server-owns-delivery, not no-receipt`() {
        // 3.7 = "Invalid calendar user" (RFC 5545 §3.8.8.3). The server
        // processed and rejected delivery; the client must NOT then try to
        // send to the same bad address. Presence of any stamped code — not a
        // 1/2/5 allowlist — is what makes this classify correctly.
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("3.7", null))
    }

    @Test
    fun `schedule-agent CLIENT with no delivering status means client must deliver`() {
        assertEquals(DeliveryState.ClientMustDeliver, classifyDelivery(null, "CLIENT"))
    }

    @Test
    fun `neither parameter present is no receipt`() {
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(null, null))
    }

    @Test
    fun `schedule-agent SERVER or NONE with no status is no receipt`() {
        // SERVER is the default and not itself a delivery receipt; NONE means
        // store-only. Without a SCHEDULE-STATUS there is no positive signal.
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(null, "SERVER"))
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(null, "NONE"))
    }

    @Test
    fun `a delivering status wins over schedule-agent CLIENT`() {
        // If the server both declined-by-agent and yet stamped a delivery
        // status, the delivery receipt is authoritative — do not client-send.
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("1.2", "CLIENT"))
    }

    @Test
    fun `multi-value status classifies off the leading code`() {
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("2.0,2.4", null))
    }

    @Test
    fun `blank or whitespace status is treated as absent`() {
        assertEquals(DeliveryState.NoReceipt, classifyDelivery("", null))
        assertEquals(DeliveryState.NoReceipt, classifyDelivery("   ", null))
        assertEquals(DeliveryState.ClientMustDeliver, classifyDelivery("  ", "client"))
    }

    @Test
    fun `schedule-agent matching is case-insensitive`() {
        assertEquals(DeliveryState.ClientMustDeliver, classifyDelivery(null, "client"))
    }

    @Test
    fun `status with surrounding whitespace still classifies`() {
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery(" 2.0 ", null))
    }

    // ========== Adversarial: hostile / malformed inputs ==========
    // The columns are populated from whatever a server stamps on the wire, so
    // the classifier must never crash and must degrade to a safe verdict on
    // garbage. "Safe" = a present-but-junk status still reads as
    // ServerOwnsDelivery (a stamp means the server processed it), and only a
    // genuinely empty/absent status falls through to agent / NoReceipt.

    @Test
    fun `leading-comma status does not throw and is treated as empty`() {
        // substringBefore(',') on ",2.0" yields "" -> no leading code.
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(",2.0", null))
    }

    @Test
    fun `comma-only status is treated as empty`() {
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(",", null))
    }

    @Test
    fun `non-numeric junk status is still a present stamp`() {
        // A server that stamps a non-numeric token still demonstrably processed
        // the message; presence (not numeric validity) is the signal.
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("garbage", null))
    }

    @Test
    fun `agent CLIENT with surrounding junk whitespace and case still routes to client`() {
        assertEquals(DeliveryState.ClientMustDeliver, classifyDelivery(null, "\tCliEnt\n"))
    }

    @Test
    fun `agent value that merely contains CLIENT as substring is NOT a client decline`() {
        // Exact-match (after trim), not contains — "CLIENTX" / "NOTCLIENT" are
        // unknown agents, not an explicit client-delivery decline.
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(null, "CLIENTX"))
        assertEquals(DeliveryState.NoReceipt, classifyDelivery(null, "NOT-CLIENT"))
    }

    @Test
    fun `very long status string does not throw and classifies on leading code`() {
        val huge = "2.0," + "9.9,".repeat(5000)
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery(huge, null))
    }

    @Test
    fun `newline-injected status classifies on the leading code only`() {
        assertEquals(DeliveryState.ServerOwnsDelivery, classifyDelivery("2.0\n5.1", null))
    }

    @Test
    fun `both inputs blank degrade to no receipt`() {
        assertEquals(DeliveryState.NoReceipt, classifyDelivery("", ""))
        assertEquals(DeliveryState.NoReceipt, classifyDelivery("\t", "\n"))
    }
}
