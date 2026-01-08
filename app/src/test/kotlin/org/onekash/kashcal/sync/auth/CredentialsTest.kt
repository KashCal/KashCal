package org.onekash.kashcal.sync.auth

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Credentials data class.
 *
 * Tests:
 * - Construction
 * - Default values
 * - Password masking for safe logging
 */
class CredentialsTest {

    // ==================== Construction Tests ====================

    @Test
    fun `minimal construction with required fields`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "xxxx-xxxx-xxxx-xxxx"
        )

        assertEquals("user@icloud.com", creds.username)
        assertEquals("xxxx-xxxx-xxxx-xxxx", creds.password)
        assertEquals(Credentials.DEFAULT_ICLOUD_SERVER, creds.serverUrl)
    }

    @Test
    fun `full construction with server URL`() {
        val creds = Credentials(
            username = "user@example.com",
            password = "secret123",
            serverUrl = "https://caldav.example.com"
        )

        assertEquals("user@example.com", creds.username)
        assertEquals("secret123", creds.password)
        assertEquals("https://caldav.example.com", creds.serverUrl)
    }

    // ==================== Constants Tests ====================

    @Test
    fun `DEFAULT_ICLOUD_SERVER is correct`() {
        assertEquals("https://caldav.icloud.com", Credentials.DEFAULT_ICLOUD_SERVER)
    }

    // ==================== toSafeString Tests ====================

    @Test
    fun `toSafeString masks password completely`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "abcd-efgh-ijkl-mnop"
        )

        val safeString = creds.toSafeString()

        // Password should be completely masked - no characters visible
        assertFalse("Password should not be visible", safeString.contains("abcd"))
        assertFalse("Password should not be visible", safeString.contains("efgh"))
        assertFalse("Password should not be visible", safeString.contains("mnop"))
        assertTrue("Password should show ****", safeString.contains("password=****"))
    }

    @Test
    fun `toSafeString masks username with maskEmail`() {
        val creds = Credentials(
            username = "john.doe@icloud.com",
            password = "secret"
        )

        val safeString = creds.toSafeString()

        // Username should be masked - shows only first 3 chars + domain TLD
        assertTrue("Username should be masked", safeString.contains("joh***@***.com"))
        assertFalse("Full username should not be visible", safeString.contains("john.doe@icloud.com"))
    }

    @Test
    fun `toSafeString with short username`() {
        val creds = Credentials(
            username = "jo@example.com",
            password = "secret"
        )

        val safeString = creds.toSafeString()

        // Short usernames (2 chars) show first char + ***
        assertTrue("Short username should be masked", safeString.contains("j***@***.com"))
    }

    @Test
    fun `toSafeString with empty password`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = ""
        )

        val safeString = creds.toSafeString()

        assertTrue("Empty password should show ****", safeString.contains("password=****"))
    }

    @Test
    fun `toSafeString never reveals any password characters`() {
        // Test various password lengths - none should reveal characters
        // Use distinctive passwords that won't appear in other parts of the output
        val passwords = listOf("xyz123", "qwerty", "secretpassword", "p@ssw0rd!")

        for (password in passwords) {
            val creds = Credentials(username = "test@example.com", password = password)
            val safeString = creds.toSafeString()

            assertFalse(
                "Password '$password' should not appear in safeString",
                safeString.contains(password)
            )
            assertTrue(
                "safeString should contain password=****",
                safeString.contains("password=****")
            )
        }
    }

    @Test
    fun `toSafeString format is correct`() {
        val creds = Credentials(
            username = "john@example.com",
            password = "secret"
        )

        val safeString = creds.toSafeString()

        // Should match exact format: Credentials(username=masked, password=****)
        assertEquals(
            "Credentials(username=joh***@***.com, password=****)",
            safeString
        )
    }

    // ==================== Data Class Behavior Tests ====================

    @Test
    fun `equals and hashCode work correctly`() {
        val creds1 = Credentials(
            username = "user@icloud.com",
            password = "password"
        )
        val creds2 = Credentials(
            username = "user@icloud.com",
            password = "password"
        )
        val creds3 = Credentials(
            username = "other@icloud.com",
            password = "password"
        )

        assertEquals(creds1, creds2)
        assertEquals(creds1.hashCode(), creds2.hashCode())
        assertNotEquals(creds1, creds3)
    }

    @Test
    fun `copy allows modification`() {
        val original = Credentials(
            username = "user@icloud.com",
            password = "password"
        )

        val modified = original.copy(serverUrl = "https://new.server.com")

        assertEquals(original.username, modified.username)
        assertEquals(original.password, modified.password)
        assertEquals("https://new.server.com", modified.serverUrl)
    }
}
