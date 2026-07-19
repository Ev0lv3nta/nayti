package app.nayti.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.nayti.R
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.storage.IndexingScopeMode
import app.nayti.ui.theme.NaytiSpacing
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

    Card(shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.indexing_scope_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text = indexingScopeDescription(indexing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            indexing.estimatedAllMediaDurationMillis?.let { estimate ->
                Text(
                    text = stringResource(
                        R.string.indexing_scope_estimate,
                        formatDuration(indexing.activeDurationMillis),
                        formatDuration(estimate),
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
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
            Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact)) {
                listOf(1L, 3L, 6L, 12L).chunked(2).forEach { presets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
                    ) {
                        presets.forEach { months ->
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectMonths(months) },
                                enabled = enabled,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = NaytiSpacing.Item,
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
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.mode == IndexingScopeMode.SINCE_DATE && summary.unknownDateAssets > 0) {
                Text(
                    text = stringResource(R.string.indexing_scope_unknown_dates, summary.unknownDateAssets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
