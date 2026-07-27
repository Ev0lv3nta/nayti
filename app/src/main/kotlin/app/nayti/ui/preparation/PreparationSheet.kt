package app.nayti.ui.preparation

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.SearchCapability
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

sealed interface ReadinessPeriodSelection {
    data class ExtendByMonths(val months: Long) : ReadinessPeriodSelection

    data object AllMedia : ReadinessPeriodSelection

    data class SinceDate(val epochMillis: Long) : ReadinessPeriodSelection
}

/**
 * Full child destination for preparation readiness.
 *
 * [onChangePeriod] owns the durable runtime orchestration. When preparation is running it must
 * perform the existing pause -> scope update -> resume sequence; this composable confirms that
 * transition before invoking the callback.
 */
@Composable
fun ReadinessScreen(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    onBack: () -> Unit,
    onRequestAccess: () -> Unit,
    onImportModels: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRetryGaps: () -> Unit,
    onChangePeriod: (ReadinessPeriodSelection) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = PreparationUiMapper.map(catalog, modelPack, indexing)
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var pendingPeriodChange by remember { mutableStateOf<ReadinessPeriodSelection?>(null) }

    ReadinessContent(
        state = state,
        indexing = indexing,
        showBack = true,
        showDetails = showDetails,
        onBack = onBack,
        onPrimaryAction = {
            dispatchPrimaryAction(
                action = state.primaryAction,
                onRequestAccess = onRequestAccess,
                onImportModels = onImportModels,
                onStart = onStart,
                onPause = onPause,
                onRetryGaps = onRetryGaps,
            )
        },
        onToggleDetails = { showDetails = !showDetails },
        onPeriodChange = { selection ->
            if (state.periodChangeRequiresPause) {
                pendingPeriodChange = selection
            } else {
                onChangePeriod(selection)
            }
        },
        onRetryGaps = onRetryGaps,
        onCancel = { confirmCancel = true },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )

    pendingPeriodChange?.let { selection ->
        AlertDialog(
            onDismissRequest = { pendingPeriodChange = null },
            title = { Text(stringResource(R.string.readiness_period_pause_title)) },
            text = { Text(stringResource(R.string.readiness_period_pause_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingPeriodChange = null
                        onChangePeriod(selection)
                    },
                ) {
                    Text(stringResource(R.string.readiness_period_pause_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPeriodChange = null }) {
                    Text(stringResource(R.string.readiness_keep_running))
                }
            },
        )
    }

    if (confirmCancel) {
        CancelPreparationDialog(
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                onCancel()
            },
        )
    }
}

/**
 * Compatibility adapter for the current root sheet. The full-screen [ReadinessScreen] should be
 * used by the new readiness route.
 */
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
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var pendingAfterPause by remember {
        mutableStateOf<ReadinessPeriodSelection?>(null)
    }
    var confirmPause by remember { mutableStateOf<ReadinessPeriodSelection?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.isRunning, pendingAfterPause) {
        val pending = pendingAfterPause
        if (!state.isRunning && pending != null) {
            pendingAfterPause = null
            dispatchLegacyPeriodSelection(pending, onSelectMonths, onSelectStartDate)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ReadinessContent(
            state = state,
            indexing = indexing,
            showBack = false,
            showDetails = showDetails,
            onBack = onDismiss,
            onPrimaryAction = {
                dispatchPrimaryAction(
                    action = state.primaryAction,
                    onRequestAccess = onRequestAccess,
                    onImportModels = onImportModels,
                    onStart = onStart,
                    onPause = onPause,
                    onRetryGaps = onRetryGaps,
                )
            },
            onToggleDetails = { showDetails = !showDetails },
            onPeriodChange = { selection ->
                if (state.periodChangeRequiresPause) {
                    confirmPause = selection
                } else {
                    dispatchLegacyPeriodSelection(selection, onSelectMonths, onSelectStartDate)
                }
            },
            onRetryGaps = onRetryGaps,
            onCancel = { confirmCancel = true },
            onOpenSettings = onOpenSettings,
            modifier = Modifier.fillMaxHeight(0.94f),
        )
    }

    confirmPause?.let { selection ->
        AlertDialog(
            onDismissRequest = { confirmPause = null },
            title = { Text(stringResource(R.string.readiness_period_pause_title)) },
            text = { Text(stringResource(R.string.readiness_period_pause_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPause = null
                        pendingAfterPause = selection
                        onPause()
                    },
                ) {
                    Text(stringResource(R.string.readiness_period_pause_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPause = null }) {
                    Text(stringResource(R.string.readiness_keep_running))
                }
            },
        )
    }

    if (confirmCancel) {
        CancelPreparationDialog(
            onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                onCancel()
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
    ReadinessContent(
        state = state,
        indexing = indexing,
        showBack = false,
        showDetails = showMore,
        onBack = {},
        onPrimaryAction = onPrimaryAction,
        onToggleDetails = onToggleMore,
        onPeriodChange = { onChangePeriod() },
        onRetryGaps = onRetryGaps,
        onCancel = onCancel,
        onOpenSettings = onOpenSettings,
        modifier = Modifier.fillMaxHeight(0.94f),
    )
}

@Composable
private fun ReadinessContent(
    state: PreparationUiState,
    indexing: OcrIndexingState,
    showBack: Boolean,
    showDetails: Boolean,
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    onToggleDetails: () -> Unit,
    onPeriodChange: (ReadinessPeriodSelection) -> Unit,
    onRetryGaps: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = NaytiSpacing.Screen,
                top = NaytiSpacing.Medium,
                end = NaytiSpacing.Screen,
                bottom = NaytiSpacing.Section,
            ),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
    ) {
        item {
            ReadinessHeader(showBack = showBack, onBack = onBack)
        }
        item {
            ReadinessStatusCard(state)
        }
        state.primaryAction?.let {
            item {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(it.label))
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.readiness_channels_title),
                modifier = Modifier.semantics { heading() },
                style = NaytiTheme.type.titleM,
                color = NaytiTheme.colors.ink,
            )
        }
        items(
            items = state.channels,
            key = { channel -> channel.capability },
        ) { channel ->
            ReadinessChannelCard(channel)
        }
        item {
            ReadinessPeriodCard(
                indexing = indexing,
                onExtendOneMonth = {
                    onPeriodChange(ReadinessPeriodSelection.ExtendByMonths(1))
                },
                onExtendThreeMonths = {
                    onPeriodChange(ReadinessPeriodSelection.ExtendByMonths(3))
                },
                onSelectAll = {
                    onPeriodChange(ReadinessPeriodSelection.AllMedia)
                },
                onSelectDate = {
                    showStartDatePicker(
                        initialMillis = indexing.scope.takenFromMillis,
                        onSelected = { millis ->
                            onPeriodChange(ReadinessPeriodSelection.SinceDate(millis))
                        },
                        context = context,
                    )
                },
            )
        }
        item {
            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (showDetails) {
                            R.string.readiness_hide_details
                        } else {
                            R.string.readiness_show_details
                        },
                    ),
                )
            }
        }
        if (showDetails) {
            if (state.canRetryGaps) {
                item {
                    OutlinedButton(
                        onClick = onRetryGaps,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.readiness_retry_gaps))
                    }
                }
            }
            if (state.canCancel) {
                item {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.readiness_cancel),
                            color = NaytiTheme.colors.error,
                        )
                    }
                }
            }
            item {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.readiness_open_settings))
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.readiness_progress_saved),
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun ReadinessHeader(
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                NaytiIconMark(
                    icon = NaytiIcon.Back,
                    color = NaytiTheme.colors.ink,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
        ) {
            Text(
                text = stringResource(R.string.readiness_title),
                modifier = Modifier.semantics { heading() },
                style = NaytiTheme.type.hero,
                color = NaytiTheme.colors.ink,
            )
            Text(
                text = stringResource(R.string.readiness_subtitle),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun ReadinessStatusCard(state: PreparationUiState) {
    Surface(
        color = NaytiTheme.colors.surface,
        contentColor = NaytiTheme.colors.ink,
        shape = NaytiTheme.shapes.card,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Screen),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                color = NaytiTheme.colors.surfaceHigh,
                contentColor = state.qualitativeStatus.color(),
                shape = NaytiTheme.shapes.control,
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NaytiIconMark(
                        icon = state.qualitativeStatus.icon,
                        size = 24.dp,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
            ) {
                Text(
                    text = stringResource(state.qualitativeStatus.title),
                    style = NaytiTheme.type.titleM,
                    color = state.qualitativeStatus.color(),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(state.qualitativeStatus.body(state.issue)),
                    style = NaytiTheme.type.bodyM,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun ReadinessChannelCard(channel: PreparationChannelUi) {
    val title = stringResource(channel.capability.title)
    val stateLabel =
        if (channel.hasRuntimeCoverage) {
            stringResource(
                R.string.readiness_channel_state,
                title,
                channel.ready,
                channel.total,
                channel.gaps,
            )
        } else {
            stringResource(R.string.readiness_channel_waiting_state, title)
        }
    val progress =
        if (channel.total <= 0) {
            0f
        } else {
            (channel.ready.toFloat() / channel.total.toFloat()).coerceIn(0f, 1f)
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NaytiTheme.colors.surface,
        contentColor = NaytiTheme.colors.ink,
        shape = NaytiTheme.shapes.card,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription = stateLabel
                    }
                    .padding(NaytiSpacing.Screen),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NaytiIconMark(
                    icon = channel.capability.icon,
                    color = NaytiTheme.colors.accent,
                    size = 22.dp,
                )
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = NaytiTheme.type.titleM,
                    fontWeight = FontWeight.SemiBold,
                )
                if (channel.hasRuntimeCoverage) {
                    Text(
                        text =
                            stringResource(
                                R.string.readiness_channel_counts,
                                channel.ready,
                                channel.total,
                            ),
                        style = NaytiTheme.type.labelL,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            progressBarRangeInfo =
                                androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                    current = channel.ready.toFloat(),
                                    range = 0f..channel.total.coerceAtLeast(0).toFloat(),
                                )
                        },
                color =
                    if (channel.gaps > 0) {
                        NaytiTheme.colors.attention
                    } else {
                        NaytiTheme.colors.ready
                    },
                trackColor = NaytiTheme.colors.surfaceHigh,
            )
            Text(
                text =
                    when {
                        !channel.hasRuntimeCoverage ->
                            stringResource(R.string.readiness_channel_waiting)
                        channel.gaps > 0 ->
                            stringResource(R.string.readiness_channel_gaps, channel.gaps)
                        else ->
                            stringResource(R.string.readiness_channel_no_gaps)
                    },
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun ReadinessPeriodCard(
    indexing: OcrIndexingState,
    onExtendOneMonth: () -> Unit,
    onExtendThreeMonths: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectDate: () -> Unit,
) {
    Surface(
        color = NaytiTheme.colors.surface,
        contentColor = NaytiTheme.colors.ink,
        shape = NaytiTheme.shapes.card,
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
                ) {
                    Text(
                        text = stringResource(R.string.readiness_period_title),
                        style = NaytiTheme.type.titleM,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = periodDescription(indexing),
                        style = NaytiTheme.type.bodyM,
                        color = NaytiTheme.colors.inkMuted,
                    )
                }
            }
            Text(
                text = stringResource(R.string.readiness_period_reuse),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.inkMuted,
            )
            Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
                OutlinedButton(
                    onClick = onExtendOneMonth,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.readiness_period_plus_one))
                }
                OutlinedButton(
                    onClick = onExtendThreeMonths,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.readiness_period_plus_three))
                }
                OutlinedButton(
                    onClick = onSelectAll,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = indexing.scope.takenFromMillis != null,
                ) {
                    Text(stringResource(R.string.readiness_period_all))
                }
                TextButton(
                    onClick = onSelectDate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.readiness_period_date))
                }
            }
            if (indexing.scope.unknownDateAssets > 0 && indexing.scope.takenFromMillis != null) {
                Text(
                    text =
                        stringResource(
                            R.string.readiness_period_unknown_dates,
                            indexing.scope.unknownDateAssets,
                        ),
                    style = NaytiTheme.type.labelS,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun CancelPreparationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.readiness_cancel_title)) },
        text = { Text(stringResource(R.string.readiness_cancel_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.readiness_cancel_confirm),
                    color = NaytiTheme.colors.onAccent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.readiness_cancel_keep))
            }
        },
    )
}

private fun dispatchPrimaryAction(
    action: PreparationPrimaryAction?,
    onRequestAccess: () -> Unit,
    onImportModels: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRetryGaps: () -> Unit,
) {
    when (action) {
        PreparationPrimaryAction.RequestAccess -> onRequestAccess()
        PreparationPrimaryAction.ImportModels -> onImportModels()
        PreparationPrimaryAction.Start,
        PreparationPrimaryAction.Continue,
        PreparationPrimaryAction.CheckAndContinue,
        -> onStart()
        PreparationPrimaryAction.Pause -> onPause()
        PreparationPrimaryAction.RetryGaps -> onRetryGaps()
        null -> Unit
    }
}

private fun dispatchLegacyPeriodSelection(
    selection: ReadinessPeriodSelection,
    onSelectMonths: (Long?) -> Unit,
    onSelectStartDate: (Long) -> Unit,
) {
    when (selection) {
        is ReadinessPeriodSelection.ExtendByMonths -> onSelectMonths(selection.months)
        ReadinessPeriodSelection.AllMedia -> onSelectMonths(null)
        is ReadinessPeriodSelection.SinceDate -> onSelectStartDate(selection.epochMillis)
    }
}

@Composable
private fun periodDescription(indexing: OcrIndexingState): String {
    val scope = indexing.scope
    val takenFromMillis = scope.takenFromMillis
    return if (takenFromMillis == null) {
        stringResource(R.string.readiness_period_all_description, scope.eligibleAssets)
    } else {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(takenFromMillis))
        stringResource(
            R.string.readiness_period_since_description,
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
            SearchCapability.TEXT -> R.string.readiness_channel_literal
            SearchCapability.MEANING -> R.string.readiness_channel_semantic
            SearchCapability.VISUAL -> R.string.readiness_channel_visual
            SearchCapability.DUPLICATES -> R.string.readiness_channel_duplicates
        }

private val SearchCapability.icon: NaytiIcon
    get() =
        when (this) {
            SearchCapability.TEXT -> NaytiIcon.Text
            SearchCapability.MEANING -> NaytiIcon.Meaning
            SearchCapability.VISUAL -> NaytiIcon.Scene
            SearchCapability.DUPLICATES -> NaytiIcon.Copies
        }

private val PreparationPrimaryAction.label: Int
    get() =
        when (this) {
            PreparationPrimaryAction.RequestAccess -> R.string.readiness_action_access
            PreparationPrimaryAction.ImportModels -> R.string.readiness_action_models
            PreparationPrimaryAction.Start -> R.string.readiness_action_start
            PreparationPrimaryAction.Continue -> R.string.readiness_action_continue
            PreparationPrimaryAction.Pause -> R.string.readiness_action_pause
            PreparationPrimaryAction.RetryGaps -> R.string.readiness_action_retry_gaps
            PreparationPrimaryAction.CheckAndContinue ->
                R.string.readiness_action_check_continue
        }

private val PreparationQualitativeStatus.title: Int
    get() =
        when (this) {
            PreparationQualitativeStatus.SearchWorksWhilePreparing ->
                R.string.readiness_status_search_works
            PreparationQualitativeStatus.Preparing -> R.string.readiness_status_preparing
            PreparationQualitativeStatus.Incomplete -> R.string.readiness_status_incomplete
            PreparationQualitativeStatus.Ready -> R.string.readiness_status_ready
            PreparationQualitativeStatus.CompletedWithGaps ->
                R.string.readiness_status_gaps
            PreparationQualitativeStatus.Paused -> R.string.readiness_status_paused
        }

private val PreparationQualitativeStatus.icon: NaytiIcon
    get() =
        when (this) {
            PreparationQualitativeStatus.Ready -> NaytiIcon.Check
            PreparationQualitativeStatus.CompletedWithGaps,
            PreparationQualitativeStatus.Paused,
            -> NaytiIcon.Alert
            else -> NaytiIcon.Info
        }

@Composable
private fun PreparationQualitativeStatus.color() =
    when (this) {
        PreparationQualitativeStatus.Ready,
        PreparationQualitativeStatus.SearchWorksWhilePreparing,
        -> NaytiTheme.colors.ready
        PreparationQualitativeStatus.CompletedWithGaps -> NaytiTheme.colors.attention
        else -> NaytiTheme.colors.ink
    }

private fun PreparationQualitativeStatus.body(issue: PreparationIssue?): Int =
    when (this) {
        PreparationQualitativeStatus.SearchWorksWhilePreparing ->
            R.string.readiness_status_search_works_body
        PreparationQualitativeStatus.Preparing -> R.string.readiness_status_preparing_body
        PreparationQualitativeStatus.Incomplete -> R.string.readiness_status_incomplete_body
        PreparationQualitativeStatus.Ready -> R.string.readiness_status_ready_body
        PreparationQualitativeStatus.CompletedWithGaps -> R.string.readiness_status_gaps_body
        PreparationQualitativeStatus.Paused ->
            when (issue) {
                PreparationIssue.Thermal -> R.string.readiness_status_paused_thermal
                PreparationIssue.Memory -> R.string.readiness_status_paused_memory
                PreparationIssue.Storage -> R.string.readiness_status_paused_storage
                PreparationIssue.BatterySaver -> R.string.readiness_status_paused_battery_saver
                PreparationIssue.BatteryLow -> R.string.readiness_status_paused_battery_low
                PreparationIssue.Charging -> R.string.readiness_status_paused_charging
                PreparationIssue.Runtime -> R.string.readiness_status_paused_runtime
                null -> R.string.readiness_status_paused_user
            }
    }
