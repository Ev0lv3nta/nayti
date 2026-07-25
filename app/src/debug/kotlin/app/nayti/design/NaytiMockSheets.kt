package app.nayti.design

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

private val SheetHandleWidth = 36.dp

/**
 * Preparation state, expanded.
 *
 * The headline is qualitative on purpose: exact numbers exist per method and are taken from the
 * coverage the runtime already computes. Nothing here derives a combined readiness figure.
 */
@Composable
fun MockPreparationSheet() {
    val colors = NaytiTheme.colors
    Box(Modifier.fillMaxSize()) {
        MockLibrary(material = ChromeMaterial.Solid)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(NaytiTheme.shapes.sheet)
                .background(colors.background)
                .padding(bottom = 32.dp),
        ) {
            Box(
                Modifier
                    .padding(top = NaytiSpacing.Medium)
                    .align(Alignment.CenterHorizontally)
                    .size(width = SheetHandleWidth, height = 4.dp)
                    .clip(NaytiTheme.shapes.control)
                    .background(colors.outline),
            )
            Column(
                modifier = Modifier.padding(NaytiSpacing.Screen),
                verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
            ) {
                Text("Поиск уже работает", style = NaytiTheme.type.titleL, color = colors.ink)
                Text(
                    text = "Подготовка продолжается. Прогресс сохраняется — приложение можно закрыть.",
                    style = NaytiTheme.type.bodyM,
                    color = colors.inkMuted,
                )
                PrimaryAction("Приостановить", MockIcon.Clock)
                Spacer(Modifier.height(NaytiSpacing.XSmall))
                ChannelRow(MockIcon.TextLines, "Текст на фото", 1420, 1423, 99, 0)
                ChannelRow(MockIcon.Meaning, "Смысл текста", 1303, 1423, 91, 0)
                ChannelRow(MockIcon.Scene, "Что на фото", 1057, 1423, 74, 0)
                ChannelRow(MockIcon.Copies, "Копии кадра", 1411, 1423, 99, 12)
                Spacer(Modifier.height(NaytiSpacing.XSmall))
                RowLink(MockIcon.Period, "Период: с 23 апреля 2026", "1 423 из 13 953")
                RowLink(MockIcon.Alert, "Повторить пропущенные", "12 фотографий")
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: String, icon: MockIcon) {
    val colors = NaytiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(colors.accent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MockIconMark(icon, colors.onAccent, 18.dp)
        Spacer(Modifier.width(NaytiSpacing.Small))
        Text(label, style = NaytiTheme.type.labelL, color = colors.onAccent)
    }
}

@Composable
private fun ChannelRow(
    icon: MockIcon,
    title: String,
    committed: Int,
    accessible: Int,
    percent: Int,
    skipped: Int,
) {
    val colors = NaytiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockIconMark(icon, colors.inkMuted, 18.dp)
            Spacer(Modifier.width(NaytiSpacing.Small))
            Text(title, style = NaytiTheme.type.bodyL, color = colors.ink, modifier = Modifier.weight(1f))
            Text("$percent %", style = NaytiTheme.type.numM, color = colors.inkMuted)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(NaytiTheme.shapes.control)
                .background(colors.hairline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(3.dp)
                    .clip(NaytiTheme.shapes.control)
                    .background(if (percent >= 100) colors.ready else colors.accent),
            )
        }
        Text(
            text = buildString {
                append("готово ")
                append(committed.grouped())
                append(" из ")
                append(accessible.grouped())
                if (skipped > 0) {
                    append(" · пропущено ")
                    append(skipped)
                }
            },
            style = NaytiTheme.type.labelL,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun RowLink(icon: MockIcon, title: String, trailing: String) {
    val colors = NaytiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NaytiTheme.shapes.card)
            .background(colors.surface)
            .padding(horizontal = NaytiSpacing.Medium, vertical = NaytiSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockIconMark(icon, colors.inkMuted, 18.dp)
        Spacer(Modifier.width(NaytiSpacing.Small))
        Text(title, style = NaytiTheme.type.bodyM, color = colors.ink, modifier = Modifier.weight(1f))
        Text(trailing, style = NaytiTheme.type.numM, color = colors.inkFaint)
        Spacer(Modifier.width(NaytiSpacing.Small))
        MockIconMark(MockIcon.ChevronRight, colors.inkFaint, 16.dp)
    }
}

/** Settings: grouped, product wording, destructive action naming its own consequence. */
@Composable
fun MockSettings() {
    val colors = NaytiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
    ) {
        Spacer(Modifier.height(56.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockIconMark(MockIcon.Back, colors.ink, 22.dp)
            Spacer(Modifier.width(NaytiSpacing.Medium))
            Text("Настройки", style = NaytiTheme.type.titleL, color = colors.ink)
        }
        SettingsGroup("Медиатека") {
            SettingsRow(MockIcon.Shield, "Доступ к фотографиям", "Вся медиатека")
            SettingsRow(MockIcon.Period, "Период подготовки", "с 23 апр 2026")
        }
        SettingsGroup("Поиск и данные") {
            SettingsRow(MockIcon.Search, "Данные поиска", "535 МБ")
            SettingsRow(MockIcon.Clock, "Скрытые данные", "12 фото")
            SettingsRow(MockIcon.Alert, "Удалить данные поиска", "поиск придётся подготовить заново", destructive = true)
        }
        SettingsGroup("Модели") {
            SettingsRow(MockIcon.Methods, "Версия моделей", "0.1.0-alpha.2")
            SettingsRow(MockIcon.Check, "Безопасный возврат", "недоступен")
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val colors = NaytiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
        Text(
            text = title,
            style = NaytiTheme.type.labelL,
            color = colors.inkFaint,
            modifier = Modifier.padding(start = NaytiSpacing.XSmall),
        )
        Column(
            Modifier.clip(NaytiTheme.shapes.card).background(colors.surface),
        ) { content() }
    }
}

@Composable
private fun SettingsRow(
    icon: MockIcon,
    title: String,
    trailing: String,
    destructive: Boolean = false,
) {
    val colors = NaytiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NaytiSpacing.Medium, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockIconMark(icon, if (destructive) colors.error else colors.inkMuted, 18.dp)
        Spacer(Modifier.width(NaytiSpacing.Medium))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = NaytiTheme.type.bodyL,
                color = if (destructive) colors.error else colors.ink,
            )
            if (destructive) {
                Text(trailing, style = NaytiTheme.type.bodyM, color = colors.inkFaint)
            }
        }
        if (!destructive) {
            Text(trailing, style = NaytiTheme.type.bodyM, color = colors.inkFaint)
            Spacer(Modifier.width(NaytiSpacing.Small))
            MockIconMark(MockIcon.ChevronRight, colors.inkFaint, 16.dp)
        }
    }
}

private fun Int.grouped(): String =
    toString().reversed().chunked(3).joinToString(" ").reversed()
