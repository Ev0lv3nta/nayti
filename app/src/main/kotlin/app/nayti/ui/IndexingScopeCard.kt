package app.nayti.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.storage.IndexingScopeMode
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

@Composable
internal fun IndexingScopeCard(
    indexing: OcrIndexingState,
    onSelectMonths: (Long?) -> Unit,
    onSelectStartDate: (Long) -> Unit,
) {
    val summary = indexing.scope
    val enabled = indexing.status != OcrIndexingStatus.Running
    val context = LocalContext.current
    val initial =
        summary.takenFromMillis?.let { millis ->
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        } ?: ZonedDateTime.now(ZoneId.systemDefault()).minusMonths(3)
    val datePicker =
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val selected =
                    ZonedDateTime.of(
                        year,
                        month + 1,
                        day,
                        0,
                        0,
                        0,
                        0,
                        ZoneId.systemDefault(),
                    ).toInstant().toEpochMilli()
                onSelectStartDate(selected)
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }

    Surface(
        shape = NaytiTheme.shapes.card,
        color = NaytiTheme.colors.surface,
        contentColor = NaytiTheme.colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Screen),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NaytiIconMark(
                    icon = NaytiIcon.Period,
                    color = NaytiTheme.colors.accent,
                    size = 22.dp,
                )
                Text(
                    text = stringResource(R.string.indexing_scope_title),
                    style = NaytiTheme.type.titleM,
                )
            }
            Text(
                text = indexingScopeDescription(indexing),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.inkMuted,
            )
            indexing.estimatedAllMediaDurationMillis?.let { estimate ->
                Text(
                    text = stringResource(
                        R.string.indexing_scope_estimate,
                        formatDuration(indexing.activeDurationMillis),
                        formatDuration(estimate),
                    ),
                    color = NaytiTheme.colors.accent,
                    style = NaytiTheme.type.labelL,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
            ) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = summary.mode == IndexingScopeMode.ALL,
                    onClick = { onSelectMonths(null) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.indexing_scope_all)) },
                )
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = summary.mode == IndexingScopeMode.SINCE_DATE,
                    onClick = datePicker::show,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.indexing_scope_since_date)) },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
                listOf(1L, 3L, 6L, 12L).chunked(2).forEach { presets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                    ) {
                        presets.forEach { months ->
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectMonths(months) },
                                enabled = enabled,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = NaytiSpacing.Medium,
                                ),
                            ) {
                                Text(stringResource(R.string.indexing_scope_months_short, months))
                            }
                        }
                    }
                }
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.indexing_scope_pause_first),
                    style = NaytiTheme.type.labelS,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
            if (summary.mode == IndexingScopeMode.SINCE_DATE && summary.unknownDateAssets > 0) {
                Text(
                    text = stringResource(R.string.indexing_scope_unknown_dates, summary.unknownDateAssets),
                    style = NaytiTheme.type.labelS,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun formatDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis.coerceAtLeast(0) + 30_000) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0L) {
        stringResource(R.string.indexing_scope_duration_minutes, totalMinutes)
    } else {
        stringResource(R.string.indexing_scope_duration_hours, hours, minutes)
    }
}

@Composable
private fun indexingScopeDescription(indexing: OcrIndexingState): String {
    val summary = indexing.scope
    return if (summary.mode == IndexingScopeMode.ALL) {
        pluralStringResource(
            R.plurals.indexing_scope_all_details,
            summary.eligibleAssets.asQuantity(),
            summary.eligibleAssets,
        )
    } else {
        val formatted =
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(checkNotNull(summary.takenFromMillis)))
        pluralStringResource(
            R.plurals.indexing_scope_since_details,
            summary.eligibleAssets.asQuantity(),
            formatted,
            summary.eligibleAssets,
            summary.totalAvailable,
        )
    }
}

private fun Long.asQuantity(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
