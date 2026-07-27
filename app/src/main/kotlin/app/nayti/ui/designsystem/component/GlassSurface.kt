package app.nayti.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
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
 * It is opaque and uses one inexpensive draw node by default. Blur remains an explicitly measured
 * enhancement; grain, glow and multi-pass effects are not part of this primitive.
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
    val tintAlpha = when {
        blurredBackdrop == null -> NaytiChrome.SolidTintAlpha
        colors.isDark -> NaytiChrome.GlassTintAlphaDark
        else -> NaytiChrome.GlassTintAlphaLight
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (blurredBackdrop != null) {
                    Modifier.naytiBackdropBlur(blurredBackdrop, NaytiChrome.BlurRadius)
                } else {
                    Modifier
                },
            )
            .background(colors.surface.copy(alpha = tintAlpha))
            .drawWithContent {
                drawContent()
                val strokeWidth = NaytiSpacing.Hairline.toPx()
                if (drawTopEdge) {
                    drawLine(
                        color = colors.edgeHighlight,
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = strokeWidth,
                    )
                }
                if (drawBottomEdge) {
                    drawLine(
                        color = colors.edgeShadow,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - strokeWidth / 2f),
                        end = androidx.compose.ui.geometry.Offset(
                            size.width,
                            size.height - strokeWidth / 2f,
                        ),
                        strokeWidth = strokeWidth,
                    )
                }
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
