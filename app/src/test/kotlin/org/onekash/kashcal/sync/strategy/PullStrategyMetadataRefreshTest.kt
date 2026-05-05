package org.onekash.kashcal.sync.strategy

import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.SyncReport
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PullStrategy.maybeRefreshMetadata] — the per-pull calendar metadata
 * refresh hook that mirrors the event-color asymmetry at PullStrategy.kt:938.
 *
 * Runs on every pull (including the ctag-unchanged NoChanges path) because
 * some servers don't bump ctag on metadata-only changes.
 *
 * Requires Robolectric because [ServerColorParser] calls android.graphics.Color
 * for hex parsing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PullStrategyMetadataRefreshTest {

    private lateinit var pullStrategy: PullStrategy

    @MockK private lateinit var database: KashCalDatabase
    @MockK private lateinit var client: CalDavClient
    @MockK private lateinit var calendarRepository: CalendarRepository
    @MockK private lateinit var eventsDao: EventsDao
    @MockK private lateinit var occurrenceGenerator: OccurrenceGenerator
    @MockK private lateinit var dataStore: KashCalDataStore

    private val quirks = ICloudQuirks()

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)

        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        coEvery { eventsDao.getMasterByUidAndCalendar(any(), any()) } returns null
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")
        coEvery { calendarRepository.updateMetadata(any(), any(), any(), any()) } just Runs

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Asymmetry: server non-null wins ==========

    @Test
    fun `server returns new color - updateMetadata called with parsed ARGB`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", color = 0xFF000000.toInt())
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", color = "#FF5733FF"))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = 0xFFFF5733.toInt(),
                displayName = null,
                isReadOnly = null
            )
        }
    }

    @Test
    fun `server returns null color - updateMetadata not invoked for color`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", color = 0xFF0000FF.toInt())
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", color = null))

        pullStrategy.pull(calendar, client = client)

        // No field differs → no write at all
        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `server returns unparseable color - updateMetadata not invoked`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", color = 0xFF0000FF.toInt())
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", color = "not-a-color"))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `server returns same color as local - updateMetadata skipped`() = runTest {
        // #FF5733 parses to 0xFFFF5733 (alpha full). Local already has same.
        val calendar = createCalendar(ctag = "ctag-123", color = 0xFFFF5733.toInt())
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", color = "#FF5733"))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) {
            calendarRepository.updateMetadata(any(), any(), any(), any())
        }
    }

    // ========== displayName asymmetry ==========

    @Test
    fun `server returns new displayName - updateMetadata called with it`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", displayName = "Old Name")
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", displayName = "New Name"))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = null,
                displayName = "New Name",
                isReadOnly = null
            )
        }
    }

    @Test
    fun `server returns same displayName - skipped`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", displayName = "Work")
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", displayName = "Work"))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `server returns null displayName - preserves local`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", displayName = "Work")
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", displayName = null))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    // ========== isReadOnly asymmetry ==========

    @Test
    fun `server returns null isReadOnly - preserves local`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", isReadOnly = false)
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", isReadOnly = null))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `server flips writable to read-only - updateMetadata called`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", isReadOnly = false)
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", isReadOnly = true))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = null,
                displayName = null,
                isReadOnly = true
            )
        }
    }

    @Test
    fun `server returns same isReadOnly as local - skipped`() = runTest {
        val calendar = createCalendar(ctag = "ctag-123", isReadOnly = true)
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", isReadOnly = true))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    // ========== All three change at once ==========

    @Test
    fun `all three fields differ - single updateMetadata with all values`() = runTest {
        val calendar = createCalendar(
            ctag = "ctag-123",
            color = 0xFF000000.toInt(),
            displayName = "Old",
            isReadOnly = false
        )
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(
                probe(
                    ctag = "ctag-123",
                    color = "#FF5733FF",
                    displayName = "New",
                    isReadOnly = true
                )
            )

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = 0xFFFF5733.toInt(),
                displayName = "New",
                isReadOnly = true
            )
        }
    }

    // ========== Ordering invariant: runs BEFORE NoChanges ==========

    @Test
    fun `ctag matches but color differs - NoChanges returned AND updateMetadata called`() = runTest {
        // This is the critical invariant: some servers don't bump ctag on
        // metadata-only changes, so the refresh must run before the early return.
        val calendar = createCalendar(
            ctag = "ctag-123",
            color = 0xFF000000.toInt()
        )
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", color = "#FF5733FF"))

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(
            "ctag unchanged → NoChanges (skip event fetching)",
            result is PullResult.NoChanges
        )
        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = 0xFFFF5733.toInt(),
                displayName = null,
                isReadOnly = null
            )
        }
    }

    // ========== localColorOverride preserved ==========

    @Test
    fun `localColorOverride is never in the updateMetadata call`() = runTest {
        // updateMetadata signature has no localColorOverride param — the column
        // is preserved by virtue of only color/displayName/isReadOnly being
        // written. This test asserts the signature contract, locking in that
        // the override column stays untouched by sync.
        val calendar = createCalendar(
            ctag = "ctag-123",
            color = 0xFF000000.toInt(),
            localColorOverride = 0xFFAA00AA.toInt()
        )
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "ctag-123", color = "#FF5733FF"))

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = 0xFFFF5733.toInt(),
                displayName = null,
                isReadOnly = null
            )
        }
        // No other write path touches the calendar. The Calendar row's
        // localColorOverride column is preserved because our write path is
        // focused per-field updates (updateColor/updateDisplayName/setReadOnly),
        // not a full-row copy.
    }

    // ========== Runs on full-sync path ==========

    @Test
    fun `metadata refresh happens on full-sync path`() = runTest {
        val calendar = createCalendar(
            ctag = null,
            syncToken = null,
            color = 0xFF000000.toInt()
        )
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "new-ctag", color = "#FF5733FF"))
        mockTwoStepFetch(calendar.caldavUrl)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns
            CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = 0xFFFF5733.toInt(),
                displayName = null,
                isReadOnly = null
            )
        }
    }

    // ========== Runs on incremental-sync path ==========

    @Test
    fun `metadata refresh happens on incremental-sync path`() = runTest {
        val calendar = createCalendar(
            ctag = "old-ctag",
            syncToken = "token",
            color = 0xFF000000.toInt()
        )
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.success(probe(ctag = "new-ctag", color = "#FF5733FF"))
        coEvery { client.syncCollection(calendar.caldavUrl, "token") } returns
            CalDavResult.success(
                SyncReport(syncToken = "new-token", changed = emptyList(), deleted = emptyList())
            )

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 1) {
            calendarRepository.updateMetadata(
                calendarId = calendar.id,
                color = 0xFFFF5733.toInt(),
                displayName = null,
                isReadOnly = null
            )
        }
    }

    // ========== Resilience: getCtag failure ==========

    @Test
    fun `getCtag returns 500 - no metadata update attempted`() = runTest {
        // Zoho-style server without ctag support. Pull falls through to
        // ctag-less path (existing behavior); metadata refresh is a no-op.
        val calendar = createCalendar(ctag = "old-ctag", syncToken = null)
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(500, "Ctag not found in response")
        mockTwoStepFetch(calendar.caldavUrl)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()

        pullStrategy.pull(calendar, client = client)

        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `getCtag returns 401 - pull aborts, no metadata update`() = runTest {
        val calendar = createCalendar(ctag = "old-ctag")
        coEvery { client.getCtag(calendar.caldavUrl) } returns
            CalDavResult.error(401, "Unauthorized")

        val result = pullStrategy.pull(calendar, client = client)

        assertTrue(result is PullResult.Error)
        assertEquals(401, (result as PullResult.Error).code)
        coVerify(exactly = 0) { calendarRepository.updateMetadata(any(), any(), any(), any()) }
    }

    // ========== helpers ==========

    private fun probe(
        ctag: String,
        displayName: String? = null,
        color: String? = null,
        isReadOnly: Boolean? = null
    ) = CalendarMetadataProbe(
        ctag = ctag,
        displayName = displayName,
        color = color,
        isReadOnly = isReadOnly
    )

    private fun createCalendar(
        id: Long = 1,
        ctag: String? = null,
        syncToken: String? = null,
        color: Int = 0xFF0000,
        displayName: String = "Test Calendar",
        isReadOnly: Boolean = false,
        localColorOverride: Int? = null
    ) = Calendar(
        id = id,
        accountId = 1,
        caldavUrl = "https://caldav.example.com/calendars/home/",
        displayName = displayName,
        color = color,
        ctag = ctag,
        syncToken = syncToken,
        isReadOnly = isReadOnly,
        localColorOverride = localColorOverride
    )

    private fun mockTwoStepFetch(calendarUrl: String) {
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(emptyList())
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns
            CalDavResult.success(emptyList())
    }
}
