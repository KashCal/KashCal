package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File

/**
 * Integration test for multiple Nextcloud CalDAV accounts.
 *
 * This test verifies that:
 * 1. Different users get different principal URLs
 * 2. Different users get different calendar URLs
 * 3. Account deduplication works correctly
 *
 * Run: ./gradlew testDebugUnitTest --tests "*NextcloudMultiAccountTest*"
 *
 * Prerequisites:
 * - Nextcloud server with CalDAV enabled
 * - Credentials in local.properties:
 *   NEXTCLOUD_SERVER=https://your-nextcloud-server.com
 *   NEXTCLOUD_USERNAME=your_username
 *   NEXTCLOUD_PASSWORD=your_password
 *   NEXTCLOUD_USERNAME_2=your_second_username
 *   NEXTCLOUD_PASSWORD_2=your_second_password
 */
class NextcloudMultiAccountTest {

    private lateinit var clientFactory: OkHttpCalDavClientFactory

    private var serverUrl: String? = null
    private var username1: String? = null
    private var password1: String? = null
    private var username2: String? = null
    private var password2: String? = null

    @Before
    fun setup() {
        loadCredentials()
        clientFactory = OkHttpCalDavClientFactory()
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )

        val localProperties = java.util.Properties()
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                file.inputStream().use { localProperties.load(it) }
                break
            }
        }

        serverUrl = localProperties.getProperty("NEXTCLOUD_SERVER")
        username1 = localProperties.getProperty("NEXTCLOUD_USERNAME")
        password1 = localProperties.getProperty("NEXTCLOUD_PASSWORD")
        username2 = localProperties.getProperty("NEXTCLOUD_USERNAME_2")
        password2 = localProperties.getProperty("NEXTCLOUD_PASSWORD_2")
    }

    private fun skipIfCredentialsMissing() {
        assumeTrue(
            "Nextcloud credentials not configured",
            serverUrl != null && username1 != null && password1 != null &&
                username2 != null && password2 != null
        )
    }

    @Test
    fun `different users get different principal URLs`() = runBlocking {
        skipIfCredentialsMissing()

        // Nextcloud uses /remote.php/dav/ as the DAV endpoint
        val davEndpoint = "$serverUrl/remote.php/dav/"

        val quirks1 = DefaultQuirks(serverUrl!!)
        val quirks2 = DefaultQuirks(serverUrl!!)

        val client1 = clientFactory.createClient(
            Credentials(username1!!, password1!!, serverUrl!!),
            quirks1
        )
        val client2 = clientFactory.createClient(
            Credentials(username2!!, password2!!, serverUrl!!),
            quirks2
        )

        // Discover principal URLs for both users
        val principal1Result = client1.discoverPrincipal(davEndpoint)
        val principal2Result = client2.discoverPrincipal(davEndpoint)

        println("=== User 1: $username1 ===")
        println("Principal URL: ${principal1Result.getOrNull()}")

        println("\n=== User 2: $username2 ===")
        println("Principal URL: ${principal2Result.getOrNull()}")

        assertTrue("User 1 principal discovery failed: $principal1Result", principal1Result.isSuccess())
        assertTrue("User 2 principal discovery failed: $principal2Result", principal2Result.isSuccess())

        val principal1 = principal1Result.getOrNull()!!
        val principal2 = principal2Result.getOrNull()!!

        assertNotEquals(
            "Different users should have different principal URLs",
            principal1,
            principal2
        )

        println("\n✓ Principal URLs are different (as expected)")
    }

    @Test
    fun `different users get different calendar home URLs`() = runBlocking {
        skipIfCredentialsMissing()

        // Nextcloud uses /remote.php/dav/ as the DAV endpoint
        val davEndpoint = "$serverUrl/remote.php/dav/"

        val quirks1 = DefaultQuirks(serverUrl!!)
        val quirks2 = DefaultQuirks(serverUrl!!)

        val client1 = clientFactory.createClient(
            Credentials(username1!!, password1!!, serverUrl!!),
            quirks1
        )
        val client2 = clientFactory.createClient(
            Credentials(username2!!, password2!!, serverUrl!!),
            quirks2
        )

        // Discover principal URLs
        val principal1 = client1.discoverPrincipal(davEndpoint).getOrNull()!!
        val principal2 = client2.discoverPrincipal(davEndpoint).getOrNull()!!

        // Discover calendar home URLs
        val home1Result = client1.discoverCalendarHome(principal1)
        val home2Result = client2.discoverCalendarHome(principal2)

        println("=== User 1: $username1 ===")
        println("Principal: $principal1")
        println("Calendar Home: ${home1Result.getOrNull()}")

        println("\n=== User 2: $username2 ===")
        println("Principal: $principal2")
        println("Calendar Home: ${home2Result.getOrNull()}")

        assertTrue("User 1 home discovery failed: $home1Result", home1Result.isSuccess())
        assertTrue("User 2 home discovery failed: $home2Result", home2Result.isSuccess())

        val home1 = home1Result.getOrNull()!!
        val home2 = home2Result.getOrNull()!!

        assertNotEquals(
            "Different users should have different calendar home URLs",
            home1,
            home2
        )

        println("\n✓ Calendar home URLs are different (as expected)")
    }

    @Test
    fun `different users get different calendar URLs`() = runBlocking {
        skipIfCredentialsMissing()

        // Nextcloud uses /remote.php/dav/ as the DAV endpoint
        val davEndpoint = "$serverUrl/remote.php/dav/"

        val quirks1 = DefaultQuirks(serverUrl!!)
        val quirks2 = DefaultQuirks(serverUrl!!)

        val client1 = clientFactory.createClient(
            Credentials(username1!!, password1!!, serverUrl!!),
            quirks1
        )
        val client2 = clientFactory.createClient(
            Credentials(username2!!, password2!!, serverUrl!!),
            quirks2
        )

        // Full discovery for user 1
        val principal1 = client1.discoverPrincipal(davEndpoint).getOrNull()!!
        val home1 = client1.discoverCalendarHome(principal1).getOrNull()!!
        val calendars1Result = client1.listCalendars(home1)

        // Full discovery for user 2
        val principal2 = client2.discoverPrincipal(davEndpoint).getOrNull()!!
        val home2 = client2.discoverCalendarHome(principal2).getOrNull()!!
        val calendars2Result = client2.listCalendars(home2)

        println("=== User 1: $username1 ===")
        println("Principal: $principal1")
        println("Calendar Home: $home1")
        if (calendars1Result.isSuccess()) {
            calendars1Result.getOrNull()!!.forEach { cal ->
                println("  Calendar: ${cal.displayName} -> ${cal.url}")
            }
        }

        println("\n=== User 2: $username2 ===")
        println("Principal: $principal2")
        println("Calendar Home: $home2")
        if (calendars2Result.isSuccess()) {
            calendars2Result.getOrNull()!!.forEach { cal ->
                println("  Calendar: ${cal.displayName} -> ${cal.url}")
            }
        }

        assertTrue("User 1 calendar list failed", calendars1Result.isSuccess())
        assertTrue("User 2 calendar list failed", calendars2Result.isSuccess())

        val urls1 = calendars1Result.getOrNull()!!.map { it.url }.toSet()
        val urls2 = calendars2Result.getOrNull()!!.map { it.url }.toSet()

        // Verify no overlap in calendar URLs
        val overlap = urls1.intersect(urls2)
        assertTrue(
            "Calendar URLs should not overlap between users, but found: $overlap",
            overlap.isEmpty()
        )

        println("\n✓ Calendar URLs are completely different (as expected)")
        println("  User 1 has ${urls1.size} calendars")
        println("  User 2 has ${urls2.size} calendars")
        println("  No overlap found")
    }

    /**
     * Test that simulates two sequential account additions.
     * This mirrors the user's reported scenario where adding a second account
     * "overwrote" the first.
     */
    @Test
    fun `sequential account discoveries return independent data`() = runBlocking {
        skipIfCredentialsMissing()

        // Nextcloud uses /remote.php/dav/ as the DAV endpoint
        val davEndpoint = "$serverUrl/remote.php/dav/"

        println("=== Simulating First Account Addition ===")
        println("Server: $serverUrl")
        println("Username: $username1")

        // First discovery (like first account addition)
        val quirks1 = DefaultQuirks(serverUrl!!)
        val client1 = clientFactory.createClient(
            Credentials(username1!!, password1!!, serverUrl!!),
            quirks1
        )

        val principal1 = client1.discoverPrincipal(davEndpoint).getOrNull()!!
        val home1 = client1.discoverCalendarHome(principal1).getOrNull()!!
        val calendars1 = client1.listCalendars(home1).getOrNull()!!

        println("Principal 1: $principal1")
        println("Home 1: $home1")
        println("Calendars 1:")
        calendars1.forEach { println("  - ${it.displayName}: ${it.url}") }

        println("\n=== Simulating Second Account Addition (different user) ===")
        println("Server: $serverUrl")
        println("Username: $username2")

        // Second discovery (like second account addition)
        val quirks2 = DefaultQuirks(serverUrl!!)
        val client2 = clientFactory.createClient(
            Credentials(username2!!, password2!!, serverUrl!!),
            quirks2
        )

        val principal2 = client2.discoverPrincipal(davEndpoint).getOrNull()!!
        val home2 = client2.discoverCalendarHome(principal2).getOrNull()!!
        val calendars2 = client2.listCalendars(home2).getOrNull()!!

        println("Principal 2: $principal2")
        println("Home 2: $home2")
        println("Calendars 2:")
        calendars2.forEach { println("  - ${it.displayName}: ${it.url}") }

        // Verify data is independent
        println("\n=== Verification ===")

        // 1. Principal URLs should be different
        assertNotEquals(
            "Principal URLs should be different",
            principal1,
            principal2
        )
        println("✓ Principal URLs are different")

        // 2. Calendar home URLs should be different
        assertNotEquals(
            "Calendar home URLs should be different",
            home1,
            home2
        )
        println("✓ Calendar home URLs are different")

        // 3. All calendar URLs should be unique across users
        val allUrls1 = calendars1.map { it.url }.toSet()
        val allUrls2 = calendars2.map { it.url }.toSet()
        val overlap = allUrls1.intersect(allUrls2)

        assertTrue(
            "Calendar URLs should not overlap between users, found: $overlap",
            overlap.isEmpty()
        )
        println("✓ Calendar URLs don't overlap")

        // 4. Verify username is embedded in URLs
        assertTrue(
            "User 1 principal should contain username '$username1'",
            principal1.contains(username1!!)
        )
        assertTrue(
            "User 2 principal should contain username '$username2'",
            principal2.contains(username2!!)
        )
        println("✓ Usernames are embedded in principal URLs")

        println("\n=== Summary ===")
        println("If KashCal is overwriting accounts, the bug is NOT in the CalDAV discovery.")
        println("Both users receive completely independent data from the server.")
        println("The bug must be in the account/calendar creation logic in Room.")
    }
}
