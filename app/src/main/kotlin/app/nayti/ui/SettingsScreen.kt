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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

enum class SettingsThemeMode {
    System,
    Light,
    Dark,
}

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
    onStartIndexing: () -> Unit = {},
    onOpenPreparation: () -> Unit = {},
    onSelectIndexingMonths: (Long?) -> Unit = {},
    onSelectIndexingStartDate: (Long) -> Unit = {},
    themeMode: SettingsThemeMode = SettingsThemeMode.System,
    onThemeModeChange: (SettingsThemeMode) -> Unit = {},
) {
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showDestructive by rememberSaveable { mutableStateOf(false) }
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
                subtitle = stringResource(R.string.settings_redesign_subtitle),
            )
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_appearance_section)) {
                ThemeSettingsRow(
                    selectedMode = themeMode,
                    onSelected = onThemeModeChange,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_media_section)) {
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
                HorizontalDivider(color = NaytiTheme.colors.hairline)
                SettingsActionRow(
                    icon = NaytiIcon.Methods,
                    title = stringResource(R.string.settings_preparation_title),
                    body = preparationSettingsDescription(indexing),
                    actionLabel = stringResource(R.string.settings_preparation_action),
                    onAction = onOpenPreparation,
                )
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
            val hiddenCount = catalog.summary.retainedQuarantine
            SettingsSection(title = stringResource(R.string.settings_storage_section)) {
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
                if (hiddenCount > 0) {
                    HorizontalDivider(color = NaytiTheme.colors.hairline)
                    SettingsInfoRow(
                        icon = NaytiIcon.Info,
                        title = stringResource(R.string.settings_hidden_data_title),
                        body =
                            pluralStringResource(
                                R.plurals.quarantine_count,
                                hiddenCount.asQuantity(),
                                hiddenCount,
                            ),
                    )
                }
                HorizontalDivider(color = NaytiTheme.colors.hairline)
                SettingsDisclosureRow(
                    icon = NaytiIcon.Delete,
                    title =
                        stringResource(
                            if (showDestructive) {
                                R.string.settings_destructive_hide
                            } else {
                                R.string.settings_destructive_show
                            },
                        ),
                    body = stringResource(R.string.settings_destructive_body),
                    expanded = showDestructive,
                    onToggle = { showDestructive = !showDestructive },
                    destructive = true,
                )
                if (showDestructive) {
                    HorizontalDivider(color = NaytiTheme.colors.hairline)
                    SettingsActionRow(
                        icon = NaytiIcon.Delete,
                        title = stringResource(R.string.settings_destructive_title),
                        body = searchDataResetDescription(searchDataReset),
                        actionLabel = stringResource(R.string.reset_index_action),
                        onAction = { showResetConfirmation = true },
                        actionEnabled = searchDataReset != SearchDataResetState.Resetting,
                        destructive = true,
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_advanced_section)) {
                SettingsDisclosureRow(
                    icon = NaytiIcon.Settings,
                    title = stringResource(R.string.settings_advanced_title),
                    body = stringResource(R.string.settings_advanced_body),
                    expanded = showAdvanced,
                    onToggle = { showAdvanced = !showAdvanced },
                )
                if (showAdvanced) {
                    HorizontalDivider(color = NaytiTheme.colors.hairline)
                    SettingsActionRow(
                        icon = NaytiIcon.Models,
                        title = stringResource(R.string.settings_search_components_title),
                        body = searchComponentsDescription(modelPack),
                        detail =
                            modelPack.candidate
                                ?.takeIf { it.packVersion != modelPack.installed?.packVersion }
                                ?.let { stringResource(R.string.model_pack_candidate, it.packVersion) },
                        actionLabel =
                            stringResource(
                                if (modelPack.installed == null) {
                                    R.string.settings_search_components_choose
                                } else {
                                    R.string.settings_search_components_replace
                                },
                            ),
                        onAction = onImportModelPack,
                        actionEnabled = modelPack.status != ModelPackRuntimeStatus.Installing,
                    )
                    if (modelPackRollback.isVisible) {
                        HorizontalDivider(color = NaytiTheme.colors.hairline)
                        PreviousVersionRow(
                            state = modelPackRollback,
                            onRollback = onRollbackModelPack,
                        )
                    }
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

        item {
            SettingsSection(title = stringResource(R.string.settings_about_section)) {
                SettingsInfoRow(
                    icon = NaytiIcon.About,
                    title = stringResource(R.string.about_title),
                    body = stringResource(R.string.about_details, BuildConfig.VERSION_NAME),
                )
            }
        }
    }
}

@Composable
private fun ThemeSettingsRow(
    selectedMode: SettingsThemeMode,
    onSelected: (SettingsThemeMode) -> Unit,
) {
    SettingsRowLayout(
        icon = NaytiIcon.Settings,
        title = stringResource(R.string.settings_theme_title),
        body = stringResource(R.string.settings_theme_body),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
        ) {
            SettingsThemeMode.entries.forEach { mode ->
                ThemeChoiceButton(
                    mode = mode,
                    selected = selectedMode == mode,
                    onClick = { onSelected(mode) },
                )
            }
        }
    }
}

@Composable
private fun ThemeChoiceButton(
    mode: SettingsThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(mode.label)
    val selectedDescription = stringResource(R.string.settings_theme_selected, label)
    val modifier =
        Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                if (selected) {
                    stateDescription = selectedDescription
                }
            }
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            NaytiIconMark(icon = NaytiIcon.Check, size = 18.dp)
            Text(
                text = label,
                modifier = Modifier.padding(start = NaytiSpacing.Small),
            )
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label)
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
        actionLabel?.let { label ->
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = label,
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
private fun SettingsDisclosureRow(
    icon: NaytiIcon,
    title: String,
    body: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    destructive: Boolean = false,
) {
    SettingsRowLayout(
        icon = icon,
        title = title,
        body = body,
    ) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text =
                    if (expanded) {
                        stringResource(R.string.settings_advanced_hide)
                    } else {
                        stringResource(R.string.settings_advanced_show)
                    },
                color =
                    if (destructive) {
                        NaytiTheme.colors.error
                    } else {
                        androidx.compose.ui.graphics.Color.Unspecified
                    },
            )
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
private fun PreviousVersionRow(
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
        title = stringResource(R.string.settings_previous_version_title),
        body = previousVersionDescription(state),
        actionLabel =
            targetVersion?.let {
                stringResource(R.string.settings_previous_version_action, it)
            },
        onAction = onRollback,
        actionEnabled = state !is ModelPackRollbackState.RollingBack,
    )
}

@Composable
private fun previousVersionDescription(state: ModelPackRollbackState): String =
    when (state) {
        is ModelPackRollbackState.Available ->
            stringResource(
                R.string.settings_previous_version_available,
                state.activeVersion,
                state.targetVersion,
            )
        is ModelPackRollbackState.RollingBack ->
            stringResource(R.string.settings_previous_version_running, state.targetVersion)
        is ModelPackRollbackState.Failed ->
            stringResource(
                R.string.settings_previous_version_failed,
                state.activeVersion,
                state.targetVersion,
            )
        ModelPackRollbackState.Loading -> stringResource(R.string.model_pack_rollback_loading)
        is ModelPackRollbackState.Unavailable -> ""
    }

private val ModelPackRollbackState.isVisible: Boolean
    get() = this !is ModelPackRollbackState.Unavailable

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
    when (state) {
        DiagnosticsExportState.Idle -> stringResource(R.string.settings_diagnostics_body)
        DiagnosticsExportState.Writing -> stringResource(R.string.diagnostics_writing)
        DiagnosticsExportState.Saved -> stringResource(R.string.diagnostics_saved)
        DiagnosticsExportState.Failed -> stringResource(R.string.diagnostics_failed)
    }

@Composable
private fun searchComponentsDescription(state: ModelPackRuntimeState): String =
    when (state.status) {
        ModelPackRuntimeStatus.Loading ->
            stringResource(R.string.settings_search_components_loading)
        ModelPackRuntimeStatus.Missing ->
            stringResource(R.string.settings_search_components_missing)
        ModelPackRuntimeStatus.Installing ->
            stringResource(R.string.settings_search_components_installing)
        ModelPackRuntimeStatus.Ready ->
            stringResource(
                R.string.settings_search_components_ready,
                state.installed?.packVersion.orEmpty(),
                (state.installed?.payloadBytes ?: 0) / (1024 * 1024),
            )
        ModelPackRuntimeStatus.Failed ->
            if (state.installed == null) {
                stringResource(R.string.settings_search_components_failed)
            } else {
                stringResource(
                    R.string.settings_search_components_failed_previous,
                    state.installed?.packVersion.orEmpty(),
                )
            }
    }

@Composable
private fun preparationSettingsDescription(
    indexing: OcrIndexingState,
): String {
    return if (indexing.scope.takenFromMillis == null) {
        stringResource(
            R.string.readiness_period_all_description,
            indexing.scope.eligibleAssets,
        )
    } else {
        stringResource(R.string.settings_preparation_body)
    }
}

private val SettingsThemeMode.label: Int
    get() =
        when (this) {
            SettingsThemeMode.System -> R.string.settings_theme_system
            SettingsThemeMode.Light -> R.string.settings_theme_light
            SettingsThemeMode.Dark -> R.string.settings_theme_dark
        }

private fun Long.asQuantity(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
