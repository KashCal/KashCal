package org.onekash.kashcal.sync.discovery

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Unit tests for [persistCalendarUserAddresses] — RFC 6638 §2.4.1 address-set
 * discovery, persisted non-fatally.
 */
class CalendarUserAddressDiscoveryTest {

    private val client = mockk<CalDavClient>()
    private val accountRepository = mockk<AccountRepository>(relaxed = true)

    private val principalUrl = "https://dav.example.com/principals/u/"
    private val accountId = 7L

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `persists discovered addresses`() = runTest {
        coEvery { client.discoverCalendarUserAddresses(principalUrl) } returns
            CalDavResult.success(listOf("mailto:u@example.com"))

        persistCalendarUserAddresses(client, principalUrl, accountId, accountRepository, "TAG")

        coVerify { accountRepository.updateCalendarUserAddresses(accountId, listOf("mailto:u@example.com")) }
    }

    @Test
    fun `discovery error persists empty list`() = runTest {
        coEvery { client.discoverCalendarUserAddresses(principalUrl) } returns
            CalDavResult.error(404, "not found")

        persistCalendarUserAddresses(client, principalUrl, accountId, accountRepository, "TAG")

        coVerify { accountRepository.updateCalendarUserAddresses(accountId, emptyList()) }
    }

    @Test
    fun `repository write failure is swallowed and never aborts the sync`() = runTest {
        coEvery { client.discoverCalendarUserAddresses(principalUrl) } returns
            CalDavResult.success(listOf("mailto:u@example.com"))
        coEvery { accountRepository.updateCalendarUserAddresses(any(), any()) } throws
            RuntimeException("disk full")

        // Must NOT throw.
        persistCalendarUserAddresses(client, principalUrl, accountId, accountRepository, "TAG")
    }

    @Test
    fun `CancellationException propagates (not swallowed)`() = runTest {
        coEvery { client.discoverCalendarUserAddresses(principalUrl) } returns
            CalDavResult.success(listOf("mailto:u@example.com"))
        coEvery { accountRepository.updateCalendarUserAddresses(any(), any()) } throws
            CancellationException("cancelled")

        var propagated = false
        try {
            persistCalendarUserAddresses(client, principalUrl, accountId, accountRepository, "TAG")
        } catch (e: CancellationException) {
            propagated = true
        }
        if (!propagated) fail("CancellationException must propagate, not be swallowed")
    }
}
