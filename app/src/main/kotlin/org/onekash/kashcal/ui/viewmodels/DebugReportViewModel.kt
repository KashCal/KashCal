package org.onekash.kashcal.ui.viewmodels

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.onekash.kashcal.domain.diagnostics.DebugReportExporter
import org.onekash.kashcal.util.DebugReportShareRunner
import javax.inject.Inject

private const val TAG = "DebugReportViewModel"

/** Debug-report results the screen shows once each: the share menu or a failure snackbar. */
sealed interface DebugReportEvent {
    data class Ready(val uri: Uri) : DebugReportEvent
    data object Failed : DebugReportEvent
}

/**
 * Runs the debug-report export in the view model's scope, so a rotation or theme change while
 * the file is written doesn't cancel it. Events wait in [events] until the screen collects them.
 */
@HiltViewModel
class DebugReportViewModel @Inject constructor(
    exporter: DebugReportExporter,
) : ViewModel() {

    private val _events = Channel<DebugReportEvent>(Channel.BUFFERED)
    val events: Flow<DebugReportEvent> = _events.receiveAsFlow()

    private val runner = DebugReportShareRunner(
        export = { exporter.export() },
        onSuccess = { _events.trySend(DebugReportEvent.Ready(it)) },
        onFailure = { e ->
            Log.e(TAG, "Debug report failed: ${e.javaClass.simpleName}")
            _events.trySend(DebugReportEvent.Failed)
        },
    )

    /** Starts an export unless one is still running. */
    fun export() = runner.run(viewModelScope)
}
