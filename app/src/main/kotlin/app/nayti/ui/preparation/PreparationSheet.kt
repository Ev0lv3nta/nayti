package app.nayti.ui.preparation

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.SearchCapability
import app.nayti.indexer.SearchCapabilityCoverage
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme
import app.nayti.ui.shell.ShellStatusMessage
import app.nayti.ui.shell.stringResource
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.floor

private enum class PreparationSheetPage {
    Overview,
    Period,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreparationSheet(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    onDismiss: () -> Unit,
    onRequestAccess: () -> Unit,
    onImportModels: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRetryGaps: () -> Unit,
    onSelectMonths: (Long?) -> Unit,
    onSelectStartDate: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state = PreparationUiMapper.map(catalog, modelPack, indexing)
    var page by rememberSaveable { mutableStateOf(PreparationSheetPage.Overview) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmPauseForPeriod by rememberSaveable { mutableStateOf(false) }
    var openPeriodAfterPause by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(indexing.status, openPeriodAfterPause) {
        if (openPeriodAfterPause && !state.isRunning) {
            openPeriodAfterPause = false
            page = PreparationSheetPage.Period
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when (page) {
            PreparationSheetPage.Overview -> {
                PreparationOverview(
                    state = state,
                    indexing = indexing,
                    showMore = showMore,
                    onPrimaryAction = {
                        when (state.primaryAction) {
                            PreparationPrimaryAction.RequestAccess -> onRequestAccess()
                            PreparationPrimaryAction.ImportModels -> onImportModels()
                            PreparationPrimaryAction.Start -> onStart()
                            PreparationPrimaryAction.Pause -> onPause()
                            PreparationPrimaryAction.RetryGaps -> onRetryGaps()
                            null -> Unit
                        }
                    },
                    onToggleMore = { showMore = !showMore },
                    onChangePeriod = {
                        if (state.periodChangeRequiresPause) {
                            confirmPauseForPeriod = true
                        } else {
                            page = PreparationSheetPage.Period
                        }
                    },
                    onRetryGaps = onRetryGaps,
                    onCancel = { confirmCancel = true },
                    onOpenSettings = onOpenSettings,
                )
            }
            PreparationSheetPage.Period -> {
                PreparationPeriodPage(
                    indexing = indexing,
                    onBack = { page = PreparationSheetPage.Overview },
                    onSelectMonths = { months ->
                        onSelectMonths(months)
                        page = PreparationSheetPage.Overview
                    },
                    onSelectStartDate = { millis ->
                        onSelectStartDate(millis)
                        page = PreparationSheetPage.Overview
                    },
                )
            }
        }
    }

    if (confirmPauseForPeriod) {
        AlertDialog(
            onDismissRequest = { confirmPauseForPeriod = false },
            title = { Text(stringResource(R.string.preparation_period_pause_title)) },
            text = { Text(stringResource(R.string.preparation_period_pause_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPauseForPeriod = false
                        openPeriodAfterPause = true
                        onPause()
                    },
                ) {
                    Text(stringResource(R.string.preparation_period_pause_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPauseForPeriod = false }) {
                    Text(stringResource(R.string.preparation_keep_running))
                }
            },
        )
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.preparation_cancel_title)) },
            text = { Text(stringResource(R.string.preparation_cancel_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmCancel = false
                        onCancel()
                    },
                ) {
                    Text(stringResource(R.string.preparation_cancel_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.preparation_keep_task))
                }
            },
        )
    }
}

@Composable
internal fun PreparationOverview(
    state: PreparationUiState,
    indexing: OcrIndexingState,
    showMore: Boolean,
    onPrimaryAction: () -> Unit,
    onToggleMore: () -> Unit,
    onChangePeriod: () -> Unit,
    onRetryGaps: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxHeight(0.94f),
        contentPadding = PaddingValues(
            start = NaytiSpacing.Screen,
            end = NaytiSpacing.Screen,
            bottom = NaytiSpacing.Section,
        ),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
    ) {
        item {
            Text(
                text = stringResource(R.string.preparation_sheet_title),
                modifier = Modifier.semantics { heading() },
                style = NaytiTheme.type.titleL,
                color = NaytiTheme.colors.ink,
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
            ) {
                Text(
                    text = stringResource(state.status.message.stringResource),
                    style = NaytiTheme.type.titleM,
                    color = statusColor(state.status.message),
                )
                Text(
                    text = stringResource(state.supportingText),
                    style = NaytiTheme.type.bodyM,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        }
        state.primaryAction?.let { action ->
            item {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(action.label(state.issue)))
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.preparation_channels_title),
                modifier = Modifier.semantics { heading() },
                style = NaytiTheme.type.titleM,
                color = NaytiTheme.colors.ink,
            )
        }
        if (indexing.capabilities.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.preparation_channels_waiting),
                    style = NaytiTheme.type.bodyM,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        } else {
            items(
                items = indexing.capabilities.sortedBy { coverage -> coverage.capability.ordinal },
                key = { coverage -> coverage.capability },
            ) { coverage ->
                PreparationChannelRow(coverage)
            }
        }
        item {
            HorizontalDivider(color = NaytiTheme.colors.hairline)
        }
        item {
            PreparationPeriodRow(
                indexing = indexing,
                onChangePeriod = onChangePeriod,
            )
        }
        item {
            TextButton(onClick = onToggleMore) {
                Text(
                    stringResource(
                        if (showMore) {
                            R.string.preparation_less_actions
                        } else {
                            R.string.preparation_more_actions
                        },
                    ),
                )
            }
        }
        if (showMore) {
            if (state.canRetryGaps) {
                item {
                    TextButton(onClick = onRetryGaps) {
                        Text(stringResource(R.string.preparation_retry_gaps))
                    }
                }
            }
            if (state.canCancel) {
                item {
                    TextButton(onClick = onCancel) {
                        Text(
                            text = stringResource(R.string.preparation_cancel_menu),
                            color = NaytiTheme.colors.error,
                        )
                    }
                }
            }
            item {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.preparation_open_settings))
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.preparation_saved_footer),
                modifier = Modifier.padding(top = NaytiSpacing.Small),
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun PreparationChannelRow(coverage: SearchCapabilityCoverage) {
    val title = stringResource(coverage.capability.title)
    val percent =
        if (coverage.accessible == 0L) {
            0
        } else {
            floor(coverage.committed.toDouble() * 100.0 / coverage.accessible.toDouble())
                .toInt()
                .coerceIn(0, 100)
        }
    val stateLabel =
        stringResource(
            R.string.preparation_channel_state_description,
            title,
            coverage.committed,
            coverage.accessible,
            percent,
            coverage.permanentGaps,
        )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = stateLabel },
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.preparation_channel_percent, percent),
                style = NaytiTheme.type.labelL,
                color = NaytiTheme.colors.ink,
            )
        }
        LinearProgressIndicator(
            progress = {
                if (coverage.accessible == 0L) {
                    0f
                } else {
                    (coverage.committed.toFloat() / coverage.accessible.toFloat()).coerceIn(0f, 1f)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            color = NaytiTheme.colors.ready,
            trackColor = NaytiTheme.colors.surfaceHigh,
        )
        Text(
            text =
                if (coverage.permanentGaps == 0L) {
                    stringResource(
                        R.string.preparation_channel_counts,
                        coverage.committed,
                        coverage.accessible,
                    )
                } else {
                    stringResource(
                        R.string.preparation_channel_counts_with_gaps,
                        coverage.committed,
                        coverage.accessible,
                        coverage.permanentGaps,
                    )
                },
            style = NaytiTheme.type.labelS,
            color = NaytiTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun PreparationPeriodRow(
    indexing: OcrIndexingState,
    onChangePeriod: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
        ) {
            Text(
                text = stringResource(R.string.preparation_period_title),
                style = NaytiTheme.type.labelL,
                color = NaytiTheme.colors.inkMuted,
            )
            Text(
                text = periodDescription(indexing),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.ink,
            )
        }
        TextButton(onClick = onChangePeriod) {
            Text(stringResource(R.string.preparation_period_change))
        }
    }
}

@Composable
private fun PreparationPeriodPage(
    indexing: OcrIndexingState,
    onBack: () -> Unit,
    onSelectMonths: (Long?) -> Unit,
    onSelectStartDate: (Long) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxHeight(0.78f)
            .padding(horizontal = NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.preparation_period_sheet_title),
            modifier = Modifier.semantics { heading() },
            style = NaytiTheme.type.titleL,
            color = NaytiTheme.colors.ink,
        )
        Text(
            text = stringResource(R.string.preparation_period_sheet_body),
            style = NaytiTheme.type.bodyM,
            color = NaytiTheme.colors.inkMuted,
        )
        Text(
            text = periodDescription(indexing),
            style = NaytiTheme.type.titleM,
            color = NaytiTheme.colors.ink,
        )
        listOf<Long?>(1, 3, 6, 12, null).forEach { months ->
            FilterChip(
                selected =
                    if (months == null) {
                        indexing.scope.takenFromMillis == null
                    } else {
                        false
                    },
                onClick = { onSelectMonths(months) },
                label = {
                    Text(
                        if (months == null) {
                            stringResource(R.string.indexing_scope_all)
                        } else {
                            stringResource(R.string.indexing_scope_months_short, months)
                        },
                    )
                },
            )
        }
        TextButton(
            onClick = {
                showStartDatePicker(
                    initialMillis = indexing.scope.takenFromMillis,
                    onSelected = onSelectStartDate,
                    context = context,
                )
            },
        ) {
            Text(stringResource(R.string.indexing_scope_since_date))
        }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.preparation_period_back))
        }
    }
}

@Composable
private fun periodDescription(indexing: OcrIndexingState): String {
    val scope = indexing.scope
    val takenFromMillis = scope.takenFromMillis
    return if (takenFromMillis == null) {
        stringResource(R.string.preparation_period_all, scope.eligibleAssets)
    } else {
        val date =
            DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(Date(takenFromMillis))
        stringResource(
            R.string.preparation_period_since,
            date,
            scope.eligibleAssets,
            scope.totalAvailable,
        )
    }
}

private fun showStartDatePicker(
    initialMillis: Long?,
    onSelected: (Long) -> Unit,
    context: android.content.Context,
) {
    val initial =
        initialMillis?.let { millis ->
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        } ?: ZonedDateTime.now(ZoneId.systemDefault()).minusMonths(3)
    DatePickerDialog(
        context,
        { _, year, month, day ->
            onSelected(
                ZonedDateTime.of(
                    year,
                    month + 1,
                    day,
                    0,
                    0,
                    0,
                    0,
                    ZoneId.systemDefault(),
                ).toInstant().toEpochMilli(),
            )
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).apply {
        datePicker.maxDate = System.currentTimeMillis()
    }.show()
}

private val SearchCapability.title: Int
    get() =
        when (this) {
            SearchCapability.TEXT -> R.string.capability_text_title
            SearchCapability.MEANING -> R.string.capability_meaning_title
            SearchCapability.VISUAL -> R.string.capability_visual_title
            SearchCapability.DUPLICATES -> R.string.capability_duplicates_title
        }

private val PreparationUiState.supportingText: Int
    get() =
        when (status.message) {
            ShellStatusMessage.NeedsAccess -> R.string.preparation_support_access
            ShellStatusMessage.NeedsModels,
            ShellStatusMessage.ModelsFailed,
            -> R.string.preparation_support_models
            ShellStatusMessage.SearchReadyPreparing -> R.string.preparation_support_running_ready
            ShellStatusMessage.Preparing -> R.string.preparation_support_running_empty
            ShellStatusMessage.PausedByUser -> R.string.preparation_support_paused_user
            ShellStatusMessage.PausedBySystem -> R.string.preparation_support_paused_system
            ShellStatusMessage.PausedThermal -> R.string.preparation_support_thermal
            ShellStatusMessage.PausedMemory -> R.string.preparation_support_memory
            ShellStatusMessage.PausedStorage -> R.string.preparation_support_storage
            ShellStatusMessage.PausedBatterySaver -> R.string.preparation_support_battery_saver
            ShellStatusMessage.PausedBatteryLow -> R.string.preparation_support_battery_low
            ShellStatusMessage.PausedCharging -> R.string.preparation_support_charging
            ShellStatusMessage.Completed -> R.string.preparation_support_completed
            ShellStatusMessage.CompletedWithGaps -> R.string.preparation_support_completed_gaps
            ShellStatusMessage.PreparationFailed,
            ShellStatusMessage.LibraryFailed,
            -> R.string.preparation_support_failed
            else -> R.string.preparation_support_saved
        }

private fun PreparationPrimaryAction.label(issue: PreparationIssue?): Int =
    when (this) {
        PreparationPrimaryAction.RequestAccess -> R.string.preparation_action_access
        PreparationPrimaryAction.ImportModels -> R.string.preparation_action_models
        PreparationPrimaryAction.Start ->
            if (issue == null) {
                R.string.preparation_action_start
            } else {
                R.string.preparation_action_retry
            }
        PreparationPrimaryAction.Pause -> R.string.preparation_action_pause
        PreparationPrimaryAction.RetryGaps -> R.string.preparation_retry_gaps
    }

@Composable
private fun statusColor(message: ShellStatusMessage) =
    when (message) {
        ShellStatusMessage.Completed,
        ShellStatusMessage.SearchAvailable,
        ShellStatusMessage.SearchReadyPreparing,
        -> NaytiTheme.colors.ready
        ShellStatusMessage.PreparationFailed,
        ShellStatusMessage.LibraryFailed,
        ShellStatusMessage.ModelsFailed,
        -> NaytiTheme.colors.error
        else -> NaytiTheme.colors.ink
    }
