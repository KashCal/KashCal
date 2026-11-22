package org.onekash.kashcal.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose test for [HomeStopEffect]: it reports each ON_STOP of the lifecycle it watches, and
 * nothing before that. Run the class alone: Robolectric runs of more than one class hit a native
 * crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeStopEffectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `reports each stop of the lifecycle`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var stops = 0
        composeTestRule.setContent {
            HomeStopEffect(lifecycleOwner = owner, onStop = { stops++ })
        }
        composeTestRule.waitForIdle()
        assertEquals(0, stops)

        composeTestRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeTestRule.waitForIdle()
        assertEquals(1, stops)

        composeTestRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        composeTestRule.waitForIdle()
        assertEquals(2, stops)
    }
}
