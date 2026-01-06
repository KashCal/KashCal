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
    fun `toSafeString masks password for logging`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "abcd-efgh-ijkl-mnop"
        )

        val safeString = creds.toSafeString()

        assertTrue(safeString.contains("user@icloud.com"))
        assertFalse(safeString.contains("abcd-efgh-ijkl-mnop"))
        assertTrue(safeString.contains("ab****op"))
    }

    @Test
    fun `toSafeString masks short password`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "abc"
        )

        val safeString = creds.toSafeString()

        assertTrue(safeString.contains("****"))
        assertFalse(safeString.contains("abc"))
    }

    @Test
    fun `toSafeString handles 4 character password`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "1234"
        )

        val safeString = creds.toSafeString()

        // 4 chars = take 2 + **** + take last 2 = "12****34"
        assertFalse(safeString.contains("1234"))
    }

    @Test
    fun `toSafeString handles 5 character password`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "12345"
        )

        val safeString = creds.toSafeString()

        // 5 chars > 4, so: take 2 + **** + take last 2 = "12****45"
        assertTrue(safeString.contains("12****45"))
    }

    @Test
    fun `toSafeString handles empty password`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = ""
        )

        val safeString = creds.toSafeString()

        assertTrue(safeString.contains("****"))
    }

    @Test
    fun `toSafeString handles single character password`() {
        val creds = Credentials(
            username = "user@icloud.com",
            password = "x"
        )

        val safeString = creds.toSafeString()

        assertTrue(safeString.contains("****"))
        assertFalse(safeString.contains("password=x"))
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
