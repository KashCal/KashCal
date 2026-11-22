package org.onekash.kashcal.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs one debug-report export at a time: [export], then [onSuccess] with its result or
 * [onFailure]. A [run] while an export is in flight does nothing, so repeat taps on
 * Share report don't build a second file.
 */
class DebugReportShareRunner<T : Any>(
    private val export: suspend () -> Result<T>,
    private val onSuccess: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var job: Job? = null

    /** Starts an export in [scope] unless one is still running. */
    fun run(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            export().onSuccess(onSuccess).onFailure(onFailure)
        }
    }
}
