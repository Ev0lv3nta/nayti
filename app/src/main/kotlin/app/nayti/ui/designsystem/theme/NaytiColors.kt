package app.nayti.ui.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour roles of the Nayti shell.
 *
 * The shell stays neutral: photographs are the only source of colour. Exactly one accent carries
 * interaction, and the three status roles ([ready], [attention], [error]) each mean one thing.
 */
@Immutable
data class NaytiColors(
    val background: Color,
    val surface: Color,
    /** Raised surface used by cards and anchored chrome. */
    val surfaceHigh: Color,
    /** Recessed field or track within a surface. */
    val surfaceLow: Color,
    /** Quiet light-facing edge of a raised surface. */
    val edgeHighlight: Color,
    /** Quiet lower edge of a raised surface. */
    val edgeShadow: Color,
    val hairline: Color,
    /** Decorative border of a filled control; identification never depends on it. */
    val outline: Color,

    /** Border that is the only affordance of a control. Kept at 3:1 against its surface. */
    val outlineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val evidenceText: Color,
    val evidenceMeaning: Color,
    val evidencePhoto: Color,
    val ready: Color,
    val attention: Color,
    val error: Color,
    val isDark: Boolean,
)

internal val NaytiLightColors = NaytiColors(
    background = Color(NaytiPalette.Paper050),
    surface = Color(NaytiPalette.Paper000),
    surfaceHigh = Color(NaytiPalette.Paper100),
    surfaceLow = Color(NaytiPalette.Paper150),
    edgeHighlight = Color(NaytiPalette.Paper000),
    edgeShadow = Color(NaytiPalette.Paper200),
    hairline = Color(NaytiPalette.Paper200),
    outline = Color(NaytiPalette.Paper300),
    outlineStrong = Color(NaytiPalette.Paper350),
    ink = Color(NaytiPalette.Paper900),
    inkMuted = Color(NaytiPalette.Paper700),
    inkFaint = Color(NaytiPalette.Paper600),
    accent = Color(NaytiPalette.AccentLight),
    onAccent = Color(NaytiPalette.Paper000),
    accentContainer = Color(NaytiPalette.AccentLightContainer),
    onAccentContainer = Color(NaytiPalette.AccentLightOnContainer),
    evidenceText = Color(NaytiPalette.EvidenceTextLight),
    evidenceMeaning = Color(NaytiPalette.EvidenceMeaningLight),
    evidencePhoto = Color(NaytiPalette.EvidencePhotoLight),
    ready = Color(NaytiPalette.ReadyLight),
    attention = Color(NaytiPalette.AttentionLight),
    error = Color(NaytiPalette.ErrorLight),
    isDark = false,
)

internal val NaytiDarkColors = NaytiColors(
    background = Color(NaytiPalette.Ink000),
    surface = Color(NaytiPalette.Ink100),
    surfaceHigh = Color(NaytiPalette.Ink150),
    surfaceLow = Color(NaytiPalette.Ink200),
    edgeHighlight = Color(NaytiPalette.Ink300),
    edgeShadow = Color(NaytiPalette.Ink000),
    hairline = Color(NaytiPalette.Ink200),
    outline = Color(NaytiPalette.Ink300),
    outlineStrong = Color(NaytiPalette.Ink350),
    ink = Color(NaytiPalette.Ink900),
    inkMuted = Color(NaytiPalette.Ink500),
    inkFaint = Color(NaytiPalette.Ink400),
    accent = Color(NaytiPalette.AccentDark),
    onAccent = Color(NaytiPalette.Paper900),
    accentContainer = Color(NaytiPalette.AccentDarkContainer),
    onAccentContainer = Color(NaytiPalette.AccentDarkOnContainer),
    evidenceText = Color(NaytiPalette.EvidenceTextDark),
    evidenceMeaning = Color(NaytiPalette.EvidenceMeaningDark),
    evidencePhoto = Color(NaytiPalette.EvidencePhotoDark),
    ready = Color(NaytiPalette.ReadyDark),
    attention = Color(NaytiPalette.AttentionDark),
    error = Color(NaytiPalette.ErrorDark),
    isDark = true,
)

/**
 * Material colour scheme derived from the Nayti roles.
 *
 * Material components still need a [ColorScheme]; deriving it here keeps a single source of truth
 * and prevents components from drifting into their own palette.
 */
internal fun NaytiColors.toMaterialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = onAccentContainer,
        background = background,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = inkMuted,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        surfaceContainerLow = background,
        surfaceContainerLowest = background,
        outline = outlineStrong,
        outlineVariant = hairline,
        error = error,
        onError = if (isDark) Color(NaytiPalette.Paper900) else Color(NaytiPalette.Paper000),
        scrim = Color(NaytiPalette.Ink000),
    )
}

val LocalNaytiColors = staticCompositionLocalOf { NaytiDarkColors }
