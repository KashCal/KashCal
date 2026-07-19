package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.UserPreferencesRepository
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.widget.WidgetUpdateManager

/**
 * Unit tests for [AppearanceViewModel] — the small state holder that drives the
 * hub's "Make it yours" section (theme, accent color, color source). App icon is
 * handled composable-locally via AppIconUtility and is not part of this VM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dataStore: KashCalDataStore
    private lateinit var userPreferences: UserPreferencesRepository
    private lateinit var widgetUpdateManager: WidgetUpdateManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dataStore = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        widgetUpdateManager = mockk(relaxed = true)
        // Flow getters the VM exposes.
        every { dataStore.theme } returns flowOf(ThemeMode.DARK.prefValue)
        every { dataStore.accentSeed } returns flowOf(KashCalDataStore.ACCENT_SEED_DEFAULT)
        every { userPreferences.resolvedColorSource } returns flowOf(ColorSource.SEED)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = AppearanceViewModel(dataStore, userPreferences, widgetUpdateManager)

    @Test
    fun `themeMode maps the stored pref value`() = runTest {
        vm().themeMode.test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accentSeed exposes the stored seed`() = runTest {
        vm().accentSeed.test {
            assertEquals(KashCalDataStore.ACCENT_SEED_DEFAULT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `colorSource exposes the resolved source`() = runTest {
        vm().colorSource.test {
            assertEquals(ColorSource.SEED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setThemeMode persists the mode pref value`() = runTest {
        vm().setThemeMode(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { dataStore.setTheme(ThemeMode.LIGHT.prefValue) }
    }

    @Test
    fun `setAccentSeed persists seed, switches source to seed, and refreshes widgets`() = runTest {
        vm().setAccentSeed(0xFF123456.toInt())
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { dataStore.setAccentSeed(0xFF123456.toInt()) }
        coVerify { dataStore.setColorSource(ColorSource.SEED.prefValue) }
        coVerify { widgetUpdateManager.updateAllWidgetsForColorChange(any()) }
    }

    @Test
    fun `setColorSource persists source and refreshes widgets`() = runTest {
        vm().setColorSource(ColorSource.DYNAMIC)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { dataStore.setColorSource(ColorSource.DYNAMIC.prefValue) }
        coVerify { widgetUpdateManager.updateAllWidgetsForColorChange(any()) }
    }
}
