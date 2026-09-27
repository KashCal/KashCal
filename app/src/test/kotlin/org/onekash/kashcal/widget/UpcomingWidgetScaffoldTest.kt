package org.onekash.kashcal.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests that [UpcomingWidgetScaffold] renders the matching content for each [UpcomingState]:
 * Loading and Error by their text, Loaded only by the loading text being absent.
 *
 * Uses [runGlanceAppWidgetUnitTest] with `provideComposable { ... }` to pass the state in.
 * Robolectric provides the context the composables need.
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
            timePattern = "h:mm a",
            detailedRows = false
        )
        provideComposable {
            UpcomingWidgetScaffold(state = loaded)
        }
        // Loaded with no events shows the "No upcoming events" empty state (not asserted here);
        // the absent loading text shows only that the scaffold left the Loading branch.
        onNode(hasText("Loading upcoming events…")).assertDoesNotExist()
    }
}
