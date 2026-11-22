package org.onekash.kashcal.domain.diagnostics

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.session.SyncSession
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DebugReportExporterTest {

    private lateinit var context: Context
    private lateinit var database: KashCalDatabase
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var syncSessionStore: SyncSessionStore
    private lateinit var workManager: WorkManager
    private lateinit var platform: FakeDebugReportPlatform
    private lateinit var exporter: DebugReportExporter

    private val now = Instant.parse("2026-10-22T14:03:00Z")
    private val sharedDir get() = File(context.cacheDir, "shared")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "debug_report_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        File(context.filesDir, "sync_sessions.json").delete()
        syncSessionStore = SyncSessionStore(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        workManager = WorkManager.getInstance(context)
        platform = FakeDebugReportPlatform()
        sharedDir.deleteRecursively()
        exporter = DebugReportExporter(
            context = context,
            database = database,
            dataStore = dataStore,
            syncSessionStore = syncSessionStore,
            workManager = workManager,
            platform = platform,
            ioDispatcher = Dispatchers.IO,
            zone = { ZoneOffset.UTC },
            clock = { now },
            // File URIs: a FileProvider lookup depends on test order (see
            // [org.onekash.kashcal.testutil.resetFileProviderCache]).
            contentUri = { Uri.fromFile(it) },
        )
    }

    @After
    fun tearDown() {
        database.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
        sharedDir.deleteRecursively()
    }

    private fun exportText(): String = runBlocking {
        exporter.export().getOrThrow()
        reportFiles().single().readText()
    }

    private fun reportFiles(): List<File> =
        sharedDir.listFiles()?.filter { it.name.startsWith("kashcal-debug-report-") }.orEmpty()

    private fun session(
        calendarName: String = "Work",
        errorMessage: String? = null,
        warnings: List<String>? = null,
    ) = SyncSession(
        // The store drops sessions older than 48 hours by the system clock, not the report's.
        timestamp = System.currentTimeMillis() - 60_000,
        calendarId = 1,
        calendarName = calendarName,
        syncType = SyncType.INCREMENTAL,
        triggerSource = SyncTrigger.BACKGROUND_PERIODIC,
        durationMs = 2_140,
        hrefsReported = 3,
        eventsFetched = 3,
        eventsWritten = 0,
        eventsUpdated = 0,
        eventsDeleted = 0,
        errorMessage = errorMessage,
        warnings = warnings,
    )

    private fun section(text: String, header: String): String {
        val start = text.indexOf("== $header ==")
        assertTrue("missing section $header", start >= 0)
        val next = text.indexOf("\n== ", start + 1)
        return if (next < 0) text.substring(start) else text.substring(start, next)
    }

    // ==================== File ====================

    @Test
    fun `export writes one report under cache shared and returns its URI`() = runBlocking {
        val uri = exporter.export().getOrThrow()

        val file = reportFiles().single()
        assertEquals("kashcal-debug-report-20261022-1403.txt", file.name)
        assertEquals(Uri.fromFile(file), uri)
    }

    @Test
    fun `export deletes earlier reports and leaves ICS exports alone`() = runBlocking {
        sharedDir.mkdirs()
        File(sharedDir, "kashcal-debug-report-20200101-0000.txt").writeText("old")
        val ics = File(sharedDir, "Work_20200101.ics").apply { writeText("BEGIN:VCALENDAR") }

        exporter.export().getOrThrow()
        exporter.export().getOrThrow()

        assertEquals(listOf("kashcal-debug-report-20261022-1403.txt"), reportFiles().map { it.name })
        assertTrue(ics.exists())
    }

    // ==================== Sections ====================

    @Test
    fun `sections appear in order`() {
        val text = exportText()
        val headers = listOf(
            "KashCal debug report",
            "== Why the app closed recently ==",
            "== Setup ==",
            "== Waiting changes ==",
            "== Sync history ==",
            "== App log ==",
            "== Crash log ==",
        )
        val positions = headers.map { text.indexOf(it) }
        assertTrue("positions $positions", positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(text.contains("Created   2026-10-22 14:03:00 +00:00"))
    }

    @Test
    fun `exit records list time, reason, status, description and ANR trace`() {
        platform.exits = listOf(
            ExitRecord(now.toEpochMilli() - 300_000, "CRASH", 4, "crash", null),
            ExitRecord(now.toEpochMilli() - 600_000, "ANR", 6, "user request after error", "\"main\" prio=5 Blocked"),
        )

        val exits = section(exportText(), "Why the app closed recently")

        assertTrue(exits, exits.contains("2026-10-22 13:58:00 +00:00  CRASH  status 4  crash"))
        assertTrue(exits, exits.contains("2026-10-22 13:53:00 +00:00  ANR  status 6  user request after error"))
        assertTrue(exits, exits.contains("    \"main\" prio=5 Blocked"))
    }

    @Test
    fun `setup lists accounts, calendars, settings, permissions, power, work and database`() = runBlocking {
        val accountId = database.accountsDao().insert(
            Account(
                provider = AccountProvider.ICLOUD,
                email = "jane.doe@icloud.com",
                lastSyncAt = Instant.parse("2026-10-22T13:58:00Z").toEpochMilli(),
                lastSuccessfulSyncAt = Instant.parse("2026-10-22T13:40:00Z").toEpochMilli(),
                consecutiveSyncFailures = 2,
            )
        )
        database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "a@example.test"))
        database.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "https://x/1/", displayName = "Work", color = 0))
        database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://x/2/", displayName = "Old", color = 0, isVisible = false, isReadOnly = true)
        )
        dataStore.setSyncIntervalMinutes(30)
        dataStore.setSyncPastDays(90)
        dataStore.setDeviceCalendarsEnabled(true)
        platform.permissions = listOf(PermissionState("READ_CALENDAR", true), PermissionState("POST_NOTIFICATIONS", false))
        platform.power = PowerState(
            canScheduleExactAlarms = false,
            ignoringBatteryOptimizations = true,
            standbyBucket = "RARE",
            backgroundRestricted = true,
        )
        workManager.enqueue(OneTimeWorkRequestBuilder<NoopWorker>().addTag("debug-report-test").build()).result.get()

        val setup = section(exportText(), "Setup")

        listOf(
            "Accounts: CALDAV 1, ICLOUD 1",
            "Account $accountId ICLOUD: last sync 2026-10-22 13:58:00 +00:00, last success 2026-10-22 13:40:00 +00:00, failures 2",
            "Calendars: 2 (1 hidden, 1 read-only)",
            "ICS subscriptions: 0",
            "Device calendars: on, 0 enabled",
            "Sync: every 30 min, lookback 90 days",
            "Permissions: READ_CALENDAR granted, POST_NOTIFICATIONS denied",
            "Exact alarms: not allowed",
            "Battery optimisation: exempt",
            "Standby bucket: RARE",
            "Background restricted: yes",
            "Pending reminders: 0",
            "debug-report-test",
            "Database: version ${TestDatabases.exportedSchemaVersion};",
            "accounts 2",
            "calendars 2",
            "ics 0 events / 0 parts",
        ).forEach { assertTrue("missing '$it' in\n$setup", setup.contains(it)) }
    }

    @Test
    fun `waiting changes count by status and show each stuck change with its event`() = runBlocking {
        val accountId = database.accountsDao().insert(Account(provider = AccountProvider.ICLOUD, email = "a@example.test"))
        val calendarId = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "https://x/1/", displayName = "Work", color = 0)
        )
        val eventId = database.eventsDao().insert(
            Event(
                uid = "uid-dentist",
                calendarId = calendarId,
                title = "Dentist appointment",
                startTs = now.toEpochMilli(),
                endTs = now.toEpochMilli() + 3_600_000,
                dtstamp = now.toEpochMilli(),
            )
        )
        val untitledId = database.eventsDao().insert(
            Event(
                uid = "uid-untitled",
                calendarId = calendarId,
                title = "  ",
                startTs = now.toEpochMilli(),
                endTs = now.toEpochMilli() + 3_600_000,
                dtstamp = now.toEpochMilli(),
            )
        )
        val dao = database.pendingOperationsDao()
        dao.insert(PendingOperation(eventId = eventId, operation = PendingOperation.OPERATION_CREATE))
        dao.insert(PendingOperation(eventId = eventId, operation = PendingOperation.OPERATION_CREATE))
        val failedId = dao.insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_UPDATE,
                status = PendingOperation.STATUS_FAILED,
                retryCount = 3,
                lastError = "HTTP 500 from server",
                createdAt = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli(),
                failedAt = Instant.parse("2026-10-22T13:58:00Z").toEpochMilli(),
            )
        )
        dao.insert(
            PendingOperation(eventId = untitledId, operation = PendingOperation.OPERATION_UPDATE, status = PendingOperation.STATUS_ABANDONED)
        )
        dao.insert(
            PendingOperation(
                eventId = 999,
                operation = PendingOperation.OPERATION_DELETE,
                status = PendingOperation.STATUS_ABANDONED,
                lastError = "first line\nsecond line",
            )
        )
        dao.insert(
            PendingOperation(
                eventId = 997,
                operation = PendingOperation.OPERATION_DELETE,
                status = PendingOperation.STATUS_FAILED,
                targetUrl = "https://x/1/moved.ics",
                sourceCalendarId = calendarId,
            )
        )
        dao.insert(
            PendingOperation(
                eventId = 998,
                operation = PendingOperation.OPERATION_DELETE,
                status = PendingOperation.STATUS_ABANDONED,
                lastError = "  ",
            )
        )

        val waiting = section(exportText(), "Waiting changes")

        assertTrue(waiting, waiting.contains("ABANDONED 3, FAILED 2, PENDING 2"))
        assertTrue(waiting, waiting.contains(
            "#$failedId  UPDATE  FAILED  retries 3  queued 2026-09-01 10:00:00 +00:00  failed 2026-10-22 13:58:00 +00:00\n" +
                "    event $eventId  \"Dentist appointment\"  in Work\n" +
                "    HTTP 500 from server"
        ))
        assertTrue(waiting, waiting.contains("    event $untitledId  (no title)  in Work"))
        assertTrue(waiting, waiting.contains("    event missing (id 999)\n    first line\n    second line"))
        // A self-contained DELETE outlives its row on purpose.
        assertTrue(waiting, waiting.contains("    event removed locally, server delete queued (id 997)"))
        // A blank error adds no empty line.
        assertFalse(waiting, waiting.lines().any { it.isNotEmpty() && it.isBlank() })
        assertFalse(waiting, waiting.contains("CREATE  PENDING"))
    }

    @Test
    fun `sync history writes one parseable JSON line per session`() = runBlocking {
        syncSessionStore.add(session(calendarName = "Home"))
        syncSessionStore.add(session(calendarName = "Work"))

        val lines = section(exportText(), "Sync history").lines().filter { it.startsWith("{") }

        assertEquals(2, lines.size)
        val newest = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("Work", newest["calendarName"]!!.jsonPrimitive.content)
        assertEquals("3", newest["hrefsReported"]!!.jsonPrimitive.content)
        // Fields still at their defaults are written too.
        assertEquals("true", newest["tokenAdvanced"]!!.jsonPrimitive.content)
        assertEquals("0", newest["skippedParseError"]!!.jsonPrimitive.content)
        assertEquals("Home", Json.parseToJsonElement(lines[1]).jsonObject["calendarName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `masked session fields keep each sync history line valid JSON`() = runBlocking {
        syncSessionStore.add(
            session(
                calendarName = "Work",
                errorMessage = "Server rejected sync token:",
                warnings = listOf("token=abc123def456ghi789jkl012", "push failed for jane.doe@icloud.com"),
            )
        )

        val line = section(exportText(), "Sync history").lines().single { it.startsWith("{") }

        val session = Json.parseToJsonElement(line).jsonObject
        assertEquals("Work", session["calendarName"]!!.jsonPrimitive.content)
        assertEquals("false", session["truncated"]!!.jsonPrimitive.content)
        assertFalse(line, line.contains("abc123"))
        assertFalse(line, line.contains("jane.doe"))
    }

    @Test
    fun `an empty sync history says none`() {
        assertTrue(section(exportText(), "Sync history").contains("none"))
    }

    @Test
    fun `app log keeps the newest 10000 lines`() {
        platform.appLogLines = (1..12_000).map { "line $it" }

        val log = section(exportText(), "App log").lines()

        assertFalse(log.contains("line 2000"))
        assertTrue(log.contains("line 2001"))
        assertTrue(log.contains("line 12000"))
        assertEquals(10_000, log.count { it.startsWith("line ") })
    }

    @Test
    fun `logs are read with the capped app-log and crash-buffer arguments`() {
        platform.appLogLines = listOf("10-22 13:58:09.112  8121  8160 I CalDavSyncWorker: started")
        platform.crashLogLines = listOf("10-22 13:58:11.020  8121  8121 E AndroidRuntime: FATAL EXCEPTION: main")

        val text = exportText()

        assertEquals(listOf("-d", "-t", "10000", "-b", "main,system", "-v", "threadtime"), platform.logCalls[0])
        assertEquals(listOf("-d", "-b", "crash", "-v", "threadtime"), platform.logCalls[1])
        assertTrue(section(text, "App log").contains("CalDavSyncWorker: started"))
        assertTrue(section(text, "Crash log").contains("FATAL EXCEPTION: main"))
    }

    // ==================== Masking ====================

    @Test
    fun `no seeded secret reaches the file while titles and contact names stay`() = runBlocking {
        val email = "jane.doe@icloud.com"
        val urlHost = "p42-caldav.icloud.com"
        val bareHost = "cal.example.lan"
        val ip = "192.168.7.9"
        val uuid = "6f1cb0a2-3c4d-4e5f-8a9b-0c1d2e3fc0d1"
        val token = "HwoQEgwAAAk4dXxz8u4Bq3cAGAEiFggCEN"
        val dsid = "987654321"
        database.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = email, principalUrl = "https://$urlHost/$dsid/principal/")
        )
        syncSessionStore.add(
            session(
                errorMessage = "PUT https://$urlHost/$dsid/calendars/home/$uuid.ics failed",
                warnings = listOf("Server kept its copy of Dentist appointment; the cancellation wasn't applied (HTTP 412)", "token=$token"),
            )
        )
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = 1,
                operation = PendingOperation.OPERATION_UPDATE,
                status = PendingOperation.STATUS_FAILED,
                lastError = "connect to $ip failed for $email",
            )
        )
        platform.exits = listOf(
            ExitRecord(now.toEpochMilli(), "ANR", 6, "Input dispatching timed out ($bareHost)", "held by sync to $bareHost $token")
        )
        platform.appLogLines = listOf(
            "I PullStrategy: Deletion href matched no local event: /$dsid/calendars/home/x.ics",
            "W CalDavClient: Unable to resolve host \"$bareHost\"",
            "D BaseContactEventRepository: birthday for Priya Raman",
            "D Sync: syncToken=$token",
        )
        platform.crashLogLines = listOf("E AndroidRuntime: java.io.IOException: $email at $ip")

        val text = exportText()

        listOf(email, urlHost, bareHost, ip, uuid, token, dsid).forEach { secret ->
            assertFalse("'$secret' leaked", text.contains(secret))
        }
        assertTrue(text.contains("Dentist appointment"))
        assertTrue(text.contains("Priya Raman"))
    }

    // ==================== Failures ====================

    @Test
    fun `a failing section reads unavailable and later sections are still written`() {
        platform.exitFailure = IllegalStateException("boom for jane.doe@icloud.com")
        platform.appLogLines = listOf("I App: still here")

        val text = exportText()

        assertTrue(section(text, "Why the app closed recently").contains("unavailable (IllegalStateException)"))
        assertFalse(text.contains("jane.doe"))
        assertTrue(section(text, "App log").contains("still here"))
        assertTrue(text.contains("== Crash log =="))
    }

    @Test
    fun `cancelling mid-section leaves no report file`() = runBlocking {
        platform.appLogHangs = true
        val job = launch(Dispatchers.IO) { exporter.export() }
        withTimeout(10_000) { while (platform.logCalls.isEmpty()) delay(10) }

        job.cancelAndJoin()

        assertEquals(emptyList<File>(), reportFiles())
    }

    @Test
    fun `a second export waits for the first`() = runBlocking {
        platform.appLogHangs = true
        val first = launch(Dispatchers.IO) { exporter.export() }
        withTimeout(10_000) { while (platform.logCalls.isEmpty()) delay(10) }
        val second = async(Dispatchers.IO) { exporter.export() }
        delay(300)
        assertEquals(1, platform.logCalls.size)

        platform.appLogHangs = false
        first.cancelAndJoin()

        assertTrue(withTimeout(10_000) { second.await() }.isSuccess)
        assertEquals(1, reportFiles().size)
    }
}

/** A worker that succeeds at once; it gives the report one WorkManager entry to list. */
class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
