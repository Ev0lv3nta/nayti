package app.nayti.ui.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing on a 4 dp grid. Photographs run edge to edge; only text surfaces are inset. */
@Immutable
object NaytiSpacing {
    val Hairline: Dp = 1.dp
    val PhotoGutter: Dp = 2.dp
    val XSmall: Dp = 4.dp
    val Small: Dp = 8.dp
    val Medium: Dp = 12.dp
    val Screen: Dp = 16.dp
    val Section: Dp = 24.dp
    val MinTouchTarget: Dp = 48.dp
}

/**
 * Motion tokens.
 *
 * Durations are short and functional. Nothing animates unless it explains a change of state, and
 * every duration collapses to zero when the system animation scale is disabled.
 */
@Immutable
object NaytiMotion {
    const val InstantMillis = 90
    const val QuickMillis = 160
    const val StandardMillis = 240
    const val DeliberateMillis = 380
}

/**
 * Translucency of the chrome that sits on top of photographs.
 *
 * The tint floor is what guarantees legibility, not the blur: text must stay readable above the
 * brightest and the darkest possible frame. Blur is an enhancement that may be switched off
 * without changing the visual language.
 */
@Immutable
object NaytiChrome {
    /**
     * Tint opacity used when the backdrop is actually blurred.
     *
     * Measured, not chosen: these are the lowest values at which primary text still clears 4.5:1
     * above both a white and a black frame. Secondary, accent and status colours do not clear it at
     * any sane opacity, which is why only primary ink is allowed on glass.
     */
    const val GlassTintAlphaDark = 0.70f
    const val GlassTintAlphaLight = 0.80f

    /** Tint opacity of the solid fallback: no blur, so the tint has to do all the work. */
    const val SolidTintAlpha = 0.92f

    val BlurRadius: Dp = 28.dp
    val BarHeight: Dp = 56.dp
    val StatusStripHeight: Dp = 44.dp
}
