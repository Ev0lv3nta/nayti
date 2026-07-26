package app.nayti.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.platform.media.MediaAccessScope
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

@Composable
internal fun SetupScreen(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    onImportModelPack: () -> Unit,
    onRequestAccess: () -> Unit,
    onStartIndexing: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit = {},
    onSelectIndexingStartDate: (Long) -> Unit = {},
    onComplete: () -> Unit,
) {
    val accessGranted = catalog.access.permission.scope != MediaAccessScope.None
    val scopeMatchesCatalog = indexing.scope.totalAvailable == catalog.summary.available
    val selectedPhotoCount =
        if (scopeMatchesCatalog) indexing.scope.eligibleAssets else catalog.summary.available
    val modelBusy =
        modelPack.status in setOf(ModelPackRuntimeStatus.Loading, ModelPackRuntimeStatus.Installing)
    val action =
        SetupPolicy.next(
            SetupSnapshot(
                modelPackReady = modelPack.installed != null,
                modelPackBusy = modelBusy,
                photoAccessGranted = accessGranted,
                catalogReconciling = catalog.status == CatalogRuntimeStatus.Reconciling,
                availablePhotos = selectedPhotoCount,
                indexingRunning = indexing.status == OcrIndexingStatus.Running,
                indexingAccessible = indexing.accessible,
                indexingOutstanding = indexing.outstanding,
            ),
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(NaytiTheme.colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding =
                PaddingValues(
                    start = NaytiSpacing.Screen,
                    top = NaytiSpacing.Section,
                    end = NaytiSpacing.Screen,
                    bottom = NaytiSpacing.Section,
                ),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Section),
        ) {
            item { SetupBrand() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
                    Text(
                        text = stringResource(R.string.setup_eyebrow).uppercase(),
                        color = NaytiTheme.colors.accent,
                        style = NaytiTheme.type.labelL,
                    )
                    Text(
                        text = stringResource(R.string.setup_title),
                        style = NaytiTheme.type.hero,
                        color = NaytiTheme.colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.setup_subtitle),
                        style = NaytiTheme.type.bodyL,
                        color = NaytiTheme.colors.inkMuted,
                    )
                }
            }
            item { PrivacyPromise() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium)) {
                    Text(
                        text = stringResource(R.string.setup_steps_title),
                        style = NaytiTheme.type.titleL,
                        color = NaytiTheme.colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    SetupStepRow(
                        number = 1,
                        icon = NaytiIcon.Models,
                        title = stringResource(R.string.setup_pack_title),
                        body = setupPackDescription(modelPack),
                        complete = modelPack.installed != null,
                        busy = modelBusy,
                    )
                    SetupStepRow(
                        number = 2,
                        icon = NaytiIcon.Photos,
                        title = stringResource(R.string.setup_access_title),
                        body =
                            stringResource(
                                if (accessGranted) {
                                    R.string.setup_access_ready
                                } else {
                                    R.string.setup_access_pending
                                },
                            ),
                        complete = accessGranted,
                        busy = false,
                    )
                    SetupStepRow(
                        number = 3,
                        icon = NaytiIcon.Period,
                        title = stringResource(R.string.setup_prepare_title),
                        body = setupPreparationDescription(catalog, indexing, selectedPhotoCount),
                        complete =
                            accessGranted &&
                                catalog.status != CatalogRuntimeStatus.Reconciling &&
                                (selectedPhotoCount == 0L ||
                                    (indexing.accessible > 0 && indexing.outstanding == 0L)),
                        busy =
                            catalog.status == CatalogRuntimeStatus.Reconciling ||
                                indexing.status == OcrIndexingStatus.Running,
                    )
                }
            }
            if (accessGranted && catalog.status != CatalogRuntimeStatus.Reconciling) {
                item {
                    IndexingScopeCard(
                        indexing = indexing,
                        onSelectMonths = onSelectIndexingMonths,
                        onSelectStartDate = onSelectIndexingStartDate,
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.setup_skip_note),
                    style = NaytiTheme.type.bodyM,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        }
        SetupActions(
            action = action,
            onImportModelPack = onImportModelPack,
            onRequestAccess = onRequestAccess,
            onStartIndexing = onStartIndexing,
            onComplete = onComplete,
        )
    }
}

@Composable
private fun SetupBrand() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = NaytiTheme.type.titleL,
            color = NaytiTheme.colors.ink,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            color = NaytiTheme.colors.accentContainer,
            contentColor = NaytiTheme.colors.onAccentContainer,
            shape = NaytiTheme.shapes.control,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NaytiSpacing.Medium, vertical = NaytiSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NaytiIconMark(NaytiIcon.Shield, size = 18.dp)
                Text(stringResource(R.string.local_only), style = NaytiTheme.type.labelL)
            }
        }
    }
}

@Composable
private fun SetupActions(
    action: SetupNextAction,
    onImportModelPack: () -> Unit,
    onRequestAccess: () -> Unit,
    onStartIndexing: () -> Unit,
    onComplete: () -> Unit,
) {
    val waiting =
        action in
            setOf(
                SetupNextAction.WAIT_FOR_MODEL_PACK,
                SetupNextAction.WAIT_FOR_CATALOG,
                SetupNextAction.WAIT_FOR_PREPARATION,
            )
    Surface(
        color = NaytiTheme.colors.surface,
        contentColor = NaytiTheme.colors.ink,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    when (action) {
                        SetupNextAction.IMPORT_MODEL_PACK -> onImportModelPack()
                        SetupNextAction.REQUEST_PHOTO_ACCESS -> onRequestAccess()
                        SetupNextAction.START_PREPARATION -> onStartIndexing()
                        SetupNextAction.ENTER_APP -> onComplete()
                        SetupNextAction.WAIT_FOR_MODEL_PACK,
                        SetupNextAction.WAIT_FOR_CATALOG,
                        SetupNextAction.WAIT_FOR_PREPARATION,
                        -> Unit
                    }
                },
                enabled = !waiting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (waiting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(primaryActionLabel(action))
                }
            }
            TextButton(onClick = onComplete) {
                Text(stringResource(R.string.setup_skip))
            }
        }
    }
}

@Composable
private fun PrivacyPromise() {
    Surface(
        color = NaytiTheme.colors.accentContainer,
        contentColor = NaytiTheme.colors.onAccentContainer,
        shape = NaytiTheme.shapes.card,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Screen),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .background(NaytiTheme.colors.surface.copy(alpha = 0.72f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NaytiIconMark(
                    icon = NaytiIcon.Shield,
                    color = NaytiTheme.colors.onAccentContainer,
                    size = 22.dp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall)) {
                Text(stringResource(R.string.setup_privacy_title), style = NaytiTheme.type.titleM)
                Text(stringResource(R.string.setup_privacy_body), style = NaytiTheme.type.bodyM)
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    number: Int,
    icon: NaytiIcon,
    title: String,
    body: String,
    complete: Boolean,
    busy: Boolean,
) {
    Surface(
        color = NaytiTheme.colors.surface,
        contentColor = NaytiTheme.colors.ink,
        shape = NaytiTheme.shapes.card,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Screen),
                horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(NaytiTheme.colors.surfaceHigh, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    NaytiIconMark(
                        icon = if (complete) NaytiIcon.Check else icon,
                        color = if (complete) NaytiTheme.colors.ready else NaytiTheme.colors.accent,
                        size = 22.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
                ) {
                    Text(
                        text = stringResource(R.string.setup_step_number, number),
                        style = NaytiTheme.type.labelS,
                        color = NaytiTheme.colors.inkFaint,
                    )
                    Text(title, style = NaytiTheme.type.titleM)
                    Text(body, style = NaytiTheme.type.bodyM, color = NaytiTheme.colors.inkMuted)
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun setupPackDescription(state: ModelPackRuntimeState): String =
    when (state.status) {
        ModelPackRuntimeStatus.Loading -> stringResource(R.string.setup_pack_loading)
        ModelPackRuntimeStatus.Missing -> stringResource(R.string.setup_pack_pending)
        ModelPackRuntimeStatus.Installing -> stringResource(R.string.setup_pack_installing)
        ModelPackRuntimeStatus.Ready ->
            stringResource(R.string.setup_pack_ready, state.installed?.packVersion.orEmpty())
        ModelPackRuntimeStatus.Failed -> stringResource(R.string.setup_pack_failed)
    }

@Composable
private fun setupPreparationDescription(
    catalog: CatalogRuntimeState,
    indexing: OcrIndexingState,
    selectedPhotoCount: Long,
): String =
    when {
        catalog.access.permission.scope == MediaAccessScope.None ->
            stringResource(R.string.setup_prepare_waiting_access)
        catalog.status == CatalogRuntimeStatus.Reconciling ->
            stringResource(R.string.setup_prepare_inventory)
        selectedPhotoCount == 0L -> stringResource(R.string.setup_prepare_empty)
        indexing.status == OcrIndexingStatus.Running ->
            stringResource(R.string.setup_prepare_running, indexing.committed, indexing.accessible)
        indexing.accessible > 0 && indexing.outstanding == 0L ->
            pluralStringResource(
                R.plurals.setup_prepare_ready,
                indexing.committed.asResourceQuantity(),
                indexing.committed,
            )
        else ->
            pluralStringResource(
                R.plurals.setup_prepare_pending,
                selectedPhotoCount.asResourceQuantity(),
                selectedPhotoCount,
            )
    }

private fun Long.asResourceQuantity(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun primaryActionLabel(action: SetupNextAction): String =
    stringResource(
        when (action) {
            SetupNextAction.WAIT_FOR_MODEL_PACK -> R.string.setup_action_pack_wait
            SetupNextAction.IMPORT_MODEL_PACK -> R.string.setup_action_pack
            SetupNextAction.REQUEST_PHOTO_ACCESS -> R.string.setup_action_access
            SetupNextAction.START_PREPARATION -> R.string.setup_action_prepare
            SetupNextAction.ENTER_APP -> R.string.setup_action_enter
            SetupNextAction.WAIT_FOR_CATALOG -> R.string.setup_action_inventory
            SetupNextAction.WAIT_FOR_PREPARATION -> R.string.setup_action_wait
        },
    )
