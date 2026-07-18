package app.nayti.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ColorWhite = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = ColorWhite,
    primaryContainer = Mint,
    onPrimaryContainer = DeepForest,
    secondary = Amber,
    onSecondary = ColorWhite,
    secondaryContainer = Color(0xFFFFDCC1),
    onSecondaryContainer = Color(0xFF331200),
    tertiary = SlateBlue,
    onTertiary = ColorWhite,
    tertiaryContainer = Color(0xFFD8E2FF),
    onTertiaryContainer = Color(0xFF0C1B35),
    background = Linen,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = SoftSage,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFF78837E),
    outlineVariant = Hairline,
    error = ErrorRed,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = NightForest,
    onPrimary = DeepForest,
    primaryContainer = Color(0xFF1A514A),
    onPrimaryContainer = Color(0xFFC1F2E7),
    secondary = Color(0xFFFFB86F),
    onSecondary = Color(0xFF522300),
    secondaryContainer = Color(0xFF713600),
    onSecondaryContainer = Color(0xFFFFDCC1),
    tertiary = Color(0xFFBAC7E8),
    onTertiary = Color(0xFF24324B),
    tertiaryContainer = Color(0xFF3B4962),
    onTertiaryContainer = Color(0xFFD8E2FF),
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSoftSage,
    onSurfaceVariant = Color(0xFFB9C4BF),
    outline = Color(0xFF89948F),
    outlineVariant = NightHairline,
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun NaytiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NaytiTypography,
        shapes = NaytiShapes,
        content = content,
    )
}
