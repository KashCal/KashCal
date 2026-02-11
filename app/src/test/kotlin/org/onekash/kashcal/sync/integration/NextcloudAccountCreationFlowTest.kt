package org.onekash.kashcal.sync.integration

import android.graphics.Color
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration test that traces the full account creation workflow
 * using real Nextcloud server but mocked database.
 *
 * This test verifies whether adding two accounts with different usernames
 * results in "Creating new account" or "Updating existing account" logs.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*NextcloudAccountCreationFlowTest*"
 */
class NextcloudAccountCreationFlowTest {

    private lateinit var discoveryService: CalDavAccountDiscoveryService
    private lateinit var clientFactory: OkHttpCalDavClientFactory
    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarRepository: CalendarRepository

    // Track accounts created in mock database
    private val accountsInDb = CopyOnWriteArrayList<Account>()
    private val calendarsInDb = CopyOnWriteArrayList<Calendar>()
    private var nextAccountId = 1L
    private var nextCalendarId = 1L

    // Track log messages to verify behavior
    private val logMessages = CopyOnWriteArrayList<String>()

    private var serverUrl: String? = null
    private var username1: String? = null
    private var password1: String? = null
    private var username2: String? = null
    private var password2: String? = null

    @Before
    fun setup() {
        loadCredentials()

        // Mock Log to capture messages
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            logMessages.add("I/$tag: $msg")
            println("I/$tag: $msg")
            0
        }
        every { Log.d(any(), any()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            logMessages.add("D/$tag: $msg")
            println("D/$tag: $msg")
            0
        }
        every { Log.w(any(), any<String>()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            logMessages.add("W/$tag: $msg")
            println("W/$tag: $msg")
            0
        }
        every { Log.e(any(), any()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            logMessages.add("E/$tag: $msg")
            println("E/$tag: $msg")
            0
        }
        every { Log.e(any(), any(), any()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            logMessages.add("E/$tag: $msg")
            println("E/$tag: $msg")
            0
        }

        // Mock Color.parseColor
        mockkStatic(Color::class)
        every { Color.parseColor(any()) } answers {
            val colorStr = firstArg<String>()
            try {
                if (colorStr.startsWith("#")) {
                    java.lang.Long.parseLong(colorStr.substring(1), 16).toInt()
                } else {
                    0xFF0000FF.toInt() // default blue
                }
            } catch (e: Exception) {
                0xFF0000FF.toInt()
            }
        }

        // Create real client factory
        clientFactory = OkHttpCalDavClientFactory()

        // Create mock repositories that simulate real database behavior
        // AccountRepository handles credential storage internally
        accountRepository = createMockAccountRepository()
        calendarRepository = createMockCalendarRepository()

        discoveryService = CalDavAccountDiscoveryService(
            clientFactory,
            accountRepository,
            calendarRepository
        )
    }

    @After
    fun teardown() {
        unmockkAll()
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

    private fun createMockAccountRepository(): AccountRepository {
        return io.mockk.mockk {
            // getAccountByProviderAndEmail - find existing account
            every {
                runBlocking { getAccountByProviderAndEmail(any(), any()) }
            } answers {
                val provider = firstArg<AccountProvider>()
                val email = secondArg<String>()
                val found = accountsInDb.find { it.provider == provider && it.email == email }
                println("  [DB] getAccountByProviderAndEmail($provider, $email) -> ${found?.id ?: "null"}")
                found
            }

            // createAccount - add new account
            val accountSlot = slot<Account>()
            every {
                runBlocking { createAccount(capture(accountSlot)) }
            } answers {
                val account = accountSlot.captured
                val newId = nextAccountId++
                val savedAccount = account.copy(id = newId)
                accountsInDb.add(savedAccount)
                println("  [DB] createAccount(Account email=${account.email}) -> id=$newId")
                newId
            }

            // updateAccount - update existing account
            every {
                runBlocking { updateAccount(capture(accountSlot)) }
            } answers {
                val account = accountSlot.captured
                val index = accountsInDb.indexOfFirst { it.id == account.id }
                if (index >= 0) {
                    accountsInDb[index] = account
                    println("  [DB] updateAccount(Account id=${account.id}, email=${account.email})")
                }
                Unit
            }

            // deleteAccount
            every {
                runBlocking { deleteAccount(any()) }
            } answers {
                val accountId = firstArg<Long>()
                accountsInDb.removeIf { it.id == accountId }
                println("  [DB] deleteAccount(accountId=$accountId)")
                Unit
            }

            // countByDisplayName
            every {
                runBlocking { countByDisplayName(any(), any()) }
            } returns 0

            // saveCredentials - always succeeds in test
            every {
                runBlocking { saveCredentials(any(), any()) }
            } returns true
        }
    }

    private fun createMockCalendarRepository(): CalendarRepository {
        return io.mockk.mockk {
            // getCalendarByUrl - find existing calendar
            every {
                runBlocking { getCalendarByUrl(any()) }
            } answers {
                val url = firstArg<String>()
                val found = calendarsInDb.find { it.caldavUrl == url }
                println("  [DB] getCalendarByUrl($url) -> ${found?.id ?: "null"}")
                found
            }

            // createCalendar - add new calendar
            val calendarSlot = slot<Calendar>()
            every {
                runBlocking { createCalendar(capture(calendarSlot)) }
            } answers {
                val calendar = calendarSlot.captured
                val newId = nextCalendarId++
                val savedCalendar = calendar.copy(id = newId)
                calendarsInDb.add(savedCalendar)
                println("  [DB] createCalendar(Calendar url=${calendar.caldavUrl}) -> id=$newId")
                newId
            }

            // updateCalendar - update existing calendar
            every {
                runBlocking { updateCalendar(capture(calendarSlot)) }
            } answers {
                val calendar = calendarSlot.captured
                val index = calendarsInDb.indexOfFirst { it.id == calendar.id }
                if (index >= 0) {
                    calendarsInDb[index] = calendar
                    println("  [DB] updateCalendar(Calendar id=${calendar.id})")
                }
                Unit
            }

            // getCalendarsForAccountOnce
            every {
                runBlocking { getCalendarsForAccountOnce(any()) }
            } answers {
                val accountId = firstArg<Long>()
                calendarsInDb.filter { it.accountId == accountId }
            }

            // deleteCalendar
            every {
                runBlocking { deleteCalendar(any()) }
            } answers {
                val calendarId = firstArg<Long>()
                calendarsInDb.removeIf { it.id == calendarId }
                println("  [DB] deleteCalendar(calendarId=$calendarId)")
                Unit
            }
        }
    }

    @Test
    fun `adding two accounts with different usernames creates two separate accounts`() = runBlocking {
        skipIfCredentialsMissing()

        println("\n" + "=".repeat(80))
        println("TEST: Adding two Nextcloud accounts with different usernames")
        println("=".repeat(80))

        // ====== ACCOUNT 1 ======
        println("\n--- Step 1a: Discover calendars for first account (username: $username1) ---")
        logMessages.clear()

        val discover1 = discoveryService.discoverCalendars(
            serverUrl = serverUrl!!,
            username = username1!!,
            password = password1!!,
            trustInsecure = false
        )

        assertTrue("First discovery should succeed", discover1 is DiscoveryResult.CalendarsFound)
        val calendarsFound1 = discover1 as DiscoveryResult.CalendarsFound
        println("  Calendars found: ${calendarsFound1.calendars.size}")
        println("  Principal: ${calendarsFound1.principalUrl}")
        println("  Calendar Home: ${calendarsFound1.calendarHomeUrl}")

        println("\n--- Step 1b: Create first account with selected calendars ---")
        logMessages.clear()

        val result1 = discoveryService.createAccountWithSelectedCalendars(
            serverUrl = calendarsFound1.serverUrl,
            username = username1!!,
            password = password1!!,
            trustInsecure = false,
            principalUrl = calendarsFound1.principalUrl,
            calendarHomeUrl = calendarsFound1.calendarHomeUrl,
            selectedCalendars = calendarsFound1.calendars,
            displayName = null  // Use default (server hostname)
        )

        println("\nResult 1: ${result1::class.simpleName}")
        when (result1) {
            is DiscoveryResult.Success -> {
                println("  Account ID: ${result1.account.id}")
                println("  Account email: ${result1.account.email}")
                println("  Account display: ${result1.account.displayName}")
                println("  Calendars: ${result1.calendars.size}")
            }
            is DiscoveryResult.Error -> {
                println("  ERROR: ${result1.message}")
            }
            else -> println("  Unexpected result type: ${result1::class.simpleName}")
        }

        // Check for "Creating new" vs "Updating existing"
        val creatingNew1 = logMessages.any { it.contains("Creating new account") }
        val updatingExisting1 = logMessages.any { it.contains("Updating existing account") }
        println("\nAccount 1 - Creating new: $creatingNew1, Updating existing: $updatingExisting1")

        // ====== ACCOUNT 2 ======
        println("\n--- Step 2a: Discover calendars for second account (username: $username2) ---")
        logMessages.clear()

        val discover2 = discoveryService.discoverCalendars(
            serverUrl = serverUrl!!,
            username = username2!!,
            password = password2!!,
            trustInsecure = false
        )

        assertTrue("Second discovery should succeed", discover2 is DiscoveryResult.CalendarsFound)
        val calendarsFound2 = discover2 as DiscoveryResult.CalendarsFound
        println("  Calendars found: ${calendarsFound2.calendars.size}")
        println("  Principal: ${calendarsFound2.principalUrl}")
        println("  Calendar Home: ${calendarsFound2.calendarHomeUrl}")

        println("\n--- Step 2b: Create second account with selected calendars ---")
        logMessages.clear()

        val result2 = discoveryService.createAccountWithSelectedCalendars(
            serverUrl = calendarsFound2.serverUrl,
            username = username2!!,
            password = password2!!,
            trustInsecure = false,
            principalUrl = calendarsFound2.principalUrl,
            calendarHomeUrl = calendarsFound2.calendarHomeUrl,
            selectedCalendars = calendarsFound2.calendars,
            displayName = null  // Use default (server hostname)
        )

        println("\nResult 2: ${result2::class.simpleName}")
        when (result2) {
            is DiscoveryResult.Success -> {
                println("  Account ID: ${result2.account.id}")
                println("  Account email: ${result2.account.email}")
                println("  Account display: ${result2.account.displayName}")
                println("  Calendars: ${result2.calendars.size}")
            }
            is DiscoveryResult.Error -> {
                println("  ERROR: ${result2.message}")
            }
            else -> println("  Unexpected result type: ${result2::class.simpleName}")
        }

        // Check for "Creating new" vs "Updating existing"
        val creatingNew2 = logMessages.any { it.contains("Creating new account") }
        val updatingExisting2 = logMessages.any { it.contains("Updating existing account") }
        println("\nAccount 2 - Creating new: $creatingNew2, Updating existing: $updatingExisting2")

        println("\n--- Final Database State ---")
        println("Accounts in DB: ${accountsInDb.size}")
        accountsInDb.forEach { account ->
            println("  id=${account.id}, email=${account.email}, displayName=${account.displayName}")
        }
        println("\nCalendars in DB: ${calendarsInDb.size}")
        calendarsInDb.forEach { calendar ->
            println("  id=${calendar.id}, accountId=${calendar.accountId}, url=${calendar.caldavUrl}")
        }

        println("\n" + "=".repeat(80))
        println("VERIFICATION")
        println("=".repeat(80))

        // Assertions
        assertTrue("First account should be created (not updated)", creatingNew1)
        assertFalse("First account should NOT be updating existing", updatingExisting1)

        assertTrue("Second account should be created (not updated)", creatingNew2)
        assertFalse("Second account should NOT be updating existing", updatingExisting2)

        assertEquals("Should have 2 accounts in database", 2, accountsInDb.size)

        val account1 = accountsInDb.find { it.email == username1 }
        val account2 = accountsInDb.find { it.email == username2 }

        assertNotNull("Account 1 should exist with email=$username1", account1)
        assertNotNull("Account 2 should exist with email=$username2", account2)
        assertNotEquals("Account IDs should be different", account1?.id, account2?.id)

        println("\n✓ Both accounts created successfully with different IDs")
        println("✓ Account 1: id=${account1?.id}, email=${account1?.email}")
        println("✓ Account 2: id=${account2?.id}, email=${account2?.email}")
    }
}
