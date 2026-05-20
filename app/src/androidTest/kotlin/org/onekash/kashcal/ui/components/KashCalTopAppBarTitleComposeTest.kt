package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R

/**
 * Contract test for [KashCalTopAppBarTitle] — the shared top-app-bar
 * title composable used across Home, Settings, Accounts, Subscriptions,
 * Birthdays & Anniversaries, and Device Calendars. Locks in:
 * 1. Renders [R.string.app_name] (never a screen name)
 * 2. Static variant (no onClick) has no click action
 * 3. Clickable variant invokes the supplied lambda
 *
 * Visual styling (titleLarge + 20sp) is asserted via the consuming
 * UnifiedTopBarComposeTest — checking style attributes through Compose
 * semantics is brittle, but verifying the text exists and behaves
 * correctly is enough to lock in the contract.
 */
@RunWith(AndroidJUnit4::class)
class KashCalTopAppBarTitleComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appName = context.getString(R.string.app_name)

    @Test
    fun rendersAppNameString() {
        rule.setContent {
            MaterialTheme {
                KashCalTopAppBarTitle()
            }
        }
        rule.onNodeWithText(appName).assertIsDisplayed()
    }

    @Test
    fun staticVariant_hasNoClickAction() {
        rule.setContent {
            MaterialTheme {
                KashCalTopAppBarTitle()
            }
        }
        rule.onNodeWithText(appName).assertHasNoClickAction()
    }

    @Test
    fun clickableVariant_hasClickActionAndInvokesLambda() {
        var invoked = false
        rule.setContent {
            MaterialTheme {
                KashCalTopAppBarTitle(onClick = { invoked = true })
            }
        }
        rule.onNodeWithText(appName).assertHasClickAction()
        rule.onNodeWithText(appName).performClick()
        assertTrue("onClick lambda was not invoked", invoked)
    }
}
