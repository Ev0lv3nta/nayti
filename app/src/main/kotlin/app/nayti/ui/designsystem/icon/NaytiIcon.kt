package app.nayti.ui.designsystem.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
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
import app.nayti.ui.designsystem.theme.NaytiIconSize
import kotlin.math.max

/**
 * Small geometric icon set shared by redesigned production surfaces.
 *
 * Each mark has one product meaning. This avoids reusing the same stock lock or tool symbol for
 * unrelated concepts and keeps the line weight stable from 18 to 24 dp.
 */
enum class NaytiIcon {
    Search,
    Filters,
    Methods,
    Settings,
    ChevronRight,
    Back,
    Close,
    Text,
    Meaning,
    Scene,
    Copies,
    Clock,
    Check,
    Alert,
    Info,
    Shield,
    Period,
    Photos,
    Models,
    Storage,
    Export,
    Delete,
    About,
}

@Composable
fun NaytiIconMark(
    icon: NaytiIcon,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    size: Dp = NaytiIconSize.Standard,
) {
    Canvas(modifier.size(size)) { drawNaytiIcon(icon, color) }
}

private fun DrawScope.drawNaytiIcon(icon: NaytiIcon, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = max(s * 0.075f, 1.5.dp.toPx()), cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), stroke.width, StrokeCap.Round)

    when (icon) {
        NaytiIcon.Search -> {
            drawCircle(color, s * 0.28f, Offset(s * 0.43f, s * 0.43f), style = stroke)
            line(0.64f, 0.64f, 0.86f, 0.86f)
        }
        NaytiIcon.Filters -> {
            line(0.16f, 0.3f, 0.84f, 0.3f)
            line(0.16f, 0.7f, 0.84f, 0.7f)
            drawCircle(color, s * 0.1f, Offset(s * 0.62f, s * 0.3f))
            drawCircle(color, s * 0.1f, Offset(s * 0.36f, s * 0.7f))
        }
        NaytiIcon.Methods -> {
            line(0.18f, 0.28f, 0.62f, 0.28f)
            line(0.18f, 0.52f, 0.5f, 0.52f)
            line(0.18f, 0.76f, 0.72f, 0.76f)
            drawCircle(color, s * 0.09f, Offset(s * 0.82f, s * 0.36f))
        }
        NaytiIcon.Settings -> {
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
        NaytiIcon.ChevronRight -> {
            line(0.42f, 0.28f, 0.64f, 0.5f)
            line(0.64f, 0.5f, 0.42f, 0.72f)
        }
        NaytiIcon.Back -> {
            line(0.2f, 0.5f, 0.8f, 0.5f)
            line(0.2f, 0.5f, 0.44f, 0.28f)
            line(0.2f, 0.5f, 0.44f, 0.72f)
        }
        NaytiIcon.Close -> {
            line(0.26f, 0.26f, 0.74f, 0.74f)
            line(0.74f, 0.26f, 0.26f, 0.74f)
        }
        NaytiIcon.Text -> {
            line(0.18f, 0.3f, 0.82f, 0.3f)
            line(0.18f, 0.5f, 0.66f, 0.5f)
            line(0.18f, 0.7f, 0.78f, 0.7f)
        }
        NaytiIcon.Meaning -> {
            // A relation between two phrases, without the sparkle shorthand used by AI products.
            line(0.16f, 0.32f, 0.62f, 0.32f)
            line(0.38f, 0.68f, 0.84f, 0.68f)
            line(0.62f, 0.32f, 0.38f, 0.68f)
            drawCircle(color, stroke.width * 0.8f, Offset(s * 0.16f, s * 0.32f))
            drawCircle(color, stroke.width * 0.8f, Offset(s * 0.84f, s * 0.68f))
        }
        NaytiIcon.Scene -> {
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
        NaytiIcon.Copies -> {
            drawRoundRectOutline(color, stroke, 0.12f, 0.12f, 0.56f, 0.56f, s)
            drawRoundRectOutline(color, stroke, 0.32f, 0.32f, 0.56f, 0.56f, s)
        }
        NaytiIcon.Clock -> {
            drawCircle(color, s * 0.36f, Offset(s * 0.5f, s * 0.5f), style = stroke)
            line(0.5f, 0.5f, 0.5f, 0.3f)
            line(0.5f, 0.5f, 0.66f, 0.58f)
        }
        NaytiIcon.Check -> {
            line(0.22f, 0.52f, 0.42f, 0.72f)
            line(0.42f, 0.72f, 0.78f, 0.3f)
        }
        NaytiIcon.Alert -> {
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
        NaytiIcon.Info -> {
            drawCircle(color, s * 0.36f, Offset(s * 0.5f, s * 0.5f), style = stroke)
            line(0.5f, 0.46f, 0.5f, 0.7f)
            drawCircle(color, s * 0.045f, Offset(s * 0.5f, s * 0.32f))
        }
        NaytiIcon.Shield -> {
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
        NaytiIcon.Period -> {
            drawRoundRectOutline(color, stroke, 0.14f, 0.22f, 0.72f, 0.62f, s)
            line(0.14f, 0.4f, 0.86f, 0.4f)
            line(0.32f, 0.14f, 0.32f, 0.3f)
            line(0.68f, 0.14f, 0.68f, 0.3f)
        }
        NaytiIcon.Photos -> {
            drawRoundRectOutline(color, stroke, 0.12f, 0.16f, 0.76f, 0.68f, s)
            drawCircle(color, s * 0.07f, Offset(s * 0.34f, s * 0.36f))
            val photo = Path().apply {
                moveTo(s * 0.18f, s * 0.74f)
                lineTo(s * 0.4f, s * 0.5f)
                lineTo(s * 0.56f, s * 0.66f)
                lineTo(s * 0.68f, s * 0.54f)
                lineTo(s * 0.82f, s * 0.74f)
            }
            drawPath(photo, color, style = stroke)
        }
        NaytiIcon.Models -> {
            drawCircle(color, s * 0.11f, Offset(s * 0.5f, s * 0.22f), style = stroke)
            drawCircle(color, s * 0.11f, Offset(s * 0.22f, s * 0.7f), style = stroke)
            drawCircle(color, s * 0.11f, Offset(s * 0.78f, s * 0.7f), style = stroke)
            line(0.44f, 0.3f, 0.28f, 0.6f)
            line(0.56f, 0.3f, 0.72f, 0.6f)
            line(0.34f, 0.7f, 0.66f, 0.7f)
        }
        NaytiIcon.Storage -> {
            drawOval(
                color,
                topLeft = Offset(s * 0.16f, s * 0.16f),
                size = Size(s * 0.68f, s * 0.24f),
                style = stroke,
            )
            drawArc(
                color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.16f, s * 0.34f),
                size = Size(s * 0.68f, s * 0.24f),
                style = stroke,
            )
            drawArc(
                color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.16f, s * 0.56f),
                size = Size(s * 0.68f, s * 0.24f),
                style = stroke,
            )
            line(0.16f, 0.28f, 0.16f, 0.68f)
            line(0.84f, 0.28f, 0.84f, 0.68f)
        }
        NaytiIcon.Export -> {
            drawRoundRectOutline(color, stroke, 0.18f, 0.38f, 0.64f, 0.48f, s)
            line(0.5f, 0.62f, 0.5f, 0.12f)
            line(0.5f, 0.12f, 0.32f, 0.3f)
            line(0.5f, 0.12f, 0.68f, 0.3f)
        }
        NaytiIcon.Delete -> {
            drawRoundRectOutline(color, stroke, 0.27f, 0.3f, 0.46f, 0.58f, s)
            line(0.2f, 0.24f, 0.8f, 0.24f)
            line(0.4f, 0.14f, 0.6f, 0.14f)
            line(0.42f, 0.44f, 0.42f, 0.72f)
            line(0.58f, 0.44f, 0.58f, 0.72f)
        }
        NaytiIcon.About -> {
            drawCircle(color, s * 0.38f, Offset(s * 0.5f, s * 0.5f), style = stroke)
            line(0.5f, 0.46f, 0.5f, 0.72f)
            drawCircle(color, s * 0.045f, Offset(s * 0.5f, s * 0.3f))
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
    scale: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left * scale, top * scale),
        size = Size(width * scale, height * scale),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * 0.12f),
        style = stroke,
    )
}
