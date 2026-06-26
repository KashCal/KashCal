package org.onekash.kashcal.sync.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.sync.client.model.OutboxDeliveryClass
import org.onekash.kashcal.sync.client.model.OutboxResponse
import org.onekash.kashcal.sync.client.model.classifyRequestStatus

/**
 * Parses a CALDAV:schedule-response document (RFC 6638 §10.1, Appendix B.6)
 * into per-recipient (recipient, request-status) pairs. The parser must be
 * namespace-prefix tolerant (servers use C:/D:/caldav:/no-prefix), survive
 * missing children and multiple <response> blocks, and never throw on garbage
 * (a hostile/empty body yields an empty result, never a crash).
 */
class OutboxResponseParserTest {

    @Test
    fun `parses a single 2_0 Success response (Zoho shape)`() {
        // The exact positive shape a live Zoho server returned.
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <C:response>
                <C:recipient><D:href>mailto:guest@example.test</D:href></C:recipient>
                <C:request-status>2.0;Success</C:request-status>
                <C:responsedescription>Event Invitation mail has been successfully sent.</C:responsedescription>
              </C:response>
            </C:schedule-response>
        """.trimIndent()

        val result = OutboxResponse.parse(xml)

        assertEquals(1, result.recipients.size)
        assertEquals("mailto:guest@example.test", result.recipients[0].recipient)
        assertEquals("2.0;Success", result.recipients[0].requestStatus)
    }

    @Test
    fun `parses the real Zoho response shape (request-status before recipient, trailing newlines)`() {
        // Captured live from Zoho 2026-06: request-status comes BEFORE recipient
        // (RFC examples show the reverse), element text has trailing newlines,
        // and the D: namespace is declared inline on the href.
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <B:schedule-response xmlns:B="urn:ietf:params:xml:ns:caldav">
            <B:response>
            <B:request-status>2.0;Success
            </B:request-status>
            <B:responsedescription>Event Invitation mail has been successfully sent.
            </B:responsedescription>
            <B:recipient>
            <D:href xmlns:D="DAV:">mailto:guest@example.test
            </D:href>
            </B:recipient>
            </B:response>
            </B:schedule-response>
        """.trimIndent()

        val result = OutboxResponse.parse(xml)

        assertEquals(1, result.recipients.size)
        assertEquals("mailto:guest@example.test", result.recipients[0].recipient)
        assertEquals("2.0;Success", result.recipients[0].requestStatus)
        assertEquals(OutboxDeliveryClass.SUCCESS, classifyRequestStatus(result.recipients[0].requestStatus))
    }

    @Test
    fun `parses multiple recipients with mixed request-status (success and invalid user)`() {
        // RFC 6638 Appendix B.6: one <response> per recipient, each with its
        // own request-status — including a 3.7 for a bad calendar user.
        val xml = """
            <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <C:response>
                <C:recipient><D:href>mailto:ok@example.test</D:href></C:recipient>
                <C:request-status>2.0;Success</C:request-status>
              </C:response>
              <C:response>
                <C:recipient><D:href>mailto:bad@example.test</D:href></C:recipient>
                <C:request-status>3.7;Invalid calendar user</C:request-status>
              </C:response>
            </C:schedule-response>
        """.trimIndent()

        val result = OutboxResponse.parse(xml)

        assertEquals(2, result.recipients.size)
        val byRecipient = result.recipients.associateBy { it.recipient }
        assertEquals("2.0;Success", byRecipient["mailto:ok@example.test"]?.requestStatus)
        assertEquals("3.7;Invalid calendar user", byRecipient["mailto:bad@example.test"]?.requestStatus)
    }

    @Test
    fun `tolerates a default-namespace (no prefix) document`() {
        val xml = """
            <schedule-response xmlns="urn:ietf:params:xml:ns:caldav" xmlns:d="DAV:">
              <response>
                <recipient><d:href>mailto:a@example.test</d:href></recipient>
                <request-status>2.0;Success</request-status>
              </response>
            </schedule-response>
        """.trimIndent()

        val result = OutboxResponse.parse(xml)

        assertEquals(1, result.recipients.size)
        assertEquals("mailto:a@example.test", result.recipients[0].recipient)
        assertEquals("2.0;Success", result.recipients[0].requestStatus)
    }

    @Test
    fun `empty schedule-response yields no recipients`() {
        // Mailbox/OX returns an empty <schedule-response/> for event REQUESTs.
        val result = OutboxResponse.parse("<C:schedule-response xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>")
        assertTrue(result.recipients.isEmpty())
    }

    @Test
    fun `garbage non-XML body yields no recipients without throwing`() {
        assertTrue(OutboxResponse.parse("this is not xml at all <<<").recipients.isEmpty())
        assertTrue(OutboxResponse.parse("").recipients.isEmpty())
    }

    @Test
    fun `response missing a request-status records a null status`() {
        val xml = """
            <C:schedule-response xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <C:response>
                <C:recipient><D:href>mailto:nostatus@example.test</D:href></C:recipient>
              </C:response>
            </C:schedule-response>
        """.trimIndent()

        val result = OutboxResponse.parse(xml)

        assertEquals(1, result.recipients.size)
        assertEquals("mailto:nostatus@example.test", result.recipients[0].recipient)
        assertEquals(null, result.recipients[0].requestStatus)
    }

    // ===== delivery-class classification (leading status digit, RFC 5546 §3.6) =====

    @Test
    fun `classifies 2_x as success`() {
        assertEquals(OutboxDeliveryClass.SUCCESS, classifyRequestStatus("2.0;Success"))
        assertEquals(OutboxDeliveryClass.SUCCESS, classifyRequestStatus("2.1;partial"))
    }

    @Test
    fun `classifies 5_1 as transient retry`() {
        assertEquals(OutboxDeliveryClass.TRANSIENT, classifyRequestStatus("5.1;Service unavailable"))
    }

    @Test
    fun `classifies 5_2 5_3 and 3_x as permanent`() {
        assertEquals(OutboxDeliveryClass.PERMANENT, classifyRequestStatus("5.2;permanent"))
        assertEquals(OutboxDeliveryClass.PERMANENT, classifyRequestStatus("5.3;not allowed"))
        assertEquals(OutboxDeliveryClass.PERMANENT, classifyRequestStatus("3.7;Invalid calendar user"))
    }

    @Test
    fun `classifies null or unparseable status as transient (safe to retry)`() {
        assertEquals(OutboxDeliveryClass.TRANSIENT, classifyRequestStatus(null))
        assertEquals(OutboxDeliveryClass.TRANSIENT, classifyRequestStatus("garbage"))
    }
}
