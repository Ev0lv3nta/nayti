package app.nayti.ui.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale of the shell.
 *
 * The system grotesque is used deliberately: a bundled face would add APK weight and a licence to
 * audit without improving Cyrillic rendering. Counters and percentages use tabular figures so
 * numbers stop jittering while preparation progresses.
 */
private const val TabularFigures = "tnum"

private val Sans = FontFamily.SansSerif

@Immutable
data class NaytiTypeScale(
    val hero: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val labelL: TextStyle,
    val labelS: TextStyle,
    val numXL: TextStyle,
    val numM: TextStyle,
)

internal val NaytiTypeScaleDefault = NaytiTypeScale(
    hero = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleL = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleM = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyL = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyM = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelL = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelS = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
    ),
    numXL = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontFeatureSettings = TabularFigures,
    ),
    numM = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TabularFigures,
    ),
)

internal fun NaytiTypeScale.toMaterialTypography(): Typography = Typography(
    headlineLarge = hero,
    headlineMedium = titleL,
    titleLarge = titleL,
    titleMedium = titleM,
    bodyLarge = bodyL,
    bodyMedium = bodyM,
    labelLarge = labelL,
    labelMedium = labelL,
    labelSmall = labelS,
)

val LocalNaytiTypeScale = staticCompositionLocalOf { NaytiTypeScaleDefault }
