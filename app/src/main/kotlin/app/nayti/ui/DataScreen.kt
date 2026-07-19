package app.nayti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nayti.BuildConfig
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.platform.media.MediaAccessScope
import app.nayti.ui.theme.NaytiSpacing

@Composable
internal fun DataScreen(
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = stringResource(R.string.data_eyebrow),
                title = stringResource(R.string.data_title),
                subtitle = stringResource(R.string.data_subtitle),
            )
        }
        item {
            SettingsCard(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.privacy_title),
                body = stringResource(R.string.privacy_details),
            )
        }
        item {
            IndexingScopeCard(
                indexing = indexing,
                onSelectMonths = onSelectIndexingMonths,
                onSelectStartDate = onSelectIndexingStartDate,
            )
        }
        item {
            DataControlCard(
                icon = Icons.Outlined.Build,
                title = stringResource(R.string.reset_index_title),
                body = searchDataResetDescription(searchDataReset),
                actionLabel = stringResource(R.string.reset_index_action),
                onAction = { showResetConfirmation = true },
                actionEnabled = searchDataReset != SearchDataResetState.Resetting,
            )
        }
        item {
            DataControlCard(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.diagnostics_title),
                body = diagnosticsDescription(diagnosticsExport),
                actionLabel = stringResource(R.string.diagnostics_export),
                onAction = onExportDiagnostics,
                actionEnabled = diagnosticsExport != DiagnosticsExportState.Writing,
            )
        }
        item {
            SettingsCard(
                icon = Icons.Outlined.CheckCircle,
                title = stringResource(R.string.storage_title),
                body = stringResource(
                    R.string.storage_details,
                    formatStorage(localStorage.indexBytes),
                    formatStorage(localStorage.modelBytes),
                ),
            )
        }
        item {
            DataControlCard(
                icon = Icons.AutoMirrored.Outlined.List,
                title = stringResource(R.string.catalog_data_title),
                body = stringResource(R.string.catalog_data_details),
                actionLabel = stringResource(
                    if (catalog.access.permission.scope == MediaAccessScope.None) {
                        R.string.connect_library
                    } else {
                        R.string.change_selection
                    },
                ),
                onAction = onRequestAccess,
            )
        }
        item {
            val hiddenCount = catalog.summary.retainedQuarantine
            if (hiddenCount == 0L) {
                SettingsCard(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.quarantine_title),
                    body = stringResource(R.string.quarantine_empty),
                )
            } else {
                DataControlCard(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.quarantine_title),
                    body = pluralStringResource(
                        R.plurals.quarantine_count,
                        hiddenCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        hiddenCount,
                    ),
                    actionLabel = stringResource(R.string.quarantine_delete_now),
                    onAction = { showResetConfirmation = true },
                    actionEnabled = searchDataReset != SearchDataResetState.Resetting,
                )
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.model_pack_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    HorizontalDivider()
                    Text(
                        text = modelPackDescription(modelPack),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    modelPack.candidate?.takeIf { candidate ->
                        candidate.packVersion != modelPack.installed?.packVersion
                    }?.let { candidate ->
                        Text(
                            text = stringResource(R.string.model_pack_candidate, candidate.packVersion),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Button(
                        onClick = onImportModelPack,
                        enabled = modelPack.status != ModelPackRuntimeStatus.Installing,
                    ) {
                        Text(
                            stringResource(
                                if (modelPack.installed == null) {
                                    R.string.model_pack_import
                                } else {
                                    R.string.model_pack_replace
                                },
                            ),
                        )
                    }
                }
            }
        }
        item {
            ModelPackRollbackCard(
                state = modelPackRollback,
                onRollback = onRollbackModelPack,
            )
        }
        item {
            SettingsCard(
                icon = Icons.Outlined.CheckCircle,
                title = stringResource(R.string.about_title),
                body = stringResource(R.string.about_details, BuildConfig.VERSION_NAME),
            )
        }
    }
}

@Composable
private fun ModelPackRollbackCard(
    state: ModelPackRollbackState,
    onRollback: () -> Unit,
) {
    val body = modelPackRollbackDescription(state)
    val targetVersion = when (state) {
        is ModelPackRollbackState.Available -> state.targetVersion
        is ModelPackRollbackState.Failed -> state.targetVersion
        is ModelPackRollbackState.RollingBack -> state.targetVersion
        else -> null
    }
    if (targetVersion == null) {
        SettingsCard(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.model_pack_rollback_title),
            body = body,
        )
    } else {
        DataControlCard(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.model_pack_rollback_title),
            body = body,
            actionLabel = stringResource(R.string.model_pack_rollback_action, targetVersion),
            onAction = onRollback,
            actionEnabled = state !is ModelPackRollbackState.RollingBack,
        )
    }
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
private fun searchDataResetDescription(state: SearchDataResetState): String = stringResource(
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
private fun DataControlCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun diagnosticsDescription(state: DiagnosticsExportState): String = stringResource(
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

@Composable
private fun SettingsCard(icon: ImageVector, title: String, body: String) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
