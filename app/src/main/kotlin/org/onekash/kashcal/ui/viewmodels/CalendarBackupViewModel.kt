package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.onekash.kashcal.calendarbackup.CalendarBackupController
import org.onekash.kashcal.calendarbackup.CalendarBackupSettings
import org.onekash.kashcal.calendarbackup.CalendarBackupUiModel
import org.onekash.kashcal.calendarbackup.FolderChoice
import org.onekash.kashcal.calendarbackup.FolderFailure
import javax.inject.Inject

/**
 * A step down in Copies to keep that waits for the user to confirm: [removed] copies would go, or
 * an unknown number (null) when the folder couldn't be read.
 */
data class KeepAsk(val next: Int, val removed: Int?)

/**
 * Backs the Automatic calendar backup sheet and its Settings row over [CalendarBackupController].
 *
 * A step down in Copies to keep that would delete existing copies, or whose effect can't be
 * counted, waits in [keepAsk] for [confirmKeep]; any other count change applies at once. A refused
 * folder pick shows in [pickError] until the next pick or [clearTransient].
 */
@HiltViewModel
class CalendarBackupViewModel @Inject constructor(
    private val controller: CalendarBackupController,
) : ViewModel() {

    val model: StateFlow<CalendarBackupUiModel?> = controller.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _pickError = MutableStateFlow<FolderFailure?>(null)
    val pickError: StateFlow<FolderFailure?> = _pickError.asStateFlow()

    private val _keepAsk = MutableStateFlow<KeepAsk?>(null)
    val keepAsk: StateFlow<KeepAsk?> = _keepAsk.asStateFlow()

    private val _sheetRequested = MutableStateFlow(false)

    /**
     * True when the backup failure notification asked for the sheet and the screen hasn't opened
     * it yet ([consumeSheetRequest]). A view model state, so a request made while Settings is
     * already open (onNewIntent) reaches the screen like one made at launch.
     */
    val sheetRequested: StateFlow<Boolean> = _sheetRequested.asStateFlow()

    fun requestSheet() {
        _sheetRequested.value = true
    }

    fun consumeSheetRequest() {
        _sheetRequested.value = false
    }

    /** Takes the folder the system picker returned; null means the user cancelled the picker. */
    fun onFolderPicked(tree: String?) {
        if (tree == null) return
        viewModelScope.launch {
            _pickError.value = (controller.chooseFolder(tree) as? FolderChoice.Refused)?.reason
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { controller.setEnabled(enabled) }
    }

    fun setInterval(hours: Int) {
        viewModelScope.launch { controller.setInterval(hours) }
    }

    /**
     * Asks for [next] copies: up at once; down at once only when it removes no existing copy,
     * otherwise (and when the folder can't be read to tell) through [keepAsk].
     */
    fun requestKeep(next: Int) {
        viewModelScope.launch {
            val current = controller.settings.value.keepCount
            val wanted = next.coerceIn(CalendarBackupSettings.MIN_KEEP, CalendarBackupSettings.MAX_KEEP)
            if (wanted == current) return@launch
            val removed = if (wanted < current) controller.removableCopies(wanted) else 0
            if (removed != 0) {
                _keepAsk.value = KeepAsk(wanted, removed)
            } else {
                _keepAsk.value = null
                controller.setKeep(wanted)
            }
        }
    }

    fun confirmKeep() {
        val ask = _keepAsk.value ?: return
        _keepAsk.value = null
        viewModelScope.launch { controller.setKeep(ask.next) }
    }

    fun cancelKeep() {
        _keepAsk.value = null
    }

    fun backUpNow() {
        viewModelScope.launch { controller.backUpNow() }
    }

    /** Reads again whether notifications are on, after the user may have changed them. */
    fun refreshNotifications() {
        controller.refreshNotifications()
    }

    /** Answers the notifications-off hint with Not now. */
    fun dismissNotificationsHint() {
        controller.dismissNotificationsHint()
    }

    /** Forgets a refused pick and an unanswered step-down when the sheet closes. */
    fun clearTransient() {
        _pickError.value = null
        _keepAsk.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
