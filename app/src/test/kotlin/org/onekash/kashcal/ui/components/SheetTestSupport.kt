package org.onekash.kashcal.ui.components

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.shadows.ShadowDialog

/**
 * Puts [view]'s window in touch mode, as a finger tap does on a phone. Robolectric windows start
 * outside it, and outside touch mode Compose's clearFocus hands focus straight back to the first
 * focusable field, so a test of "opening X drops the keyboard" needs this first. Call it on the
 * UI thread.
 */
internal fun enterTouchMode(view: View) {
    val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(view)
        ?: error("view is not attached to a window")
    val ensure = root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.javaPrimitiveType)
    ensure.isAccessible = true
    ensure.invoke(root, true)
}

/** Runs [enterTouchMode] on every dialog window on screen, such as a bottom sheet's. */
internal fun enterTouchModeInShownDialogs() {
    ShadowDialog.getShownDialogs().forEach { dialog -> dialog.window?.decorView?.let(::enterTouchMode) }
}

/** Counts dialog windows; each bottom sheet is one, so a sheet stacked on another adds one. */
internal fun ComposeContentTestRule.dialogCount(): Int =
    onAllNodes(isDialog()).fetchSemanticsNodes().size

/** Presses Back in the newest dialog window, which is where a system Back reaches a sheet. */
internal fun ComposeContentTestRule.backInLatestDialog() {
    runOnUiThread {
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
    }
    waitForIdle()
}

/** Presses Back in the activity, for content composed straight into it. */
internal fun AndroidComposeTestRule<*, out ComponentActivity>.activityBack() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}
