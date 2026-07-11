package org.onekash.kashcal.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import org.onekash.kashcal.data.preferences.KashCalDataStore

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun KashCalTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorSource: ColorSource = ColorSource.DYNAMIC,
    accentSeed: Int = KashCalDataStore.ACCENT_SEED_DEFAULT,
    content: @Composable () -> Unit
) {
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())

    val colorScheme = when {
        // Seed source: a full Material 3 scheme generated from the user's chosen accent color.
        // WCAG AA is guaranteed for any seed (see AccentSchemeTest).
        colorSource == ColorSource.SEED -> accentColorScheme(accentSeed, darkTheme)
        // Dynamic source: Material You (wallpaper-derived) on Android 12+, baseline otherwise.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Drive the status/navigation bar icon appearance from the app's resolved face, not the OS
    // setting — otherwise forcing Light on a dark-mode phone (or Dark on a light one) leaves the
    // system icons the wrong color and near-invisible against the app's bar. Keyed on darkTheme so
    // it only writes when the face actually flips, not on every recomposition.
    val view = LocalView.current
    val activity = LocalActivity.current
    if (!view.isInEditMode && activity != null) {
        LaunchedEffect(darkTheme, view, activity) {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
