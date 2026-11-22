package org.onekash.kashcal.domain.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.room3.deferredTransaction
import androidx.room3.useReaderConnection
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.sync.session.SyncSession
import org.onekash.kashcal.sync.session.SyncSessionStore
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Writes the debug report the user shares from Settings: one plain-text file in the cache's
 * `shared/` folder, shared through the app's FileProvider. Every line goes through
 * [DebugReportMasker] before it reaches the file; a sync-history line is masked field by field
 * before it is encoded.
 *
 * The report is read when the user asks for it; nothing here runs in the background.
 */
@Singleton
class DebugReportExporter(
    private val context: Context,
    private val database: KashCalDatabase,
    private val dataStore: KashCalDataStore,
    private val syncSessionStore: SyncSessionStore,
    private val workManager: WorkManager,
    private val platform: DebugReportPlatform,
    private val ioDispatcher: CoroutineDispatcher,
    private val zone: () -> ZoneId,
    private val clock: () -> Instant,
    private val contentUri: (File) -> Uri,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: KashCalDatabase,
        dataStore: KashCalDataStore,
        syncSessionStore: SyncSessionStore,
        workManager: WorkManager,
        platform: DebugReportPlatform,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        context, database, dataStore, syncSessionStore, workManager, platform, ioDispatcher,
        { ZoneId.systemDefault() }, { Instant.now() },
        { file -> FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file) },
    )

    private val mutex = Mutex()
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.US)
    private val fileTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US)
    // Default-valued fields (tokenAdvanced, zero skip counts) are the ones a sync bug shows in.
    private val json = Json { encodeDefaults = true }

    /**
     * Replaces any earlier report with a new one and returns its content URI. A failure or a
     * cancellation deletes the partial file; a cancellation is rethrown.
     */
    suspend fun export(): Result<Uri> = mutex.withLock {
        withContext(ioDispatcher) {
            val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
            dir.listFiles()
                ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
                ?.forEach { it.delete() }
            val now = clock()
            val zoneNow = zone()
            val file = File(dir, FILE_PREFIX + fileTimeFormat.format(now.atZone(zoneNow)) + FILE_SUFFIX)
            try {
                file.bufferedWriter().use { writeReport(it, now, zoneNow) }
                Result.success(contentUri(file))
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                Result.failure(e)
            }
        }
    }

    private suspend fun writeReport(out: Writer, now: Instant, zone: ZoneId) {
        val time: (Long?) -> String = { formatTime(it, zone) }
        val writeRaw: (String) -> Unit = { out.write(it); out.write("\n") }
        val write: (String) -> Unit = { writeRaw(DebugReportMasker.mask(it)) }
        write("KashCal debug report")
        write("Created   ${time(now.toEpochMilli())}")
        write("App       ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        write("Device    ${Build.MANUFACTURER} ${Build.MODEL}")
        write("Android   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        write("Locale    ${Locale.getDefault().toLanguageTag()}")
        section(write, "Why the app closed recently") { writeExits(write, time) }
        section(write, "Setup") { writeSetup(write, time) }
        section(write, "Waiting changes") { writeWaitingChanges(write, time) }
        section(write, "Sync history") {
            // One session per line, newest first, so the history stays readable at 200 sessions.
            // Masking the text fields before encoding keeps each line valid JSON; masking the
            // encoded line would let a credential key in one field swallow the fields after it.
            val sessions = syncSessionStore.sessions.value
            if (sessions.isEmpty()) write("none")
            sessions.forEach { session -> writeRaw(json.encodeToString(session.masked())) }
        }
        section(write, "App log") { writeAppLog(write) }
        section(write, "Crash log") {
            platform.readLog(CRASH_LOG_ARGS, Int.MAX_VALUE, write)
        }
    }

    // A failing source must not cost the user the rest of the report. Only the exception class
    // is written: a message can hold values the masker doesn't recognise.
    private suspend fun section(write: (String) -> Unit, title: String, body: suspend () -> Unit) {
        write("")
        write("== $title ==")
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            write("unavailable (${e.javaClass.simpleName})")
        }
    }

    private fun writeExits(write: (String) -> Unit, time: (Long?) -> String) {
        val exits = platform.exitRecords()
        if (exits.isEmpty()) write("none recorded")
        exits.forEach { exit ->
            write("${time(exit.timestampMs)}  ${exit.reason}  status ${exit.status}  ${exit.description.orEmpty()}".trimEnd())
            exit.trace?.lineSequence()?.forEach { write("    $it") }
        }
    }

    private suspend fun writeSetup(write: (String) -> Unit, time: (Long?) -> String) {
        val accounts = database.accountsDao().getAllOnce()
        write("Accounts: " + accounts.groupingBy { it.provider.name }.eachCount().toSortedMap()
            .entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" })
        accounts.forEach { account ->
            write(
                "Account ${account.id} ${account.provider.name}: last sync ${time(account.lastSyncAt)}, " +
                    "last success ${time(account.lastSuccessfulSyncAt)}, failures ${account.consecutiveSyncFailures}"
            )
        }
        val calendars = database.calendarsDao().getAllOnce()
        write("Calendars: ${calendars.size} (${calendars.count { !it.isVisible }} hidden, ${calendars.count { it.isReadOnly }} read-only)")
        write("ICS subscriptions: ${database.icsSubscriptionsDao().getCount()}")
        val deviceOn = dataStore.deviceCalendarsEnabled.first()
        write("Device calendars: ${if (deviceOn) "on" else "off"}, ${dataStore.enabledDeviceCalendarIds.first().size} enabled")
        write("Sync: every ${dataStore.syncIntervalMinutes.first()} min, lookback ${dataStore.syncPastDays.first()} days")
        write("Permissions: " + platform.permissionStates()
            .joinToString(", ") { "${it.name} ${if (it.granted) "granted" else "denied"}" })
        val power = platform.powerState()
        write("Exact alarms: ${if (power.canScheduleExactAlarms) "allowed" else "not allowed"}")
        write("Battery optimisation: ${if (power.ignoringBatteryOptimizations) "exempt" else "applied"}")
        write("Standby bucket: ${power.standbyBucket}")
        write("Background restricted: ${if (power.backgroundRestricted) "yes" else "no"}")
        write("Pending reminders: ${database.scheduledRemindersDao().getPendingCount()}")
        val work = workManager.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.entries)).get()
        write("Work: ${work.size}")
        work.forEach { info ->
            write("  ${info.state}  attempts ${info.runAttemptCount}  ${info.tags.sorted().joinToString(" ")}")
        }
        // One read transaction, so the version and the counts come from one snapshot; outside a
        // transaction each statement could see a sync commit between them.
        val (version, counts) = database.useReaderConnection { connection ->
            connection.deferredTransaction {
                suspend fun readLong(sql: String): Long = usePrepared(sql) { if (it.step()) it.getLong(0) else 0L }
                val tables = COUNTED_TABLES.map { table -> "$table ${readLong("SELECT COUNT(*) FROM $table")}" }
                // How many events store an ICS, and in how many pieces (EventIcsStore splits them).
                val ics = "ics ${readLong("SELECT COUNT(DISTINCT event_id) FROM event_ics")} events / " +
                    "${readLong("SELECT COUNT(*) FROM event_ics")} parts"
                readLong("PRAGMA user_version") to tables + ics
            }
        }
        write("Database: version $version; ${counts.joinToString(", ")}")
    }

    private suspend fun writeWaitingChanges(write: (String) -> Unit, time: (Long?) -> String) {
        val operations = database.pendingOperationsDao().getAll()
        write(operations.groupingBy { it.status }.eachCount().toSortedMap()
            .entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" })
        val stuck = operations.filter {
            it.status == PendingOperation.STATUS_FAILED || it.status == PendingOperation.STATUS_ABANDONED
        }
        if (stuck.isEmpty()) return
        val events = database.eventsDao().getByIds(stuck.map { it.eventId }.distinct()).associateBy { it.id }
        val calendarNames = database.calendarsDao().getAllOnce().associate { it.id to it.displayName }
        stuck.forEach { op ->
            write(
                "#${op.id}  ${op.operation}  ${op.status}  retries ${op.retryCount}  " +
                    "queued ${time(op.createdAt)}  failed ${time(op.failedAt)}"
            )
            val event = events[op.eventId]
            if (event == null) {
                // A self-contained DELETE outlives its row on purpose (PendingOperationTriggers).
                val selfContained = op.operation == PendingOperation.OPERATION_DELETE &&
                    op.targetUrl != null && op.sourceCalendarId != null
                write(
                    if (selfContained) "    event removed locally, server delete queued (id ${op.eventId})"
                    else "    event missing (id ${op.eventId})"
                )
            } else {
                val title = if (event.title.isBlank()) "(no title)" else "\"${event.title.replace('\n', ' ')}\""
                write("    event ${event.id}  $title  in ${calendarNames[event.calendarId] ?: "calendar ${event.calendarId}"}")
            }
            op.lastError?.takeIf { it.isNotBlank() }?.lines()?.forEach { write("    $it") }
        }
    }

    // `-t` counts log entries, but a multi-line entry (a stack trace) prints one line per frame
    // and logcat prints oldest first. Keeping the last lines read keeps the ones nearest the
    // problem; lines read before a timeout are still written.
    private suspend fun writeAppLog(write: (String) -> Unit) {
        val tail = ArrayDeque<String>()
        try {
            platform.readLog(APP_LOG_ARGS, MAX_APP_LOG_READ_LINES) { line ->
                tail.addLast(line)
                if (tail.size > MAX_APP_LOG_LINES) tail.removeFirst()
            }
        } finally {
            tail.forEach(write)
        }
    }

    private fun SyncSession.masked(): SyncSession = copy(
        id = DebugReportMasker.mask(id),
        calendarName = DebugReportMasker.mask(calendarName),
        errorStage = errorStage?.let(DebugReportMasker::mask),
        errorMessage = errorMessage?.let(DebugReportMasker::mask),
        warnings = warnings?.map(DebugReportMasker::mask),
    )

    private fun formatTime(epochMs: Long?, zone: ZoneId): String =
        if (epochMs == null || epochMs <= 0) "-" else timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private companion object {
        const val SHARED_DIR = "shared"
        const val AUTHORITY_SUFFIX = ".fileprovider"
        const val FILE_PREFIX = "kashcal-debug-report-"
        const val FILE_SUFFIX = ".txt"
        const val MAX_APP_LOG_LINES = 10_000

        // Bounds memory if logcat ignores `-t`.
        const val MAX_APP_LOG_READ_LINES = 4 * MAX_APP_LOG_LINES
        val APP_LOG_ARGS = listOf("-d", "-t", "$MAX_APP_LOG_LINES", "-b", "main,system", "-v", "threadtime")
        val CRASH_LOG_ARGS = listOf("-d", "-b", "crash", "-v", "threadtime")
        val COUNTED_TABLES = listOf(
            "events", "occurrences", "calendars", "accounts", "pending_operations", "scheduled_reminders",
        )
    }
}
