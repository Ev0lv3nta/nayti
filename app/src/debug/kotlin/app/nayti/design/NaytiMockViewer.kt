package app.nayti.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.component.GlassSurface
import app.nayti.ui.designsystem.component.rememberNaytiBackdrop
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

/**
 * Full-screen viewer.
 *
 * The photograph owns the whole surface; chrome floats above it and the match explanation is a
 * solid card, because accent text may not sit on glass.
 */
@Composable
fun MockViewer() {
    val colors = NaytiTheme.colors
    val backdrop = rememberNaytiBackdrop()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SyntheticPhoto(3, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 52.dp, start = NaytiSpacing.Screen, end = NaytiSpacing.Screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerChromeButton(MockIcon.Back)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("26 июня 2026", style = NaytiTheme.type.labelL, color = Color.White)
                Text("Скриншоты", style = NaytiTheme.type.labelS, color = Color.White.copy(alpha = 0.75f))
            }
            Spacer(Modifier.weight(1f))
            ViewerChromeButton(MockIcon.Methods)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(NaytiSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NaytiTheme.shapes.card)
                    .background(colors.surface)
                    .padding(NaytiSpacing.Screen),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                ) {
                    MockIconMark(MockIcon.TextLines, colors.accent, 16.dp)
                    Text("Почему найдено · точный номер", style = NaytiTheme.type.labelL, color = colors.accent)
                }
                Text(
                    text = "…договор № АБ-123/45 от 12 апреля…",
                    style = NaytiTheme.type.bodyL,
                    color = colors.ink,
                )
                Text(
                    text = "По этому кадру готово: текст, копии. Готовится: смысл, содержимое.",
                    style = NaytiTheme.type.bodyM,
                    color = colors.inkFaint,
                )
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                material = ChromeMaterial.Solid,
                shape = RoundedCornerShape(28.dp),
                hairlineOnTop = false,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                ) {
                    ViewerAction(MockIcon.Copies, "Похожие", Modifier.weight(1f))
                    ViewerAction(MockIcon.TextLines, "Текст на фото", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(NaytiSpacing.Medium))
        }
    }
}

@Composable
private fun ViewerChromeButton(icon: MockIcon) {
    Box(
        modifier = Modifier
            .size(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        MockIconMark(icon, Color.White, 20.dp)
    }
}

@Composable
private fun ViewerAction(icon: MockIcon, label: String, modifier: Modifier) {
    val colors = NaytiTheme.colors
    Row(
        modifier = modifier
            .height(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(colors.surfaceHigh),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MockIconMark(icon, colors.ink, 18.dp)
        Spacer(Modifier.width(NaytiSpacing.Small))
        Text(label, style = NaytiTheme.type.labelL, color = colors.ink, maxLines = 1)
    }
}

/**
 * Brand mark in the contexts that decide it: adaptive foreground, monochrome, launcher size and the
 * notification silhouette.
 */
@Composable
fun MockIconSheet() {
    val colors = NaytiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Section),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Знак «Точка фокуса»", style = NaytiTheme.type.titleL, color = colors.ink)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Section),
        ) {
            IconTile(size = 132.dp, corner = 30.dp, background = Color(0xFF14161A), mark = Color(0xFFECEEF2))
            IconTile(size = 132.dp, corner = 66.dp, background = Color(0xFF14161A), mark = Color(0xFFECEEF2))
        }
        Text("Адаптивная иконка: квадратная и круглая маска", style = NaytiTheme.type.bodyM, color = colors.inkMuted)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Section),
        ) {
            IconTile(size = 64.dp, corner = 15.dp, background = Color(0xFF14161A), mark = Color(0xFFECEEF2))
            IconTile(size = 48.dp, corner = 11.dp, background = Color(0xFF14161A), mark = Color(0xFFECEEF2))
            IconTile(size = 64.dp, corner = 15.dp, background = colors.ink, mark = colors.background)
            Box(Modifier.size(28.dp)) {
                Canvas(Modifier.fillMaxSize()) { drawFocusMark(colors.ink, strokeScale = 1.15f) }
            }
        }
        Text(
            text = "Мелкий размер, монохромный вариант для themed icons и силуэт 24 dp для уведомления",
            style = NaytiTheme.type.bodyM,
            color = colors.inkMuted,
        )
    }
}

@Composable
private fun IconTile(
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
    background: Color,
    mark: Color,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) { drawFocusMark(mark) }
    }
}
