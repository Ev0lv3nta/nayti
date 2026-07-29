package app.nayti.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import app.nayti.ui.designsystem.theme.NaytiChrome
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

/** How the chrome separates itself from the photographs underneath. */
enum class ChromeMaterial {
    /**
     * Blurred backdrop plus a tint floor. It remains solid until the caller explicitly enables a
     * measured blur in [rememberNaytiBackdrop].
     */
    Glass,

    /** Opaque tint only. Always available, always legible, no sampling cost. */
    Solid,
}

/**
 * The production "Kromka" surface: a tonal step, a quiet top reflection and a calm lower edge.
 *
 * The gradient and four inset edges are the literal Compose counterpart of the Kromka reference
 * material. Blur remains an explicitly measured enhancement.
 */
@Composable
fun EdgeSurface(
    modifier: Modifier = Modifier,
    backdrop: NaytiBackdrop? = null,
    material: ChromeMaterial = ChromeMaterial.Solid,
    shape: Shape = RectangleShape,
    drawTopEdge: Boolean = true,
    drawBottomEdge: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = NaytiTheme.colors
    val blurredBackdrop =
        backdrop?.takeIf { material == ChromeMaterial.Glass && it.blurSupported }
    val materialBrush = if (material == ChromeMaterial.Glass) {
        val topAlpha = when {
            blurredBackdrop == null -> NaytiChrome.SolidTintAlpha
            colors.isDark -> NaytiChrome.GlassTintAlphaDarkTop
            else -> NaytiChrome.GlassTintAlphaLightTop
        }
        val bottomAlpha = when {
            blurredBackdrop == null -> NaytiChrome.SolidTintAlpha
            colors.isDark -> NaytiChrome.GlassTintAlphaDarkBottom
            else -> NaytiChrome.GlassTintAlphaLightBottom
        }
        Brush.verticalGradient(
            colors = listOf(
                colors.glassTop.copy(alpha = topAlpha),
                colors.glassBottom.copy(alpha = bottomAlpha),
            ),
        )
    } else {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to colors.edgeTop,
                0.62f to colors.surface,
                1f to colors.edgeBottom,
            ),
        )
    }
    val shadowElevation = if (material == ChromeMaterial.Glass) 18.dp else 14.dp
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.46f),
                spotColor = Color.Black.copy(alpha = 0.72f),
            )
            .clip(shape)
            .then(
                if (blurredBackdrop != null) {
                    Modifier.naytiBackdropBlur(blurredBackdrop, NaytiChrome.BlurRadius)
                } else {
                    Modifier
                },
            )
            .background(materialBrush)
            .drawWithCache {
                // The HTML material uses a 160 px noise tile at 2.8% overlay opacity. A cached,
                // deterministic stipple keeps the same tactile breakup without a bitmap asset or
                // per-frame random work.
                val lightGrain = Path()
                val darkGrain = Path()
                val step = 10.dp.toPx()
                val radius = 0.34.dp.toPx()
                var row = 0
                var y = step / 2f
                while (y < size.height) {
                    var column = 0
                    var x = step / 2f
                    while (x < size.width) {
                        val hash = (column * 73 + row * 151 + column * row * 17) and 15
                        if (hash == 1 || hash == 7) {
                            val target = if (hash == 1) lightGrain else darkGrain
                            target.addOval(Rect(x - radius, y - radius, x + radius, y + radius))
                        }
                        column += 1
                        x += step
                    }
                    row += 1
                    y += step
                }
                onDrawWithContent {
                    drawContent()
                    drawPath(
                        path = lightGrain,
                        color = Color.White.copy(alpha = if (colors.isDark) 0.020f else 0.035f),
                    )
                    drawPath(
                        path = darkGrain,
                        color = Color.Black.copy(alpha = if (colors.isDark) 0.026f else 0.020f),
                    )
                }
            }
            .drawWithContent {
                drawContent()
                val strokeWidth = NaytiSpacing.Hairline.toPx()
                if (drawTopEdge) {
                    drawLine(
                        color = colors.edgeHighlight,
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                        strokeWidth = strokeWidth,
                    )
                }
                if (drawBottomEdge) {
                    drawLine(
                        color = colors.edgeShadow,
                        start = Offset(0f, size.height - strokeWidth / 2f),
                        end = Offset(
                            size.width,
                            size.height - strokeWidth / 2f,
                        ),
                        strokeWidth = strokeWidth,
                    )
                }
                val sideEdge = if (colors.isDark) {
                    Color(0x09FFEFE4)
                } else {
                    Color(0x0F463024)
                }
                drawLine(
                    color = sideEdge,
                    start = Offset(strokeWidth / 2f, 0f),
                    end = Offset(strokeWidth / 2f, size.height),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = sideEdge,
                    start = Offset(size.width - strokeWidth / 2f, 0f),
                    end = Offset(size.width - strokeWidth / 2f, size.height),
                    strokeWidth = strokeWidth,
                )
            },
    ) {
        content()
    }
}

/**
 * Compatibility wrapper for screens that predate [EdgeSurface].
 *
 * The old name no longer implies blur: production calls resolve to the same opaque edge primitive
 * unless their backdrop was explicitly created with measured blur enabled.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: NaytiBackdrop? = null,
    material: ChromeMaterial = ChromeMaterial.Solid,
    shape: Shape = RectangleShape,
    hairlineOnTop: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    EdgeSurface(
        modifier = modifier,
        backdrop = backdrop,
        material = material,
        shape = shape,
        drawTopEdge = hairlineOnTop,
        content = content,
    )
}
