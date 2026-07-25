package app.nayti.design

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import kotlin.math.abs
import kotlin.math.sin

/**
 * Deterministic stand-ins for photographs.
 *
 * The design review needs colourful, varied frames to judge density and the legibility of chrome
 * placed above them, but no personal photograph may end up in a design artefact. These are drawn
 * procedurally from an index, so every reviewer and every screenshot sees the same library.
 */
@Composable
fun SyntheticPhoto(index: Int, modifier: Modifier = Modifier) {
    val seed = index * 2654435761u.toInt()
    val hue = abs(seed % 360).toFloat()
    val bright = abs((seed shr 8) % 100) / 100f
    val base = hsl(hue, 0.42f + bright * 0.3f, 0.28f + bright * 0.4f)
    val far = hsl((hue + 42f) % 360f, 0.35f, 0.18f + bright * 0.25f)
    val near = hsl((hue + 320f) % 360f, 0.5f, 0.55f + bright * 0.3f)
    val horizon = 0.38f + sin(index.toFloat()) * 0.18f

    Canvas(modifier) {
        drawRect(Brush.verticalGradient(0f to far, horizon to base, 1f to base.copy(alpha = 0.92f)))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(near.copy(alpha = 0.85f), near.copy(alpha = 0f)),
                center = Offset(size.width * (0.2f + bright * 0.6f), size.height * horizon * 0.7f),
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * (0.2f + bright * 0.6f), size.height * horizon * 0.7f),
        )
        val blockHeight = size.height * (0.18f + bright * 0.2f)
        drawRect(
            color = far.copy(alpha = 0.55f),
            topLeft = Offset(size.width * (0.05f + bright * 0.5f), size.height - blockHeight),
            size = Size(size.width * 0.34f, blockHeight),
        )
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f))))
    }
}

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m)
}
