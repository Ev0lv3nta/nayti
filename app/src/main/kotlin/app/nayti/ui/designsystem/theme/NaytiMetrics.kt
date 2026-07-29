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

/** Icon geometry stays independent from touch targets, which remain at least 48 dp. */
@Immutable
object NaytiIconSize {
    val Compact: Dp = 18.dp
    val Standard: Dp = 24.dp
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
     * These are the directional alpha values from the approved Kromka material and stay above the
     * measured contrast floor. Secondary, accent and status colours are still kept off glass
     * because they do not clear the same contrast requirement above arbitrary photographs.
     */
    const val GlassTintAlphaDarkTop = 0.90f
    const val GlassTintAlphaDarkBottom = 0.94f
    const val GlassTintAlphaLightTop = 0.92f
    const val GlassTintAlphaLightBottom = 0.95f

    /** The production fallback is opaque, so every approved text role keeps its measured contrast. */
    const val SolidTintAlpha = 1f

    val BlurRadius: Dp = 22.dp
    val BarHeight: Dp = 56.dp
    val StatusStripHeight: Dp = 44.dp
}
