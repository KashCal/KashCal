package org.onekash.kashcal.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Calls [onStop] on every ON_STOP of [lifecycleOwner]: leaving the app, opening another
 * activity over Home, or a configuration change MainActivity doesn't handle itself (a
 * rotation is handled, so it doesn't stop Home).
 */
@Composable
fun HomeStopEffect(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onStop: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, lifecycleOwner) { onStop() }
}
