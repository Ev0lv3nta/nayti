package app.nayti.ui.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode {
    System,
    Light,
    Dark,
}

/**
 * Theme of the redesigned shell.
 *
 * Dynamic colour stays off: the alpha has to be reviewable for contrast and identity independently
 * of the firmware wallpaper. [ThemeMode] is intentionally presentation-only here; persistence is
 * owned by the settings layer.
 */
@Composable
fun NaytiTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    NaytiThemeResolved(darkTheme = darkTheme, content = content)
}

/**
 * Compatibility entry point for existing previews and tests. New presentation code should pass a
 * [ThemeMode] so the three supported choices stay explicit.
 */
@Composable
fun NaytiTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    NaytiThemeResolved(darkTheme = darkTheme, content = content)
}

@Composable
private fun NaytiThemeResolved(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NaytiDarkColors else NaytiLightColors
    val scheme = remember(colors) { colors.toMaterialScheme() }
    val typography = remember { NaytiTypeScaleDefault.toMaterialTypography() }
    val shapes = remember { NaytiShapeScaleDefault.toMaterialShapes() }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(
        LocalNaytiColors provides colors,
        LocalNaytiTypeScale provides NaytiTypeScaleDefault,
        LocalNaytiShapes provides NaytiShapeScaleDefault,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

object NaytiTheme {
    val colors: NaytiColors
        @Composable @ReadOnlyComposable get() = LocalNaytiColors.current

    val type: NaytiTypeScale
        @Composable @ReadOnlyComposable get() = LocalNaytiTypeScale.current

    val shapes: NaytiShapeScale
        @Composable @ReadOnlyComposable get() = LocalNaytiShapes.current
}
