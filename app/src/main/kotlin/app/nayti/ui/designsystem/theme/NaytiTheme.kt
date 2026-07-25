package app.nayti.ui.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * Theme of the redesigned shell.
 *
 * Dynamic colour stays off: the alpha has to be reviewable for contrast and identity independently
 * of the firmware wallpaper. The theme follows the system light/dark setting.
 */
@Composable
fun NaytiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NaytiDarkColors else NaytiLightColors
    val scheme = remember(colors) { colors.toMaterialScheme() }
    val typography = remember { NaytiTypeScaleDefault.toMaterialTypography() }
    val shapes = remember { NaytiShapeScaleDefault.toMaterialShapes() }
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
