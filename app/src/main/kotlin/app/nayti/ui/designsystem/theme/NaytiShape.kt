package app.nayti.ui.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Shape language.
 *
 * Three levels only: photographs keep an almost square corner so the frame is not eaten, content
 * containers are gently rounded, and interactive chrome is a pill. Everything being a 24 dp bubble
 * is what makes the current build read as a settings panel.
 */
@Immutable
data class NaytiShapeScale(
    val photo: RoundedCornerShape,
    val card: RoundedCornerShape,
    val sheet: RoundedCornerShape,
    val control: RoundedCornerShape,
    val badge: RoundedCornerShape,
)

internal val NaytiShapeScaleDefault = NaytiShapeScale(
    photo = RoundedCornerShape(6.dp),
    card = RoundedCornerShape(16.dp),
    sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    control = RoundedCornerShape(percent = 50),
    badge = RoundedCornerShape(8.dp),
)

internal fun NaytiShapeScale.toMaterialShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = badge,
    medium = card,
    large = card,
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalNaytiShapes = staticCompositionLocalOf { NaytiShapeScaleDefault }
