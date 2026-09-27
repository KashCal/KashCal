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
 * A ViewModel has the lifetime the lock needs: it survives a configuration change (rotation
 * must not re-prompt) and dies with the process (a cold start or a return after process death
 * must lock when the feature is on).
 *
 * MainActivity supplies lifecycle edges and `SystemClock.elapsedRealtime()`; all timing policy
 * lives in [AppLockStateMachine].
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val dataStore: KashCalDataStore,
) : ViewModel() {

    private val machine = AppLockStateMachine()

    private val _lockState = MutableStateFlow(false)
    /** True when the veil covers the UI. */
    val lockState: StateFlow<Boolean> = _lockState

    /** Whether the app-lock feature is turned on (off by default). */
    val appLockEnabled: StateFlow<Boolean> = dataStore.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Persists the app-lock flag. MainActivity runs the capability and enrollment check, the
     * route to system enrollment and the authenticate-before-disable challenge, since they
     * need the Activity; this only stores the resolved value. It lives here because the
     * account hub's toggle is hosted in MainActivity, where this ViewModel is injected.
     */
    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setAppLockEnabled(enabled)
        }
    }

    /**
     * Sets the initial lock before any content frame shows; see
     * [AppLockStateMachine.onActivityCreated].
     */
    fun onActivityCreated(enabled: Boolean) {
        machine.onActivityCreated(enabled)
        publish()
    }

    /** Records that the app went to the background at [nowElapsed]. */
    fun onBackground(nowElapsed: Long) {
        machine.onBackground(nowElapsed)
    }

    /** Updates the lock on a return at [nowElapsed]; see [AppLockStateMachine.onForeground]. */
    fun onForeground(enabled: Boolean, nowElapsed: Long, suppressRelock: Boolean = false) {
        machine.onForeground(enabled, nowElapsed, suppressRelock)
        publish()
    }

    /** Reveals the UI after a successful authentication. */
    fun onUnlockSucceeded() {
        machine.onUnlockSucceeded()
        publish()
    }

    /** Keeps the UI locked after a cancelled or failed authentication. */
    fun onUnlockError() {
        machine.onUnlockCancelled()
        publish()
    }

    private fun publish() {
        _lockState.value = machine.isLocked
    }
}
