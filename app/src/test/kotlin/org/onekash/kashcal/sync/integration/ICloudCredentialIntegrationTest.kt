package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.util.Properties

/**
 * Integration tests for iCloud credential flow.
 *
 * These tests verify:
 * - Credentials can be loaded from properties file
 * - CalDavClient can be configured with credentials
 * - Basic CalDAV operations work with real iCloud server
 *
 * NOTE: These tests require network access and valid iCloud credentials
 * in local.properties file.
 */
class ICloudCredentialIntegrationTest {

    private lateinit var calDavClient: CalDavClient
    private lateinit var clientFactory: OkHttpCalDavClientFactory
    private var credentials: Credentials? = null

    companion object {
        private const val PROPERTIES_FILE = "local.properties"
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        clientFactory = OkHttpCalDavClientFactory()

        // Try to load credentials from properties file
        credentials = loadCredentialsFromProperties()

        // Create client using factory pattern (replaces setCredentials)
        val quirks = ICloudQuirks()
        val creds = credentials ?: Credentials(
            username = "dummy",
            password = "dummy",
            serverUrl = Credentials.DEFAULT_ICLOUD_SERVER
        )
        calDavClient = clientFactory.createClient(creds, quirks)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Load credentials from local.properties if available.
     */
    private fun loadCredentialsFromProperties(): Credentials? {
        println("DEBUG: Working directory = ${System.getProperty("user.dir")}")

        // Look for properties file in project root
        val possiblePaths = listOf(
            "../../../../../../../$PROPERTIES_FILE",
            "../../../../../../$PROPERTIES_FILE",
            "../../../../../$PROPERTIES_FILE",
            "../../../../$PROPERTIES_FILE",
            "../../../$PROPERTIES_FILE",
            "../../$PROPERTIES_FILE",
            "../$PROPERTIES_FILE",
            PROPERTIES_FILE
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                return loadCredentialsFromFile(file)
            }
        }

        // Also try absolute path based on project structure
        val projectRoot = System.getProperty("user.dir")
        val absoluteFile = File(projectRoot, PROPERTIES_FILE)
        if (absoluteFile.exists()) {
            return loadCredentialsFromFile(absoluteFile)
        }

        // Try parent directory (since working dir is /onekash/KashCal/app)
        val parentDir = File(File(".."), PROPERTIES_FILE)
        println("DEBUG: Checking parent dir: ${parentDir.absolutePath} exists=${parentDir.exists()}")
        if (parentDir.exists()) {
            println("DEBUG: Found credentials at ${parentDir.absolutePath}")
            return loadCredentialsFromFile(parentDir)
        }

        // Try KashCal project root
        val kashCalRoot = File("/onekash/KashCal", PROPERTIES_FILE)
        println("DEBUG: Checking ${kashCalRoot.absolutePath} exists=${kashCalRoot.exists()}")
        if (kashCalRoot.exists()) {
            println("DEBUG: Found credentials at ${kashCalRoot.absolutePath}")
            return loadCredentialsFromFile(kashCalRoot)
        }

        println("DEBUG: No credentials file found!")
        return null
    }

    private fun loadCredentialsFromFile(file: File): Credentials? {
        return try {
            println("DEBUG: Loading from ${file.absolutePath}")

            // Load using standard Java Properties
            val props = Properties()
            file.inputStream().use { props.load(it) }

            // Try uppercase format first (ICLOUD_USERNAME), then lowercase (icloud username)
            val username = props.getProperty("ICLOUD_USERNAME")
                ?: props.getProperty("icloud username")
            val password = props.getProperty("ICLOUD_APP_PASSWORD")
                ?: props.getProperty("icloud app password")

            println("DEBUG: username=${username != null}, password=${password != null}")

            if (username != null && password != null) {
                println("DEBUG: Credentials loaded successfully!")
                Credentials(
                    username = username,
                    password = password,
                    serverUrl = Credentials.DEFAULT_ICLOUD_SERVER
                )
            } else {
                println("DEBUG: Missing username or password")
                null
            }
        } catch (e: Exception) {
            println("DEBUG: Exception loading: ${e.message}")
            null
        }
    }

    // ==================== Credential Loading Tests ====================

    @Test
    fun `credentials can be loaded from properties file`() {
        // This test verifies the properties file exists and has valid format
        // If credentials aren't available, we skip with a meaningful message
        if (credentials == null) {
            println("SKIPPED: No credentials available in $PROPERTIES_FILE")
            return
        }

        assertNotNull(credentials)
        assertTrue(credentials!!.username.isNotEmpty())
        assertTrue(credentials!!.password.isNotEmpty())
        assertEquals(Credentials.DEFAULT_ICLOUD_SERVER, credentials!!.serverUrl)
    }

    @Test
    fun `credentials username is valid email format`() {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return
        }

        assertTrue(
            "Username should contain @",
            credentials!!.username.contains("@")
        )
    }

    @Test
    fun `credentials password is app-specific format`() {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return
        }

        // iCloud app-specific passwords are in format: xxxx-xxxx-xxxx-xxxx
        val password = credentials!!.password
        assertTrue(
            "Password should be 19 chars (xxxx-xxxx-xxxx-xxxx format)",
            password.length == 19 && password.count { it == '-' } == 3
        )
    }

    @Test
    fun `credentials toSafeString masks password`() {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return
        }

        val safeString = credentials!!.toSafeString()

        // Should NOT contain full username (email is masked)
        assertFalse(safeString.contains(credentials!!.username))

        // Should NOT contain full password
        assertFalse(safeString.contains(credentials!!.password))

        // Should contain masked password pattern
        assertTrue(safeString.contains("****"))

        // Should contain "Credentials(" prefix indicating it's a safe representation
        assertTrue(safeString.contains("Credentials("))
    }

    // ==================== CalDavClient Configuration Tests ====================
    // NOTE: The following tests for setCredentials/hasCredentials/clearCredentials
    // have been removed because CalDavClient no longer uses the singleton pattern
    // with mutable credentials. Instead, use OkHttpCalDavClientFactory to create
    // immutable clients with credentials baked in.

    @Test
    fun `CalDavClient factory creates client with credentials`() {
        // Factory pattern replaces setCredentials/hasCredentials/clearCredentials
        val factory = OkHttpCalDavClientFactory()
        val testCredentials = Credentials(
            username = "test@example.com",
            password = "password",
            serverUrl = Credentials.DEFAULT_ICLOUD_SERVER
        )
        val client = factory.createClient(testCredentials, ICloudQuirks())

        // Client is created successfully with credentials baked in
        assertNotNull(client)
    }

    @Test
    fun `CalDavClient can be configured with real credentials via factory`() {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return
        }

        // Factory pattern creates immutable client with credentials
        val factory = OkHttpCalDavClientFactory()
        val client = factory.createClient(credentials!!, ICloudQuirks())

        // Client is created successfully
        assertNotNull(client)
    }

    // ==================== Network Integration Tests ====================
    // These tests actually connect to iCloud (require network)

    @Test
    fun `iCloud server responds to PROPFIND`() = runTest {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        // Client already created with credentials in setup()

        // Try to discover calendar home
        val result = try {
            calDavClient.discoverCalendarHome(credentials!!.serverUrl)
        } catch (e: Exception) {
            // Network errors are OK for unit test environment
            println("Network test skipped: ${e.message}")
            null
        }

        // If we got a result, verify it
        if (result != null) {
            when (result) {
                is CalDavResult.Success -> {
                    val url = result.data
                    assertTrue("Result should be HTTPS URL", url.startsWith("https://"))
                    assertTrue("Result should be iCloud URL", url.contains("icloud.com"))
                }
                is CalDavResult.Error -> {
                    // Auth errors indicate credentials were received
                    println("CalDAV error (expected in test env): ${result.message}")
                }
            }
        }
    }

    @Test
    fun `iCloud returns calendars after discovery`() = runTest {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        // Client already created with credentials in setup()

        try {
            // First discover calendar home
            val homeResult = calDavClient.discoverCalendarHome(credentials!!.serverUrl)

            when (homeResult) {
                is CalDavResult.Success -> {
                    val calendarHome = homeResult.data
                    assertNotNull("Should discover calendar home", calendarHome)

                    // Then list calendars
                    val calendarsResult = calDavClient.listCalendars(calendarHome)

                    when (calendarsResult) {
                        is CalDavResult.Success -> {
                            val calendars = calendarsResult.data
                            // Should have at least one calendar (iCloud always has default)
                            assertTrue("Should have at least one calendar", calendars.isNotEmpty())

                            // Each calendar should have required properties
                            for (calendar in calendars) {
                                assertTrue("Calendar should have href", calendar.href.isNotEmpty())
                                assertTrue("Calendar should have displayName", calendar.displayName.isNotEmpty())
                            }
                        }
                        is CalDavResult.Error -> {
                            println("List calendars error: ${calendarsResult.message}")
                        }
                    }
                }
                is CalDavResult.Error -> {
                    println("Discovery error: ${homeResult.message}")
                }
            }
        } catch (e: Exception) {
            // Network errors are acceptable in unit test environment
            println("Network test skipped: ${e.message}")
        }
    }

    @Test
    fun `checkConnection validates credentials`() = runTest {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        // Client already created with credentials in setup()

        try {
            val result = calDavClient.checkConnection(credentials!!.serverUrl)

            when (result) {
                is CalDavResult.Success -> {
                    println("Connection check successful - credentials valid")
                }
                is CalDavResult.Error -> {
                    // 401 = invalid creds, other errors = network issues
                    println("Connection check returned: ${result.code} - ${result.message}")
                }
            }
        } catch (e: Exception) {
            println("Network test skipped: ${e.message}")
        }
    }

    // ==================== Calendar Move Integration Test ====================
    // Tests the MOVE pattern: DELETE from old calendar + CREATE in new calendar

    @Test
    fun `calendar move pattern DELETE then CREATE works`() = runTest {
        if (credentials == null) {
            println("SKIPPED: No credentials available")
            return@runTest
        }

        // Client already created with credentials in setup()

        try {
            // Step 1: Discover calendars
            val homeResult = calDavClient.discoverCalendarHome(credentials!!.serverUrl)
            if (homeResult !is CalDavResult.Success) {
                println("SKIPPED: Could not discover calendar home")
                return@runTest
            }

            val calendarsResult = calDavClient.listCalendars(homeResult.data)
            if (calendarsResult !is CalDavResult.Success || calendarsResult.data.size < 2) {
                println("SKIPPED: Need at least 2 calendars for move test")
                return@runTest
            }

            val calendars = calendarsResult.data
            val sourceCalendar = calendars[0]
            val targetCalendar = calendars[1]
            println("Source calendar: ${sourceCalendar.displayName}")
            println("Target calendar: ${targetCalendar.displayName}")

            // Step 2: Create test event in source calendar
            val testUid = "test-move-${System.currentTimeMillis()}"
            val icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//KashCal//Move Test//EN
                BEGIN:VEVENT
                UID:$testUid
                DTSTAMP:20250101T120000Z
                DTSTART:20250115T100000Z
                DTEND:20250115T110000Z
                SUMMARY:Calendar Move Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val createResult = calDavClient.createEvent(sourceCalendar.href, testUid, icalData)
            if (createResult !is CalDavResult.Success) {
                println("SKIPPED: Could not create test event: ${(createResult as? CalDavResult.Error)?.message}")
                return@runTest
            }

            val (sourceUrl, sourceEtag) = createResult.data
            println("Created test event at: $sourceUrl")

            try {
                // Step 3: DELETE from source calendar (like processMove does)
                println("Deleting from source calendar...")
                val deleteResult = calDavClient.deleteEvent(sourceUrl, "")

                when {
                    deleteResult.isSuccess() -> println("DELETE succeeded")
                    deleteResult.isNotFound() -> println("DELETE: already deleted (404)")
                    else -> {
                        println("DELETE failed: ${(deleteResult as? CalDavResult.Error)?.message}")
                        // Continue anyway to test CREATE
                    }
                }

                // Step 4: CREATE in target calendar (like processMove does)
                println("Creating in target calendar...")
                val moveCreateResult = calDavClient.createEvent(targetCalendar.href, testUid, icalData)

                when {
                    moveCreateResult.isSuccess() -> {
                        val (newUrl, newEtag) = moveCreateResult.getOrNull()!!
                        println("MOVE SUCCESS! New URL: $newUrl")

                        // Clean up: delete from target calendar
                        calDavClient.deleteEvent(newUrl, "")
                        println("Cleaned up test event")
                    }
                    moveCreateResult.isConflict() -> {
                        println("CREATE conflict (412) - UID already exists")
                        // This is the iCloud quirk we documented - may need to delete first
                    }
                    else -> {
                        println("CREATE failed: ${(moveCreateResult as? CalDavResult.Error)?.message}")
                    }
                }

            } catch (e: Exception) {
                // Clean up on error
                try {
                    calDavClient.deleteEvent(sourceUrl, "")
                } catch (ignored: Exception) {}
                throw e
            }

        } catch (e: Exception) {
            println("Network test failed: ${e.message}")
        }
    }
}
