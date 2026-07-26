package app.nayti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nayti.BuildConfig
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.platform.media.MediaAccessScope
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

@Composable
internal fun SettingsScreen(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    localStorage: LocalStorageSummary,
    diagnosticsExport: DiagnosticsExportState,
    searchDataReset: SearchDataResetState,
    modelPackRollback: ModelPackRollbackState,
    indexing: OcrIndexingState,
    onRequestAccess: () -> Unit,
    onImportModelPack: () -> Unit,
    onRefreshStorage: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onResetSearchData: () -> Unit,
    onRollbackModelPack: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit = {},
    onSelectIndexingStartDate: (Long) -> Unit = {},
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { onRefreshStorage() }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.reset_index_confirm_title)) },
            text = { Text(stringResource(R.string.reset_index_confirm_details)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmation = false
                        onResetSearchData()
                    },
                ) {
                    Text(stringResource(R.string.reset_index_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.reset_index_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = NaytiSpacing.Screen,
                top = NaytiSpacing.Section,
                end = NaytiSpacing.Screen,
                bottom = 104.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Section),
    ) {
        item {
            ScreenHeader(
                eyebrow = stringResource(R.string.data_eyebrow),
                title = stringResource(R.string.data_title),
                subtitle = stringResource(R.string.data_subtitle),
            )
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_library_section)) {
                SettingsActionRow(
                    icon = NaytiIcon.Photos,
                    title = stringResource(R.string.catalog_data_title),
                    body = stringResource(R.string.catalog_data_details),
                    actionLabel =
                        stringResource(
                            if (catalog.access.permission.scope == MediaAccessScope.None) {
                                R.string.connect_library
                            } else {
                                R.string.change_selection
                            },
                        ),
                    onAction = onRequestAccess,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_period_section)) {
                IndexingScopeCard(
                    indexing = indexing,
                    onSelectMonths = onSelectIndexingMonths,
                    onSelectStartDate = onSelectIndexingStartDate,
                )
            }
        }

        item {
            val hiddenCount = catalog.summary.retainedQuarantine
            SettingsSection(title = stringResource(R.string.settings_search_data_section)) {
                SettingsInfoRow(
                    icon = NaytiIcon.Storage,
                    title = stringResource(R.string.storage_title),
                    body =
                        stringResource(
                            R.string.storage_details,
                            formatStorage(localStorage.indexBytes),
                            formatStorage(localStorage.modelBytes),
                        ),
                )
                HorizontalDivider(color = NaytiTheme.colors.hairline)
                SettingsActionRow(
                    icon = if (hiddenCount == 0L) NaytiIcon.Shield else NaytiIcon.Delete,
                    title = stringResource(R.string.quarantine_title),
                    body =
                        if (hiddenCount == 0L) {
                            stringResource(R.string.quarantine_empty)
                        } else {
                            pluralStringResource(
                                R.plurals.quarantine_count,
                                hiddenCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                hiddenCount,
                            )
                        },
                    actionLabel =
                        if (hiddenCount == 0L) {
                            null
                        } else {
                            stringResource(R.string.quarantine_reset_action)
                        },
                    onAction = { showResetConfirmation = true },
                    actionEnabled = searchDataReset != SearchDataResetState.Resetting,
                    destructive = hiddenCount > 0L,
                )
                HorizontalDivider(color = NaytiTheme.colors.hairline)
                SettingsActionRow(
                    icon = NaytiIcon.Delete,
                    title = stringResource(R.string.reset_index_title),
                    body = searchDataResetDescription(searchDataReset),
                    actionLabel = stringResource(R.string.reset_index_action),
                    onAction = { showResetConfirmation = true },
                    actionEnabled = searchDataReset != SearchDataResetState.Resetting,
                    destructive = true,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_models_section)) {
                SettingsActionRow(
                    icon = NaytiIcon.Models,
                    title = stringResource(R.string.model_pack_title),
                    body = modelPackDescription(modelPack),
                    detail =
                        modelPack.candidate
                            ?.takeIf { it.packVersion != modelPack.installed?.packVersion }
                            ?.let { stringResource(R.string.model_pack_candidate, it.packVersion) },
                    actionLabel =
                        stringResource(
                            if (modelPack.installed == null) {
                                R.string.model_pack_import
                            } else {
                                R.string.model_pack_replace
                            },
                        ),
                    onAction = onImportModelPack,
                    actionEnabled = modelPack.status != ModelPackRuntimeStatus.Installing,
                )
                HorizontalDivider(color = NaytiTheme.colors.hairline)
                ModelPackRollbackRow(modelPackRollback, onRollbackModelPack)
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_privacy_section)) {
                SettingsInfoRow(
                    icon = NaytiIcon.Shield,
                    title = stringResource(R.string.privacy_title),
                    body = stringResource(R.string.privacy_details),
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_about_section)) {
                SettingsInfoRow(
                    icon = NaytiIcon.About,
                    title = stringResource(R.string.about_title),
                    body = stringResource(R.string.about_details, BuildConfig.VERSION_NAME),
                )
                HorizontalDivider(color = NaytiTheme.colors.hairline)
                SettingsActionRow(
                    icon = NaytiIcon.Export,
                    title = stringResource(R.string.diagnostics_title),
                    body = diagnosticsDescription(diagnosticsExport),
                    actionLabel = stringResource(R.string.diagnostics_export),
                    onAction = onExportDiagnostics,
                    actionEnabled = diagnosticsExport != DiagnosticsExportState.Writing,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
        Text(
            text = title,
            style = NaytiTheme.type.labelL,
            color = NaytiTheme.colors.inkMuted,
            modifier = Modifier.padding(horizontal = NaytiSpacing.XSmall).semantics { heading() },
        )
        Surface(
            shape = NaytiTheme.shapes.card,
            color = NaytiTheme.colors.surface,
            contentColor = NaytiTheme.colors.ink,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: NaytiIcon,
    title: String,
    body: String,
) {
    SettingsRowLayout(icon = icon, title = title, body = body)
}

@Composable
private fun SettingsActionRow(
    icon: NaytiIcon,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    destructive: Boolean = false,
    detail: String? = null,
) {
    SettingsRowLayout(
        icon = icon,
        title = title,
        body = body,
        detail = detail,
    ) {
        if (actionLabel != null) {
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = actionLabel,
                    color =
                        if (destructive && actionEnabled) {
                            NaytiTheme.colors.error
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        },
                )
            }
        }
    }
}

@Composable
private fun SettingsRowLayout(
    icon: NaytiIcon,
    title: String,
    body: String,
    detail: String? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                color = NaytiTheme.colors.surfaceHigh,
                contentColor = NaytiTheme.colors.accent,
                shape = NaytiTheme.shapes.control,
            ) {
                Row(modifier = Modifier.padding(NaytiSpacing.Medium)) {
                    NaytiIconMark(icon = icon, size = 22.dp)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
            ) {
                Text(title, style = NaytiTheme.type.titleM, fontWeight = FontWeight.SemiBold)
                Text(body, style = NaytiTheme.type.bodyM, color = NaytiTheme.colors.inkMuted)
                detail?.let {
                    Text(it, style = NaytiTheme.type.labelS, color = NaytiTheme.colors.accent)
                }
            }
        }
        footer?.invoke()
    }
}

@Composable
private fun ModelPackRollbackRow(
    state: ModelPackRollbackState,
    onRollback: () -> Unit,
) {
    val targetVersion =
        when (state) {
            is ModelPackRollbackState.Available -> state.targetVersion
            is ModelPackRollbackState.Failed -> state.targetVersion
            is ModelPackRollbackState.RollingBack -> state.targetVersion
            else -> null
        }
    SettingsActionRow(
        icon = NaytiIcon.Clock,
        title = stringResource(R.string.model_pack_rollback_title),
        body = modelPackRollbackDescription(state),
        actionLabel =
            targetVersion?.let { stringResource(R.string.model_pack_rollback_action, it) },
        onAction = onRollback,
        actionEnabled = state !is ModelPackRollbackState.RollingBack,
    )
}

@Composable
private fun modelPackRollbackDescription(state: ModelPackRollbackState): String =
    when (state) {
        ModelPackRollbackState.Loading -> stringResource(R.string.model_pack_rollback_loading)
        is ModelPackRollbackState.Unavailable ->
            if (state.rollbackCompleted) {
                stringResource(R.string.model_pack_rollback_succeeded, state.activeVersion.orEmpty())
            } else {
                stringResource(R.string.model_pack_rollback_unavailable)
            }
        is ModelPackRollbackState.Available ->
            if (state.rollbackCompleted) {
                stringResource(
                    R.string.model_pack_rollback_succeeded_with_previous,
                    state.activeVersion,
                    state.targetVersion,
                )
            } else {
                stringResource(
                    R.string.model_pack_rollback_available,
                    state.activeVersion,
                    state.targetVersion,
                )
            }
        is ModelPackRollbackState.RollingBack ->
            stringResource(R.string.model_pack_rollback_running, state.targetVersion)
        is ModelPackRollbackState.Failed ->
            stringResource(R.string.model_pack_rollback_failed, state.activeVersion)
    }

@Composable
private fun searchDataResetDescription(state: SearchDataResetState): String =
    stringResource(
        when (state) {
            SearchDataResetState.Idle -> R.string.reset_index_details
            SearchDataResetState.Resetting -> R.string.reset_index_running
            SearchDataResetState.Succeeded -> R.string.reset_index_succeeded
            SearchDataResetState.Failed -> R.string.reset_index_failed
        },
    )

@Composable
private fun formatStorage(bytes: Long): String {
    val mebibytes = bytes.coerceAtLeast(0L) / (1024L * 1024L)
    return if (mebibytes == 0L) {
        stringResource(R.string.storage_less_than_megabyte)
    } else {
        stringResource(R.string.storage_megabytes, mebibytes)
    }
}

@Composable
private fun diagnosticsDescription(state: DiagnosticsExportState): String =
    stringResource(
        when (state) {
            DiagnosticsExportState.Idle -> R.string.diagnostics_details
            DiagnosticsExportState.Writing -> R.string.diagnostics_writing
            DiagnosticsExportState.Saved -> R.string.diagnostics_saved
            DiagnosticsExportState.Failed -> R.string.diagnostics_failed
        },
    )

@Composable
private fun modelPackDescription(state: ModelPackRuntimeState): String =
    when (state.status) {
        ModelPackRuntimeStatus.Loading -> stringResource(R.string.model_pack_loading)
        ModelPackRuntimeStatus.Missing -> stringResource(R.string.model_pack_missing)
        ModelPackRuntimeStatus.Installing -> stringResource(R.string.model_pack_installing)
        ModelPackRuntimeStatus.Ready ->
            stringResource(
                R.string.model_pack_ready,
                state.installed?.packVersion.orEmpty(),
                (state.installed?.payloadBytes ?: 0) / (1024 * 1024),
            )
        ModelPackRuntimeStatus.Failed ->
            if (state.installed == null) {
                stringResource(R.string.model_pack_failed)
            } else {
                stringResource(
                    R.string.model_pack_failed_using_previous,
                    state.installed?.packVersion.orEmpty(),
                )
            }
    }
