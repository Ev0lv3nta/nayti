package app.nayti.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import app.nayti.ui.designsystem.theme.NaytiChrome
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

/** How the chrome separates itself from the photographs underneath. */
enum class ChromeMaterial {
    /** Blurred backdrop plus a tint floor. Requires API 31+ and a measured frame budget. */
    Glass,

    /** Opaque tint only. Always available, always legible, no sampling cost. */
    Solid,
}

/**
 * Surface for chrome placed above photographs.
 *
 * The tint alpha floor — not the blur — is what guarantees the 4.5:1 contrast of the text placed on
 * it, so the panel stays readable above the brightest and the darkest frame. A hairline separates
 * it from the content instead of a shadow.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: NaytiBackdrop? = null,
    material: ChromeMaterial = ChromeMaterial.Glass,
    shape: Shape = RectangleShape,
    hairlineOnTop: Boolean = true,
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
            .background(colors.surface.copy(alpha = tintAlpha)),
    ) {
        if (hairlineOnTop) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(NaytiSpacing.Hairline)
                    .background(colors.hairline.copy(alpha = 0.6f)),
            )
        }
        content()
    }
}
