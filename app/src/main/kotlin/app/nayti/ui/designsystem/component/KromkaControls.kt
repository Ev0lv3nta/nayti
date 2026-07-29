package app.nayti.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

enum class KromkaButtonMaterial {
    Primary,
    Metal,
    Ghost,
}

/** Recessed tonal field used by the search console. */
@Composable
fun Modifier.kromkaRecessedField(
    shape: Shape = NaytiTheme.shapes.card,
): Modifier {
    val colors = NaytiTheme.colors
    val brush = if (colors.isDark) {
        Brush.verticalGradient(listOf(Color(0xFF241F1D), Color(0xFF1B1716)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF7F2EE), Color(0xFFF1EAE4)))
    }
    return this
        .clip(shape)
        .background(brush)
        .drawWithContent {
            drawContent()
            val hairline = NaytiSpacing.Hairline.toPx()
            drawLine(
                if (colors.isDark) Color(0xFF38312E) else colors.hairline,
                Offset.Zero,
                Offset(size.width, 0f),
                hairline,
            )
            drawLine(colors.hairline, Offset.Zero, Offset(0f, size.height), hairline)
            drawLine(colors.hairline, Offset(size.width, 0f), Offset(size.width, size.height), hairline)
            drawLine(
                colors.hairline,
                Offset(0f, size.height - hairline / 2f),
                Offset(size.width, size.height - hairline / 2f),
                hairline,
            )
        }
}

/**
 * Visual body for modal sheets. Material3 still owns gestures, focus and accessibility, while this
 * layer supplies the Kromka edge gradient and the reference grab handle.
 */
@Composable
fun KromkaSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    EdgeSurface(
        modifier = modifier.fillMaxWidth(),
        shape = NaytiTheme.shapes.sheet,
        drawBottomEdge = false,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(top = 14.dp, bottom = 14.dp)
                    .align(Alignment.CenterHorizontally)
                    .height(4.dp)
                    .fillMaxWidth(0.10f)
                    .clip(NaytiTheme.shapes.control)
                    .background(
                        if (NaytiTheme.colors.isDark) {
                            Color(0xFF453D39)
                        } else {
                            Color(0xFFD8CEC5)
                        },
                    ),
            )
            content()
        }
    }
}

/**
 * Filled action with the same directional light, inset edges and restrained shadow as the HTML
 * Kromka reference. The caller owns dimensions so the component also works for square icon actions.
 */
@Composable
fun KromkaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    material: KromkaButtonMaterial = KromkaButtonMaterial.Primary,
    shape: Shape = NaytiTheme.shapes.card,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = NaytiTheme.colors
    val brush = when (material) {
        KromkaButtonMaterial.Primary -> Brush.verticalGradient(
            colorStops = arrayOf(
                0f to colors.accentTop,
                0.55f to if (colors.isDark) Color(0xFFB32744) else colors.accent,
                1f to colors.accentBottom,
            ),
        )
        KromkaButtonMaterial.Metal -> if (colors.isDark) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFFEDF0F3),
                    0.48f to Color(0xFFCDD3DA),
                    1f to Color(0xFFAEB5BD),
                ),
            )
        } else {
            Brush.verticalGradient(listOf(Color(0xFF3A3330), Color(0xFF221C19)))
        }
        KromkaButtonMaterial.Ghost -> Brush.verticalGradient(
            listOf(
                colors.ink.copy(alpha = if (colors.isDark) 0.05f else 0.025f),
                colors.ink.copy(alpha = if (colors.isDark) 0.015f else 0.01f),
            ),
        )
    }
    val contentColor = when (material) {
        KromkaButtonMaterial.Primary -> Color.White
        KromkaButtonMaterial.Metal -> if (colors.isDark) Color(0xFF171312) else Color.White
        KromkaButtonMaterial.Ghost -> colors.ink
    }
    val shadowColor = when (material) {
        KromkaButtonMaterial.Primary -> colors.accent.copy(alpha = 0.55f)
        else -> Color.Black.copy(alpha = 0.42f)
    }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.46f)
            .shadow(
                elevation = if (material == KromkaButtonMaterial.Ghost) 0.dp else 8.dp,
                shape = shape,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(shape)
            .background(brush)
            .drawWithContent {
                drawContent()
                val hairline = NaytiSpacing.Hairline.toPx()
                val top = when (material) {
                    KromkaButtonMaterial.Primary -> Color.White.copy(alpha = 0.22f)
                    KromkaButtonMaterial.Metal -> Color.White.copy(alpha = 0.78f)
                    KromkaButtonMaterial.Ghost -> colors.edgeHighlight
                }
                val bottom = if (material == KromkaButtonMaterial.Ghost) {
                    colors.hairline
                } else {
                    Color.Black.copy(alpha = 0.30f)
                }
                drawLine(top, Offset.Zero, Offset(size.width, 0f), hairline)
                drawLine(
                    bottom,
                    Offset(0f, size.height - hairline / 2f),
                    Offset(size.width, size.height - hairline / 2f),
                    hairline,
                )
                if (material == KromkaButtonMaterial.Ghost) {
                    drawLine(colors.hairline, Offset.Zero, Offset(0f, size.height), hairline)
                    drawLine(
                        colors.hairline,
                        Offset(size.width, 0f),
                        Offset(size.width, size.height),
                        hairline,
                    )
                }
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * Search-channel selector from the reference: a metallic selected face and a recessed inactive
 * face. Channel identity remains visible through its own colour in both states.
 */
@Composable
fun KromkaModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    channelColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = NaytiTheme.colors
    val selectedStops = when {
        colors.isDark && channelColor == colors.evidenceText ->
            listOf(Color(0xFFE4E8ED), Color(0xFFC3C9D1))
        colors.isDark && channelColor == colors.evidenceMeaning ->
            listOf(Color(0xFFF2C878), Color(0xFFDDA84E))
        colors.isDark ->
            listOf(Color(0xFF83D4B2), Color(0xFF5CB48F))
        channelColor == colors.evidenceText ->
            listOf(Color(0xFF5C646F), Color(0xFF414851))
        channelColor == colors.evidenceMeaning ->
            listOf(Color(0xFFA06A0E), Color(0xFF835308))
        else ->
            listOf(Color(0xFF188266), Color(0xFF0F6650))
    }
    val shape = NaytiTheme.shapes.control
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.46f)
            .clip(shape)
            .background(
                if (selected) {
                    Brush.verticalGradient(selectedStops)
                } else {
                    Brush.verticalGradient(
                        listOf(
                            colors.ink.copy(alpha = if (colors.isDark) 0.045f else 0.025f),
                            colors.ink.copy(alpha = 0.012f),
                        ),
                    )
                },
            )
            .drawWithContent {
                drawContent()
                val hairline = NaytiSpacing.Hairline.toPx()
                if (selected) {
                    drawLine(
                        Color.White.copy(alpha = if (colors.isDark) 0.58f else 0.18f),
                        Offset.Zero,
                        Offset(size.width, 0f),
                        hairline,
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.25f),
                        Offset(0f, size.height - hairline / 2f),
                        Offset(size.width, size.height - hairline / 2f),
                        hairline,
                    )
                } else {
                    drawLine(colors.hairline, Offset.Zero, Offset(size.width, 0f), hairline)
                    drawLine(colors.hairline, Offset(0f, size.height), Offset(size.width, size.height), hairline)
                    drawLine(colors.hairline, Offset.Zero, Offset(0f, size.height), hairline)
                    drawLine(colors.hairline, Offset(size.width, 0f), Offset(size.width, size.height), hairline)
                }
            }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (selected) {
                    if (colors.isDark) Color(0xFF12100F) else Color.White
                } else {
                    colors.inkMuted
                },
            LocalTextStyle provides NaytiTheme.type.labelL,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Compact selected/unselected pill used by filters and scope sheets. */
@Composable
fun KromkaChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = NaytiTheme.colors
    val shape = NaytiTheme.shapes.control
    val interactionSource = remember { MutableInteractionSource() }
    val brush = if (selected) {
        Brush.verticalGradient(listOf(colors.accentTop, colors.accentBottom))
    } else {
        Brush.verticalGradient(
            listOf(
                colors.ink.copy(alpha = if (colors.isDark) 0.045f else 0.022f),
                colors.ink.copy(alpha = 0.01f),
            ),
        )
    }
    Box(
        modifier = modifier
            .height(38.dp)
            .alpha(if (enabled) 1f else 0.46f)
            .clip(shape)
            .background(brush)
            .drawWithContent {
                drawContent()
                val hairline = NaytiSpacing.Hairline.toPx()
                val top = if (selected) Color.White.copy(alpha = 0.20f) else colors.hairline
                val bottom = if (selected) Color.Black.copy(alpha = 0.30f) else colors.hairline
                drawLine(top, Offset.Zero, Offset(size.width, 0f), hairline)
                drawLine(
                    bottom,
                    Offset(0f, size.height - hairline / 2f),
                    Offset(size.width, size.height - hairline / 2f),
                    hairline,
                )
                if (!selected) {
                    drawLine(colors.hairline, Offset.Zero, Offset(0f, size.height), hairline)
                    drawLine(colors.hairline, Offset(size.width, 0f), Offset(size.width, size.height), hairline)
                }
            }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (selected) Color.White else colors.inkMuted,
            LocalTextStyle provides NaytiTheme.type.labelL,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
