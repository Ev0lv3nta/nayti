package app.nayti.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drawn icon set of the proposal.
 *
 * A geometric, single-weight set replaces the stock Material icons, where one padlock currently
 * stands for privacy, photo access, quarantine and "outside the current results". Each mark here
 * means exactly one thing. Drawn in code so the direction can be judged before any asset is cut.
 */
enum class MockIcon {
    Search,
    Filters,
    Methods,
    Settings,
    ChevronRight,
    Back,
    Close,
    TextLines,
    Meaning,
    Scene,
    Copies,
    Clock,
    Check,
    Alert,
    Shield,
    Period,
}

@Composable
fun MockIconMark(
    icon: MockIcon,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) { drawMockIcon(icon, color) }
}

fun DrawScope.drawMockIcon(icon: MockIcon, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), stroke.width, StrokeCap.Round)

    when (icon) {
        MockIcon.Search -> {
            drawCircle(color, radius = s * 0.28f, center = Offset(s * 0.43f, s * 0.43f), style = stroke)
            line(0.64f, 0.64f, 0.86f, 0.86f)
        }
        MockIcon.Filters -> {
            line(0.16f, 0.3f, 0.84f, 0.3f)
            line(0.16f, 0.7f, 0.84f, 0.7f)
            drawCircle(color, s * 0.1f, Offset(s * 0.62f, s * 0.3f))
            drawCircle(color, s * 0.1f, Offset(s * 0.36f, s * 0.7f))
        }
        MockIcon.Methods -> {
            line(0.18f, 0.28f, 0.62f, 0.28f)
            line(0.18f, 0.52f, 0.5f, 0.52f)
            line(0.18f, 0.76f, 0.72f, 0.76f)
            drawCircle(color, s * 0.09f, Offset(s * 0.82f, s * 0.36f))
        }
        MockIcon.Settings -> {
            val gear = Path().apply {
                repeat(24) { point ->
                    val angle = Math.toRadians((point * 15.0) - 90.0)
                    val radius = if (point % 3 == 1) 0.33f else 0.43f
                    val x = s * (0.5f + radius * kotlin.math.cos(angle).toFloat())
                    val y = s * (0.5f + radius * kotlin.math.sin(angle).toFloat())
                    if (point == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(gear, color, style = stroke)
            drawCircle(color, s * 0.15f, Offset(s * 0.5f, s * 0.5f), style = stroke)
        }
        MockIcon.ChevronRight -> {
            line(0.42f, 0.28f, 0.64f, 0.5f)
            line(0.64f, 0.5f, 0.42f, 0.72f)
        }
        MockIcon.Back -> {
            line(0.2f, 0.5f, 0.8f, 0.5f)
            line(0.2f, 0.5f, 0.44f, 0.28f)
            line(0.2f, 0.5f, 0.44f, 0.72f)
        }
        MockIcon.Close -> {
            line(0.26f, 0.26f, 0.74f, 0.74f)
            line(0.74f, 0.26f, 0.26f, 0.74f)
        }
        MockIcon.TextLines -> {
            line(0.18f, 0.3f, 0.82f, 0.3f)
            line(0.18f, 0.5f, 0.66f, 0.5f)
            line(0.18f, 0.7f, 0.78f, 0.7f)
        }
        MockIcon.Meaning -> {
            line(0.18f, 0.32f, 0.6f, 0.32f)
            line(0.18f, 0.56f, 0.46f, 0.56f)
            val star = Path().apply {
                moveTo(s * 0.76f, s * 0.5f)
                lineTo(s * 0.82f, s * 0.68f)
                lineTo(s * 0.98f, s * 0.74f)
                lineTo(s * 0.82f, s * 0.8f)
                lineTo(s * 0.76f, s * 0.96f)
                lineTo(s * 0.7f, s * 0.8f)
                lineTo(s * 0.54f, s * 0.74f)
                lineTo(s * 0.7f, s * 0.68f)
                close()
            }
            drawPath(star, color)
        }
        MockIcon.Scene -> {
            drawRoundRectOutline(color, stroke, 0.14f, 0.2f, 0.72f, 0.6f, s)
            drawCircle(color, s * 0.07f, Offset(s * 0.34f, s * 0.38f))
            val hill = Path().apply {
                moveTo(s * 0.18f, s * 0.72f)
                lineTo(s * 0.4f, s * 0.48f)
                lineTo(s * 0.58f, s * 0.66f)
                lineTo(s * 0.68f, s * 0.56f)
                lineTo(s * 0.82f, s * 0.72f)
                close()
            }
            drawPath(hill, color)
        }
        MockIcon.Copies -> {
            drawRoundRectOutline(color, stroke, 0.12f, 0.12f, 0.56f, 0.56f, s)
            drawRoundRectOutline(color, stroke, 0.32f, 0.32f, 0.56f, 0.56f, s)
        }
        MockIcon.Clock -> {
            drawCircle(color, s * 0.36f, Offset(s * 0.5f, s * 0.5f), style = stroke)
            line(0.5f, 0.5f, 0.5f, 0.3f)
            line(0.5f, 0.5f, 0.66f, 0.58f)
        }
        MockIcon.Check -> {
            line(0.22f, 0.52f, 0.42f, 0.72f)
            line(0.42f, 0.72f, 0.78f, 0.3f)
        }
        MockIcon.Alert -> {
            val triangle = Path().apply {
                moveTo(s * 0.5f, s * 0.16f)
                lineTo(s * 0.9f, s * 0.82f)
                lineTo(s * 0.1f, s * 0.82f)
                close()
            }
            drawPath(triangle, color, style = stroke)
            line(0.5f, 0.42f, 0.5f, 0.6f)
            drawCircle(color, s * 0.045f, Offset(s * 0.5f, s * 0.72f))
        }
        MockIcon.Shield -> {
            val shield = Path().apply {
                moveTo(s * 0.5f, s * 0.12f)
                lineTo(s * 0.84f, s * 0.28f)
                lineTo(s * 0.84f, s * 0.54f)
                cubicTo(s * 0.84f, s * 0.74f, s * 0.68f, s * 0.84f, s * 0.5f, s * 0.9f)
                cubicTo(s * 0.32f, s * 0.84f, s * 0.16f, s * 0.74f, s * 0.16f, s * 0.54f)
                lineTo(s * 0.16f, s * 0.28f)
                close()
            }
            drawPath(shield, color, style = stroke)
        }
        MockIcon.Period -> {
            drawRoundRectOutline(color, stroke, 0.14f, 0.22f, 0.72f, 0.62f, s)
            line(0.14f, 0.4f, 0.86f, 0.4f)
            line(0.32f, 0.14f, 0.32f, 0.3f)
            line(0.68f, 0.14f, 0.68f, 0.3f)
        }
    }
}

private fun DrawScope.drawRoundRectOutline(
    color: Color,
    stroke: Stroke,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    s: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left * s, top * s),
        size = Size(width * s, height * s),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
        style = stroke,
    )
}

/**
 * Brand mark: a focus bracket around a single point.
 *
 * Reads as "this exact frame, out of thousands" — aiming rather than magnifying. A magnifier would
 * be indistinguishable from every other search app, and the current pinwheel-free market around
 * gallery apps leaves the bracket unoccupied. Survives monochrome and 48 dp.
 */
fun DrawScope.drawFocusMark(color: Color, strokeScale: Float = 1f) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.085f * strokeScale, cap = StrokeCap.Round)
    val inset = 0.2f
    val arm = 0.16f
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x * s, y * s), Offset((x + dx * arm) * s, y * s), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(x * s, y * s), Offset(x * s, (y + dy * arm) * s), stroke.width, StrokeCap.Round)
    }
    corner(inset, inset, 1f, 1f)
    corner(1f - inset, inset, -1f, 1f)
    corner(inset, 1f - inset, 1f, -1f)
    corner(1f - inset, 1f - inset, -1f, -1f)
    drawCircle(color, radius = s * 0.105f, center = Offset(s * 0.5f, s * 0.5f))
}
