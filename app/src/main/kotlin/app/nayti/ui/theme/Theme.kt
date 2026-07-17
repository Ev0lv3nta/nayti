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
    background = Linen,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFF78837E),
)

private val DarkColors = darkColorScheme(
    primary = NightForest,
    onPrimary = DeepForest,
    primaryContainer = Color(0xFF1A514A),
    onPrimaryContainer = Color(0xFFC1F2E7),
    secondary = Color(0xFFFFB86F),
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    onSurfaceVariant = Color(0xFFB9C4BF),
    outline = Color(0xFF89948F),
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
        content = content,
    )
}
