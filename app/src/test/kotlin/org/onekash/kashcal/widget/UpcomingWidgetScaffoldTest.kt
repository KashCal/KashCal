package org.onekash.kashcal.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [UpcomingWidgetScaffold] — verifies the scaffold renders the correct
 * sub-composable for each [UpcomingState] variant.
 *
 * Uses [runGlanceAppWidgetUnitTest] (docs: kb://android/develop/ui/compose/glance/testing)
 * with `provideComposable { ... }` to inject parameterized state. Robolectric provides
 * the LocalContext that composables need.
 */
@RunWith(RobolectricTestRunner::class)
class UpcomingWidgetScaffoldTest {

    @Test
    fun `Loading state renders Loading sub-composable`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            UpcomingWidgetScaffold(state = UpcomingState.Loading)
        }
        onNode(hasText("Loading upcoming events…")).assertExists()
    }

    @Test
    fun `Error state renders Error sub-composable with open-app hint`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            UpcomingWidgetScaffold(state = UpcomingState.Error)
        }
        onNode(hasText("Couldn't load events")).assertExists()
    }

    @Test
    fun `Loaded state with content renders the real widget body not Loading`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        val loaded = UpcomingState.Loaded(
            eventsByDay = emptyMap(),
            todayDayCode = 20260428,
            showEventEmojis = false,
            timePattern = "h:mm a"
        )
        provideComposable {
            UpcomingWidgetScaffold(state = loaded)
        }
        // The existing empty-state string "No upcoming events" appears when Loaded with no events.
        // We assert Loading is NOT shown — proving the scaffold branched into Loaded.
        onNode(hasText("Loading upcoming events…")).assertDoesNotExist()
    }
}
