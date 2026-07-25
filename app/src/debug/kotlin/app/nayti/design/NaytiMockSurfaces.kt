package app.nayti.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.component.GlassSurface
import app.nayti.ui.designsystem.component.NaytiBackdrop
import app.nayti.ui.designsystem.component.naytiBackdropSource
import app.nayti.ui.designsystem.component.rememberNaytiBackdrop
import app.nayti.ui.designsystem.theme.NaytiChrome
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

/** Height reserved for the system status bar so the composition reads correctly in a screenshot. */
private val SystemBarTop = 44.dp
private val SystemBarBottom = 24.dp

@Composable
fun MockLibrary(material: ChromeMaterial = ChromeMaterial.Glass, resultsMode: Boolean = false) {
    val colors = NaytiTheme.colors
    val backdrop = rememberNaytiBackdrop()
    Box(Modifier.fillMaxSize().background(colors.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().naytiBackdropSource(backdrop),
            contentPadding = PaddingValues(
                top = SystemBarTop + NaytiChrome.StatusStripHeight + 52.dp,
                bottom = SystemBarBottom + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.PhotoGutter),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.PhotoGutter),
        ) {
            if (resultsMode) {
                item(span = { GridItemSpan(maxLineSpan) }) { CoverageLine() }
                items(ResultSamples, key = { it.index }) { sample -> ResultTile(sample) }
                item(span = { GridItemSpan(maxLineSpan) }) { SnippetRow() }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { MonthHeader("Июль 2026") }
                items((0..17).toList(), key = { it }) { index -> PhotoTile(index) }
                item(span = { GridItemSpan(maxLineSpan) }) { MonthHeader("Июнь 2026") }
                items((18..47).toList(), key = { it }) { index -> PhotoTile(index) }
            }
        }
        TopChrome(backdrop, material)
        BottomChrome(backdrop, material)
    }
}

@Composable
private fun MonthHeader(title: String) {
    Text(
        text = title,
        style = NaytiTheme.type.titleM,
        color = NaytiTheme.colors.ink,
        modifier = Modifier.padding(
            start = NaytiSpacing.Screen,
            top = NaytiSpacing.Section,
            bottom = NaytiSpacing.Medium,
        ),
    )
}

@Composable
private fun PhotoTile(index: Int, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).clip(NaytiTheme.shapes.photo)) {
        SyntheticPhoto(index, Modifier.fillMaxSize())
    }
}

private data class ResultSample(val index: Int, val reason: String, val icon: MockIcon)

/**
 * Reason labels on a tile are the short form.
 *
 * A tile is about 128 dp wide, so the full wording ("По содержимому фотографии") cannot fit and was
 * silently clipped in the first render. The complete phrasing lives in the viewer, where there is
 * room to be precise.
 */
private val ResultSamples = listOf(
    ResultSample(3, "Номер", MockIcon.TextLines),
    ResultSample(11, "Фраза", MockIcon.TextLines),
    ResultSample(24, "Смысл", MockIcon.Meaning),
    ResultSample(7, "Номер", MockIcon.TextLines),
    ResultSample(31, "Кадр", MockIcon.Scene),
    ResultSample(19, "Смысл", MockIcon.Meaning),
    ResultSample(42, "Кадр", MockIcon.Scene),
    ResultSample(15, "Похоже", MockIcon.TextLines),
    ResultSample(28, "Кадр", MockIcon.Scene),
)

@Composable
private fun ResultTile(sample: ResultSample) {
    val colors = NaytiTheme.colors
    Box(Modifier.aspectRatio(1f).clip(NaytiTheme.shapes.photo)) {
        SyntheticPhoto(sample.index, Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(52.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(NaytiTheme.shapes.badge)
                .background(colors.accentContainer.copy(alpha = 0.94f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MockIconMark(sample.icon, colors.onAccentContainer, 12.dp)
            Text(
                text = sample.reason,
                style = NaytiTheme.type.labelS,
                color = colors.onAccentContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CoverageLine() {
    val colors = NaytiTheme.colors
    Column(
        Modifier.padding(
            start = NaytiSpacing.Screen,
            end = NaytiSpacing.Screen,
            top = NaytiSpacing.Small,
            bottom = NaytiSpacing.Medium,
        ),
    ) {
        Text("Найдено 9 фотографий", style = NaytiTheme.type.titleM, color = colors.ink)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Искали по тексту и смыслу среди 1 423 фотографий периода",
            style = NaytiTheme.type.bodyM,
            color = colors.inkMuted,
        )
    }
}

@Composable
private fun SnippetRow() {
    val colors = NaytiTheme.colors
    Column(
        Modifier
            .padding(NaytiSpacing.Screen)
            .clip(NaytiTheme.shapes.card)
            .background(colors.surface)
            .padding(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
        ) {
            MockIconMark(MockIcon.TextLines, colors.accent, 18.dp)
            Text("Совпавший текст", style = NaytiTheme.type.labelL, color = colors.accent)
        }
        Text(
            text = "Договор аренды № АБ-123/45 от 12 апреля 2026 года",
            style = NaytiTheme.type.bodyL,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text("12 апр 2026 · Документы", style = NaytiTheme.type.bodyM, color = colors.inkFaint)
    }
}

@Composable
private fun TopChrome(backdrop: NaytiBackdrop, material: ChromeMaterial) {
    val colors = NaytiTheme.colors
    Column(Modifier.fillMaxWidth()) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            backdrop = backdrop,
            material = material,
            hairlineOnTop = false,
        ) {
            Column {
                Spacer(Modifier.height(SystemBarTop))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NaytiChrome.StatusStripHeight)
                        .padding(horizontal = NaytiSpacing.Screen),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(colors.ready),
                    )
                    Text(
                        text = "Поиск уже работает · подготовка продолжается",
                        style = NaytiTheme.type.bodyM,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MockIconMark(MockIcon.ChevronRight, colors.ink, 18.dp)
                    Spacer(Modifier.width(NaytiSpacing.Small))
                    MockIconMark(MockIcon.Settings, colors.ink, 20.dp)
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = NaytiSpacing.Screen, top = NaytiSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
        ) {
            ScopePill("Период: с 23 апр", selected = true)
            ScopePill("Вся медиатека", selected = false)
        }
    }
}

@Composable
private fun ScopePill(label: String, selected: Boolean) {
    val colors = NaytiTheme.colors
    Row(
        modifier = Modifier
            .clip(NaytiTheme.shapes.control)
            .background(
                if (selected) colors.accentContainer else colors.surface.copy(alpha = NaytiChrome.SolidTintAlpha),
            )
            .padding(horizontal = NaytiSpacing.Medium, vertical = NaytiSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) MockIconMark(MockIcon.Period, colors.onAccentContainer, 14.dp)
        Text(
            text = label,
            style = NaytiTheme.type.labelL,
            color = if (selected) colors.onAccentContainer else colors.inkMuted,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomChrome(
    backdrop: NaytiBackdrop,
    material: ChromeMaterial,
) {
    val colors = NaytiTheme.colors
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(
                start = NaytiSpacing.Medium,
                end = NaytiSpacing.Medium,
                bottom = SystemBarBottom + NaytiSpacing.Medium,
            ),
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            backdrop = backdrop,
            material = material,
            shape = RoundedCornerShape(28.dp),
            hairlineOnTop = false,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NaytiSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(NaytiSpacing.MinTouchTarget)
                        .clip(NaytiTheme.shapes.control)
                        .background(colors.surfaceHigh)
                        .padding(horizontal = NaytiSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                ) {
                    MockIconMark(MockIcon.Search, colors.inkMuted, 20.dp)
                    Text(
                        text = "Найти по тексту, смыслу или содержимому",
                        style = NaytiTheme.type.bodyM,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ChromeIconButton(MockIcon.Filters)
                ChromeIconButton(MockIcon.Methods)
            }
        }
    }
}

@Composable
private fun ChromeIconButton(icon: MockIcon) {
    val colors = NaytiTheme.colors
    Box(
        modifier = Modifier
            .size(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(colors.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        MockIconMark(icon, colors.ink, 20.dp)
    }
}

@Composable
fun MockChromeComparison() {
    val colors = NaytiTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        ChromeSample("Стекло: размытие + тон", ChromeMaterial.Glass, Modifier.weight(1f))
        ChromeSample("Плотный вариант: только тон", ChromeMaterial.Solid, Modifier.weight(1f))
    }
}

@Composable
private fun ChromeSample(label: String, material: ChromeMaterial, modifier: Modifier) {
    val colors = NaytiTheme.colors
    val backdrop = rememberNaytiBackdrop()
    Box(modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().naytiBackdropSource(backdrop),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.PhotoGutter),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.PhotoGutter),
        ) {
            items((50..73).toList(), key = { it }) { index -> PhotoTile(index) }
        }
        GlassSurface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(NaytiSpacing.Medium),
            backdrop = backdrop,
            material = material,
            shape = RoundedCornerShape(28.dp),
            hairlineOnTop = false,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(NaytiChrome.BarHeight).padding(horizontal = NaytiSpacing.Screen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
            ) {
                MockIconMark(MockIcon.Search, colors.ink, 20.dp)
                Text(label, style = NaytiTheme.type.bodyL, color = colors.ink, maxLines = 1)
            }
        }
    }
}
