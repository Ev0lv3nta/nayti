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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.nayti.ui.theme.NaytiSpacing

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
    val action =
        SetupPolicy.next(
            SetupSnapshot(
                modelPackReady = modelPack.installed != null,
                modelPackBusy = modelPack.status in setOf(ModelPackRuntimeStatus.Loading, ModelPackRuntimeStatus.Installing),
                photoAccessGranted = accessGranted,
                catalogReconciling = catalog.status == CatalogRuntimeStatus.Reconciling,
                availablePhotos = selectedPhotoCount,
                indexingRunning = indexing.status == OcrIndexingStatus.Running,
                indexingAccessible = indexing.accessible,
                indexingOutstanding = indexing.outstanding,
            ),
        )
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                horizontal = NaytiSpacing.ScreenHorizontal,
                vertical = NaytiSpacing.ScreenVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Section),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.local_only),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.setup_eyebrow).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = stringResource(R.string.setup_title),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.setup_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                PrivacyPromise()
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
                Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Item)) {
                    Text(
                        text = stringResource(R.string.setup_steps_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    SetupStepCard(
                        icon = Icons.Outlined.Build,
                        title = stringResource(R.string.setup_pack_title),
                        body = setupPackDescription(modelPack),
                        complete = modelPack.installed != null,
                        busy = modelPack.status in setOf(ModelPackRuntimeStatus.Loading, ModelPackRuntimeStatus.Installing),
                    )
                    SetupStepCard(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.setup_access_title),
                        body =
                            if (accessGranted) {
                                stringResource(R.string.setup_access_ready)
                            } else {
                                stringResource(R.string.setup_access_pending)
                            },
                        complete = accessGranted,
                        busy = false,
                    )
                    SetupStepCard(
                        icon = Icons.AutoMirrored.Outlined.List,
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
            item {
                Text(
                    text = stringResource(R.string.setup_skip_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SetupActions(
    action: SetupNextAction,
    onImportModelPack: () -> Unit,
    onRequestAccess: () -> Unit,
    onStartIndexing: () -> Unit,
    onComplete: () -> Unit,
) {
    val waiting = action in setOf(
        SetupNextAction.WAIT_FOR_MODEL_PACK,
        SetupNextAction.WAIT_FOR_CATALOG,
        SetupNextAction.WAIT_FOR_PREPARATION,
    )
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NaytiSpacing.ScreenHorizontal,
                vertical = NaytiSpacing.Item,
            ),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onComplete) {
                Text(stringResource(R.string.setup_skip))
            }
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
                modifier = Modifier.weight(1f),
            ) {
                if (waiting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(primaryActionLabel(action))
                }
            }
        }
    }
}

@Composable
private fun PrivacyPromise() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setup_privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setup_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    icon: ImageVector,
    title: String,
    body: String,
    complete: Boolean,
    busy: Boolean,
) {
    Card(shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                complete -> Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.setup_step_complete),
                    tint = MaterialTheme.colorScheme.primary,
                )
                busy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
    }
}

@Composable
private fun setupPackDescription(state: ModelPackRuntimeState): String = when (state.status) {
    ModelPackRuntimeStatus.Loading -> stringResource(R.string.setup_pack_loading)
    ModelPackRuntimeStatus.Missing -> stringResource(R.string.setup_pack_pending)
    ModelPackRuntimeStatus.Installing -> stringResource(R.string.setup_pack_installing)
    ModelPackRuntimeStatus.Ready -> stringResource(
        R.string.setup_pack_ready,
        state.installed?.packVersion.orEmpty(),
    )
    ModelPackRuntimeStatus.Failed -> stringResource(R.string.setup_pack_failed)
}

@Composable
private fun setupPreparationDescription(
    catalog: CatalogRuntimeState,
    indexing: OcrIndexingState,
    selectedPhotoCount: Long,
): String = when {
    catalog.access.permission.scope == MediaAccessScope.None -> stringResource(R.string.setup_prepare_waiting_access)
    catalog.status == CatalogRuntimeStatus.Reconciling -> stringResource(R.string.setup_prepare_inventory)
    selectedPhotoCount == 0L -> stringResource(R.string.setup_prepare_empty)
    indexing.status == OcrIndexingStatus.Running -> stringResource(
        R.string.setup_prepare_running,
        indexing.committed,
        indexing.accessible,
    )
    indexing.accessible > 0 && indexing.outstanding == 0L -> pluralStringResource(
        R.plurals.setup_prepare_ready,
        indexing.committed.asResourceQuantity(),
        indexing.committed,
    )
    else -> pluralStringResource(
        R.plurals.setup_prepare_pending,
        selectedPhotoCount.asResourceQuantity(),
        selectedPhotoCount,
    )
}

private fun Long.asResourceQuantity(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun primaryActionLabel(action: SetupNextAction): String = stringResource(
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
