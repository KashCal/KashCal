package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.lock.AppLockStateMachine
import javax.inject.Inject

/**
 * Owns the app-lock decision for the foreground UI.
 *
 * Hoisting the lock state in a ViewModel gives the exact lifetime the feature
 * needs: it survives a configuration change (rotation must not re-prompt) but
 * dies with the process (a cold start / return after process death must lock
 * when the feature is enabled).
 *
 * The Activity supplies lifecycle edges and a monotonic elapsed-time value
 * (`SystemClock.elapsedRealtime()`); all timing policy lives in
 * [AppLockStateMachine], which is unit-tested independently.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val dataStore: KashCalDataStore,
) : ViewModel() {

    private val machine = AppLockStateMachine()

    private val _lockState = MutableStateFlow(false)
    /** True when the veil should cover the UI. */
    val lockState: StateFlow<Boolean> = _lockState

    /** Whether the app-lock feature is turned on (off by default). */
    val appLockEnabled: StateFlow<Boolean> = dataStore.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Persist the app-lock flag. The capability / enrollment check (and any
     * routing to the system enrollment flow, plus the authenticate-before-disable
     * challenge) happens at the call site, which has the Android context — the
     * ViewModel only stores the resolved value. Mirrors the former write path on
     * the settings ViewModel; lives here because the account hub (its new home)
     * is hosted where this ViewModel is injected, not the settings ViewModel.
     */
    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setAppLockEnabled(enabled)
        }
    }

    /** See [AppLockStateMachine.onActivityCreated]. Sets the no-flash initial lock. */
    fun onActivityCreated(enabled: Boolean) {
        machine.onActivityCreated(enabled)
        publish()
    }

    /** App went to background at [nowElapsed]. */
    fun onBackground(nowElapsed: Long) {
        machine.onBackground(nowElapsed)
    }

    /** App returned to foreground at [nowElapsed]. */
    fun onForeground(enabled: Boolean, nowElapsed: Long, suppressRelock: Boolean = false) {
        machine.onForeground(enabled, nowElapsed, suppressRelock)
        publish()
    }

    /** Authentication succeeded; reveal the UI. */
    fun onUnlockSucceeded() {
        machine.onUnlockSucceeded()
        publish()
    }

    /** Authentication was cancelled or failed; stay locked. */
    fun onUnlockError() {
        machine.onUnlockCancelled()
        publish()
    }

    private fun publish() {
        _lockState.value = machine.isLocked
    }
}
