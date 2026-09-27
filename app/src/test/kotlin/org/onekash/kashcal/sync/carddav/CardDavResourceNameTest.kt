package org.onekash.kashcal.sync.carddav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [contactResourceName]: a UID that is a safe path segment names the resource
 * (`<uid>.vcf`), which keeps writes working on Zoho (it rejects arbitrary names with a
 * misleading 401). Any other UID gets a random UUID name, so the URL is always well-formed and
 * needs no escaping.
 */
class CardDavResourceNameTest {

    private val uuidVcf = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.vcf$")

    @Test
    fun `safe uid becomes uid dot vcf`() {
        assertEquals("A1B2-C3D4-E5F6.vcf", contactResourceName("A1B2-C3D4-E5F6"))
    }

    @Test
    fun `safe uid with dots and underscores is kept`() {
        assertEquals("contact_v2.03-final.vcf", contactResourceName("contact_v2.03-final"))
    }

    @Test
    fun `uid with a slash falls back to a random uuid name`() {
        val name = contactResourceName("has/slash")
        assertTrue("expected uuid.vcf, got $name", uuidVcf.matches(name))
        assertNotEquals("has/slash.vcf", name)
    }

    @Test
    fun `uid with a space falls back to a random uuid name`() {
        assertTrue(uuidVcf.matches(contactResourceName("has space")))
    }

    @Test
    fun `uid with a percent falls back to a random uuid name`() {
        // A raw percent would be read as the start of a percent-escape in the path.
        assertTrue(uuidVcf.matches(contactResourceName("50%25")))
    }

    @Test
    fun `uid with reserved url characters falls back to a random uuid name`() {
        listOf("a?b", "a#b", "a:b", "a@b", "a&b", "a=b", "a+b").forEach { uid ->
            assertTrue("expected uuid.vcf for '$uid'", uuidVcf.matches(contactResourceName(uid)))
        }
    }

    @Test
    fun `blank uid falls back to a random uuid name`() {
        assertTrue(uuidVcf.matches(contactResourceName("")))
        assertTrue(uuidVcf.matches(contactResourceName("   ")))
    }

    @Test
    fun `dot and dot-dot fall back to a random uuid name`() {
        // "." and ".." are path traversal, never a valid resource segment.
        assertTrue(uuidVcf.matches(contactResourceName(".")))
        assertTrue(uuidVcf.matches(contactResourceName("..")))
    }

    @Test
    fun `over-length uid falls back to a random uuid name`() {
        assertTrue(uuidVcf.matches(contactResourceName("a".repeat(201))))
    }

    @Test
    fun `fallback names are random per call`() {
        assertNotEquals(contactResourceName("bad/uid"), contactResourceName("bad/uid"))
    }

    @Test
    fun `a synthesized UUID uid names the resource by itself`() {
        // A device-created contact has no UID, so its caller synthesizes a UUID and passes it
        // here. A bare UUID is a safe path segment, so it names the file with no random name,
        // and a re-attempt hits the same resource.
        val uid = "11111111-2222-3333-4444-555555555555"
        assertEquals("$uid.vcf", contactResourceName(uid))
        assertEquals("the same uid always yields the same name", contactResourceName(uid), contactResourceName(uid))
    }
}
