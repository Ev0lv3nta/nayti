package app.nayti.ui.designsystem.component

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp

/**
 * Backdrop plumbing for chrome that sits on top of photographs.
 *
 * `Modifier.blur` cannot be used here: it blurs the content of the composable it is applied to, not
 * what is behind it. Real backdrop blur means recording the scrolling content into a graphics layer
 * and redrawing the region behind the panel with a blur render effect, which the platform only
 * supports from API 31. Below that, and whenever the measured cost is not worth it, the same panel
 * falls back to an opaque tint — the visual language does not depend on the effect.
 */
class NaytiBackdrop internal constructor(
    internal val source: GraphicsLayer,
    internal val blurred: GraphicsLayer,
    val blurSupported: Boolean,
) {
    internal var sourcePosition by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberNaytiBackdrop(): NaytiBackdrop {
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(source, blurred) {
        NaytiBackdrop(
            source = source,
            blurred = blurred,
            blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
    }
}

/** Records the content it wraps so chrome can sample it. Draws exactly as before otherwise. */
fun Modifier.naytiBackdropSource(backdrop: NaytiBackdrop): Modifier = this
    .onGloballyPositioned { coordinates -> backdrop.sourcePosition = coordinates.positionInWindow() }
    .drawWithContent {
        backdrop.source.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.source)
    }

/**
 * Draws the blurred backdrop region behind a bounded panel.
 *
 * Does nothing when blur is unavailable, so the caller can always add the tint on top and remain
 * legible either way.
 */
fun Modifier.naytiBackdropBlur(backdrop: NaytiBackdrop, radius: Dp): Modifier =
    if (!backdrop.blurSupported) {
        this
    } else {
        composed {
            var panelPosition by remember { mutableStateOf(Offset.Zero) }
            Modifier
                .onGloballyPositioned { coordinates -> panelPosition = coordinates.positionInWindow() }
                .drawBehind {
                    val radiusPx = radius.toPx()
                    if (radiusPx <= 0f || size.width <= 0f || size.height <= 0f) return@drawBehind
                    backdrop.blurred.renderEffect =
                        BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                    backdrop.blurred.record {
                        translate(
                            left = backdrop.sourcePosition.x - panelPosition.x,
                            top = backdrop.sourcePosition.y - panelPosition.y,
                        ) {
                            drawLayer(backdrop.source)
                        }
                    }
                    drawLayer(backdrop.blurred)
                }
        }
    }
