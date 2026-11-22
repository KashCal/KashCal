package org.onekash.kashcal.calendarbackup

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.onekash.kashcal.util.foldFileNameCase
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Who started a backup run. */
enum class RunKind {
    /** The periodic job. */
    PERIODIC,

    /** A run started from the Automatic calendar backup sheet, or its follow-up. */
    ONE_TIME,
}

/** What a backup run did. */
sealed interface BackupRunOutcome {
    /** Nothing ran or was recorded: backup off, no folder, or the folder changed meanwhile. */
    data object Skipped : BackupRunOutcome

    /** The run finished and its results are in the store. */
    data class Done(
        val kind: RunKind,
        /** Why the folder couldn't be used at all, or null when it could. */
        val folderProblem: FolderFailure?,
        /** Latest file names of the calendars that failed, in calendar order. */
        val failedFiles: List<String>,
    ) : BackupRunOutcome
}

/**
 * Backs up every offline calendar into the folder in [CalendarBackupStore.settings].
 *
 * For each calendar it builds the ICS text, streams the old latest file into a dated copy (when
 * more than one copy is kept and the old file isn't empty), then overwrites the latest file and
 * reads it back. Every run does this, changed or not: keeping copies is the user's call, so the
 * backup doesn't compare contents. A calendar backed up without a failure has its dated copies
 * beyond the count removed. A calendar with no events is skipped and its file left as it was.
 * One calendar failing doesn't stop the others.
 *
 * Runs never overlap. Results are recorded only for the folder a run started with: a run whose
 * folder changed or that was turned off meanwhile records nothing and raises no alert. Failures
 * go into the store, the returned outcome and the notification ([CalendarBackupAlertPolicy]),
 * never to the caller as an exception.
 */
@Singleton
class CalendarBackupRunner internal constructor(
    private val store: CalendarBackupStore,
    private val folder: BackupFolder,
    private val source: OfflineCalendarSource,
    private val alerts: CalendarBackupAlerts,
    private val now: () -> Long,
) {

    @Inject
    constructor(
        store: CalendarBackupStore,
        folder: BackupFolder,
        source: OfflineCalendarSource,
        alerts: CalendarBackupAlerts,
    ) : this(store, folder, source, alerts, System::currentTimeMillis)

    /** Runs one backup; waits while another run is going. */
    suspend fun run(kind: RunKind): BackupRunOutcome = runLock.withLock {
        val settings = store.settings.value
        val tree = settings.folderUri
        if (!settings.isActive || tree == null) return@withLock BackupRunOutcome.Skipped

        val previous = store.status.value
        val pass = try {
            backUp(tree, settings.keepCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFolderException) {
            Pass(folderProblem = e.failure, results = previous.results, failed = emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed: ${e.javaClass.simpleName}")
            Pass(folderProblem = FolderFailure.FAILED, results = previous.results, failed = emptyList())
        }

        val current = store.settings.value
        if (!current.enabled || current.folderUri != tree) return@withLock BackupRunOutcome.Skipped

        val failedRun = pass.folderProblem != null || pass.failed.isNotEmpty()
        val checkedAt = now()
        store.updateStatus {
            it.copy(
                lastCheckedAt = checkedAt,
                results = pass.results,
                folderProblem = pass.folderProblem,
                failedRuns = if (failedRun) it.failedRuns + 1 else 0,
                notifiedAlert = if (failedRun) it.notifiedAlert else null,
            )
        }
        val outcome = BackupRunOutcome.Done(kind, pass.folderProblem, pass.failed)
        applyAlert(outcome, current.folderName)
        outcome
    }

    /** Posts or removes the failure notification for [outcome] ([CalendarBackupAlertPolicy]). */
    private fun applyAlert(outcome: BackupRunOutcome.Done, folderName: String?) {
        val status = store.status.value
        when (val action = CalendarBackupAlertPolicy.decide(outcome, status.failedRuns, status.notifiedAlert, folderName)) {
            is CalendarBackupAlertPolicy.Action.Notify -> if (alerts.canPost()) {
                alerts.post(action.alert)
                store.updateStatus { it.copy(notifiedAlert = action.alert.kind) }
            }
            CalendarBackupAlertPolicy.Action.Cancel -> alerts.cancel()
            CalendarBackupAlertPolicy.Action.None -> Unit
        }
    }

    private class Pass(
        val folderProblem: FolderFailure?,
        val results: Map<Long, CalendarBackupResult>,
        val failed: List<String>,
    )

    private suspend fun backUp(tree: String, keep: Int): Pass {
        if (!folder.hasWriteAccess(tree)) throw BackupFolderException(FolderFailure.NO_ACCESS)
        if (!folder.describe(tree).canCreate) throw BackupFolderException(FolderFailure.UNSUPPORTED)

        val calendars = source.calendars()
        val bases = CalendarBackupNames.planBaseNames(
            calendars.map { BackupCalendarName(it.id, it.shownName, it.isBuiltIn) }
        )
        // Keyed by folded name: on shared storage names differing only in case are one file.
        val files = folder.list(tree).associateBy { foldFileNameCase(it.name) }.toMutableMap()
        val results = mutableMapOf<Long, CalendarBackupResult>()
        val failed = mutableListOf<String>()

        for (calendar in calendars) {
            val base = bases.getValue(calendar.id)
            val latestName = CalendarBackupNames.latestFileName(base)
            val result = try {
                backUpCalendar(tree, calendar, base, keep, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupFolderException) {
                // Lost access ends the run: every other calendar would fail the same way.
                if (e.failure == FolderFailure.NO_ACCESS) throw e
                CalendarBackupResult(CalendarBackupResult.Kind.FAILED, now(), latestName)
            } catch (e: Exception) {
                Log.w(TAG, "Backup of calendar ${calendar.id} failed: ${e.javaClass.simpleName}")
                CalendarBackupResult(CalendarBackupResult.Kind.FAILED, now(), latestName)
            }
            results[calendar.id] = result
            if (result.kind == CalendarBackupResult.Kind.FAILED) failed += latestName
        }
        return Pass(folderProblem = null, results = results, failed = failed)
    }

    /**
     * Backs up one calendar and trims its dated copies. [files] is the folder listing by folded
     * name ([foldFileNameCase]), kept current as files are created and deleted.
     */
    private suspend fun backUpCalendar(
        tree: String,
        calendar: OfflineCalendar,
        base: String,
        keep: Int,
        files: MutableMap<String, FolderFile>,
    ): CalendarBackupResult {
        val latestName = CalendarBackupNames.latestFileName(base)
        val text = source.calendarText(calendar)
        if (text == null) {
            trim(base, keep, files, justAdded = null)
            return CalendarBackupResult(CalendarBackupResult.Kind.EMPTY, now(), latestName)
        }

        val latest = files[foldFileNameCase(latestName)]

        // A cancelled run must not stop between the copy and the overwrite, or leave a file
        // half-written.
        val justAdded = withContext(NonCancellable) {
            val copyName = if (latest != null && keep > 1 && latest.sizeBytes != 0L) {
                keepOldVersion(tree, base, latest, files)
            } else {
                null
            }
            val target = latest ?: folder.create(tree, latestName).also { files[foldFileNameCase(latestName)] = it }
            folder.write(target.uri, text)
            // A provider that ignored the truncation leaves the old tail after the new text.
            val kept = folder.readLines(target.uri) { it.joinToString("\n") } ==
                text.lines().joinToString("\n").removeSuffix("\n")
            if (!kept) throw BackupFolderException(FolderFailure.FAILED)
            copyName
        }
        trim(base, keep, files, justAdded)
        return CalendarBackupResult(CalendarBackupResult.Kind.UPDATED, now(), latestName)
    }

    /**
     * Streams [latest] into a new dated copy named by its last write (now when unknown), moving to
     * the next free second when that name is taken, and returns the copy's name. A copy that fails
     * part way is deleted, so a truncated file never counts as a kept version.
     */
    private suspend fun keepOldVersion(
        tree: String,
        base: String,
        latest: FolderFile,
        files: MutableMap<String, FolderFile>,
    ): String {
        var time = latest.lastModified ?: now()
        var attempts = 0
        while (true) {
            val name = CalendarBackupNames.datedCopyName(base, Instant.ofEpochMilli(time))
            if (foldFileNameCase(name) !in files) {
                val copy = try {
                    folder.create(tree, name)
                } catch (e: BackupFolderException) {
                    if (e.failure != FolderFailure.NAME_TAKEN || ++attempts >= MAX_NAME_ATTEMPTS) throw e
                    null
                }
                if (copy != null) {
                    try {
                        folder.copyInto(latest.uri, copy.uri)
                    } catch (e: Exception) {
                        runCatching { folder.delete(copy.uri) }
                        throw e
                    }
                    files[foldFileNameCase(name)] = copy
                    return name
                }
            }
            time += 1_000
        }
    }

    /**
     * Deletes [base]'s dated copies beyond the count ([CalendarBackupNames.copiesBeyondCount]).
     * A provider that can't delete keeps them; that is not a failed backup.
     */
    private suspend fun trim(base: String, keep: Int, files: MutableMap<String, FolderFile>, justAdded: String?) {
        val deletable = files.values.filter { it.supportsDelete }.map { it.name }
        for (name in CalendarBackupNames.copiesBeyondCount(deletable, base, keep, justAdded)) {
            val key = foldFileNameCase(name)
            val file = files[key] ?: continue
            try {
                if (folder.delete(file.uri)) files.remove(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove an old copy: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "CalendarBackupRunner"
        const val MAX_NAME_ATTEMPTS = 5

        /** One lock for the process, so the periodic job and a run started now never overlap. */
        val runLock = Mutex()
    }
}
