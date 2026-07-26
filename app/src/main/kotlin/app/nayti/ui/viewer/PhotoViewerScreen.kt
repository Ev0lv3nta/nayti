package app.nayti.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.indexer.CatalogItem
import app.nayti.indexer.PhotoChannel
import app.nayti.indexer.PhotoRegion
import app.nayti.indexer.UnifiedSearchHit
import app.nayti.indexer.UnifiedSearchReason
import app.nayti.indexer.VisualSimilaritySearchStatus
import app.nayti.indexer.PerceptualHashSearchStatus
import app.nayti.platform.media.MediaKey
import app.nayti.ui.DuplicateResultItem
import app.nayti.ui.DuplicateUiState
import app.nayti.ui.PhotoThumbnail
import app.nayti.ui.SimilarResultItem
import app.nayti.ui.SimilarUiState
import app.nayti.ui.ViewerUiState
import app.nayti.ui.ViewerUnavailableReason
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.component.GlassSurface
import app.nayti.ui.designsystem.component.NaytiBackdrop
import app.nayti.ui.designsystem.component.naytiBackdropSource
import app.nayti.ui.designsystem.component.rememberNaytiBackdrop
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

private enum class ViewerSheet {
    Similar,
    Duplicates,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    assetId: Long,
    state: ViewerUiState,
    searchProvenance: UnifiedSearchHit?,
    previousAssetId: Long?,
    nextAssetId: Long?,
    accessRevision: Long,
    similarState: SimilarUiState,
    duplicateState: DuplicateUiState,
    onLoadThumbnail: suspend (MediaKey, Long) -> android.graphics.Bitmap?,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onClose: (Long) -> Unit,
    onOpenAsset: (Long) -> Unit,
    onFindSimilar: () -> Unit,
    onFindDuplicates: () -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf<ViewerSheet?>(null) }

    LaunchedEffect(assetId, accessRevision) {
        sheet = null
        onOpen()
    }
    DisposableEffect(assetId) {
        onDispose { onClose(assetId) }
    }

    val visibleState =
        when (state) {
            ViewerUiState.Idle -> ViewerUiState.Loading(assetId)
            is ViewerUiState.Loading -> state.takeIf { it.assetId == assetId }
            is ViewerUiState.Ready -> state.takeIf { it.evidence.item.assetId == assetId }
            is ViewerUiState.Unavailable -> state.takeIf { it.assetId == assetId }
            is ViewerUiState.Failed -> state.takeIf { it.assetId == assetId }
        } ?: ViewerUiState.Loading(assetId)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (visibleState) {
            is ViewerUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )

            is ViewerUiState.Ready -> {
                val backdrop = rememberNaytiBackdrop()
                Box(Modifier.fillMaxSize()) {
                    ViewerPhoto(
                        state = visibleState,
                        previousAssetId = previousAssetId,
                        nextAssetId = nextAssetId,
                        onOpenAsset = onOpenAsset,
                        modifier = Modifier.fillMaxSize().naytiBackdropSource(backdrop),
                    )
                    ViewerChrome(
                        state = visibleState,
                        searchProvenance = searchProvenance,
                        backdrop = backdrop,
                        onBack = onBack,
                        onShowSimilar = {
                            sheet = ViewerSheet.Similar
                            onFindSimilar()
                        },
                        onShowDuplicates = {
                            sheet = ViewerSheet.Duplicates
                            onFindDuplicates()
                        },
                    )
                }
            }

            is ViewerUiState.Unavailable -> ViewerUnavailable(
                reason = visibleState.reason,
                onBack = onBack,
                onRetry = onOpen,
            )

            is ViewerUiState.Failed -> ViewerUnavailable(
                reason = ViewerUnavailableReason.TemporarilyUnavailable,
                onBack = onBack,
                onRetry = onOpen,
            )

            ViewerUiState.Idle -> error("Idle viewer state is normalized before rendering")
        }
    }

    when (sheet) {
        ViewerSheet.Similar -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            SimilarResults(
                sourceAssetId = assetId,
                state = similarState,
                accessRevision = accessRevision,
                onLoadThumbnail = onLoadThumbnail,
                onOpenAsset = { selected ->
                    sheet = null
                    onOpenAsset(selected)
                },
                onRetry = onFindSimilar,
            )
        }
        ViewerSheet.Duplicates -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            DuplicateResults(
                sourceAssetId = assetId,
                state = duplicateState,
                accessRevision = accessRevision,
                onLoadThumbnail = onLoadThumbnail,
                onOpenAsset = { selected ->
                    sheet = null
                    onOpenAsset(selected)
                },
                onRetry = onFindDuplicates,
            )
        }
        null -> Unit
    }
}

@Composable
private fun ViewerPhoto(
    state: ViewerUiState.Ready,
    previousAssetId: Long?,
    nextAssetId: Long?,
    onOpenAsset: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(state.evidence.item.assetId) { mutableFloatStateOf(1f) }
    var offsetX by remember(state.evidence.item.assetId) { mutableFloatStateOf(0f) }
    var offsetY by remember(state.evidence.item.assetId) { mutableFloatStateOf(0f) }
    var swipeDistance by remember(state.evidence.item.assetId) { mutableFloatStateOf(0f) }
    var showText by rememberSaveable(state.evidence.item.assetId) { mutableStateOf(false) }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    val transformState =
        rememberTransformableState { _, zoomChange, panChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
            if (nextScale == 1f) {
                offsetX = 0f
                offsetY = 0f
            } else {
                offsetX += panChange.x
                offsetY += panChange.y
            }
            scale = nextScale
        }

    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(scale, previousAssetId, nextAssetId) {
                if (scale <= 1.01f) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipeDistance = 0f },
                        onHorizontalDrag = { _, dragAmount -> swipeDistance += dragAmount },
                        onDragCancel = { swipeDistance = 0f },
                        onDragEnd = {
                            val target =
                                when {
                                    swipeDistance > swipeThreshold -> previousAssetId
                                    swipeDistance < -swipeThreshold -> nextAssetId
                                    else -> null
                                }
                            swipeDistance = 0f
                            target?.let(onOpenAsset)
                        },
                    )
                }
            }
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        ) {
            Image(
                bitmap = state.image.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.viewer_photo_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (showText) {
                OcrRegionOverlay(
                    imageWidth = state.image.decodedWidth,
                    imageHeight = state.image.decodedHeight,
                    regions = state.evidence.regions,
                    matchedOrdinals = state.matchedRegionOrdinals,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ViewerTextToggle(
            selected = showText,
            enabled = state.evidence.regions.isNotEmpty(),
            onClick = { showText = !showText },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = NaytiSpacing.Screen),
        )
    }
}

@Composable
private fun ViewerChrome(
    state: ViewerUiState.Ready,
    searchProvenance: UnifiedSearchHit?,
    backdrop: NaytiBackdrop,
    onBack: () -> Unit,
    onShowSimilar: () -> Unit,
    onShowDuplicates: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerChromeButton(
                icon = NaytiIcon.Back,
                label = stringResource(R.string.viewer_back),
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.evidence.item.dateTakenMillis.viewerDate(),
                    style = NaytiTheme.type.labelL,
                    color = Color.White,
                )
                state.evidence.item.bucketDisplayName?.let { album ->
                    Text(
                        text = album,
                        style = NaytiTheme.type.labelS,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(NaytiSpacing.MinTouchTarget))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(NaytiSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
        ) {
            searchProvenance?.let { provenance ->
                MatchReasonCard(
                    provenance = provenance,
                    readyChannels = state.evidence.readyChannels,
                    outsidePreparationPeriod = state.evidence.outsidePreparationPeriod,
                )
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                backdrop = backdrop,
                material = ChromeMaterial.Glass,
                shape = RoundedCornerShape(28.dp),
                hairlineOnTop = false,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NaytiSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
                ) {
                    ViewerAction(
                        icon = NaytiIcon.Scene,
                        label = stringResource(R.string.viewer_similar_action),
                        onClick = onShowSimilar,
                        modifier = Modifier.weight(1f),
                    )
                    ViewerAction(
                        icon = NaytiIcon.Copies,
                        label = stringResource(R.string.viewer_copies_action),
                        onClick = onShowDuplicates,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchReasonCard(
    provenance: UnifiedSearchHit,
    readyChannels: Set<PhotoChannel>,
    outsidePreparationPeriod: Boolean,
) {
    val readyChannelLabels =
        readyChannels
            .sortedBy(PhotoChannel::ordinal)
            .map { channel -> stringResource(channel.shortLabel) }
            .joinToString()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NaytiTheme.shapes.card)
            .background(NaytiTheme.colors.surface)
            .padding(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.XSmall),
    ) {
        Text(
            text = stringResource(R.string.viewer_why_found, provenance.reason.label()),
            style = NaytiTheme.type.labelL,
            color = NaytiTheme.colors.accent,
        )
        provenance.displaySnippet?.takeIf(String::isNotBlank)?.let { snippet ->
            Text(
                text = snippet,
                style = NaytiTheme.type.bodyL,
                color = NaytiTheme.colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text =
                when {
                    outsidePreparationPeriod ->
                        stringResource(R.string.viewer_readiness_outside_period)
                    readyChannelLabels.isEmpty() ->
                        stringResource(R.string.viewer_readiness_preparing)
                    else ->
                        stringResource(
                            R.string.viewer_readiness_channels,
                            readyChannelLabels,
                        )
                },
            style = NaytiTheme.type.labelS,
            color = NaytiTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun ViewerTextToggle(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        stringResource(
            if (selected) {
                R.string.viewer_hide_text_regions
            } else {
                R.string.viewer_show_text_regions
            },
        )
    Box(
        modifier = modifier
            .size(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(Color.Black.copy(alpha = if (enabled) 0.55f else 0.28f))
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NaytiIconMark(
            icon = NaytiIcon.Text,
            color = if (selected) NaytiTheme.colors.accent else Color.White,
            size = 22.dp,
        )
    }
}

@Composable
private fun ViewerChromeButton(
    icon: NaytiIcon,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(Color.Black.copy(alpha = 0.52f))
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NaytiIconMark(icon = icon, color = Color.White, size = 22.dp)
    }
}

@Composable
private fun ViewerAction(
    icon: NaytiIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(NaytiSpacing.MinTouchTarget)
            .clip(NaytiTheme.shapes.control)
            .background(NaytiTheme.colors.surfaceHigh)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NaytiIconMark(icon = icon, color = NaytiTheme.colors.ink, size = 18.dp)
        Spacer(Modifier.size(NaytiSpacing.Small))
        Text(
            text = label,
            style = NaytiTheme.type.labelL,
            color = NaytiTheme.colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun ViewerUnavailable(
    reason: ViewerUnavailableReason,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(NaytiTheme.colors.background)) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(NaytiSpacing.Screen),
        ) {
            ViewerChromeButton(
                icon = NaytiIcon.Back,
                label = stringResource(R.string.viewer_back),
                onClick = onBack,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(NaytiSpacing.Section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
        ) {
            NaytiIconMark(
                icon = NaytiIcon.Alert,
                color = NaytiTheme.colors.inkMuted,
                size = 32.dp,
            )
            Text(
                text = stringResource(reason.title),
                style = NaytiTheme.type.titleL,
                color = NaytiTheme.colors.ink,
            )
            Text(
                text = stringResource(reason.details),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.inkMuted,
            )
            Text(
                text = stringResource(R.string.viewer_retry),
                modifier = Modifier
                    .clip(NaytiTheme.shapes.control)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Medium),
                style = NaytiTheme.type.labelL,
                color = NaytiTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun SimilarResults(
    sourceAssetId: Long,
    state: SimilarUiState,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> android.graphics.Bitmap?,
    onOpenAsset: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    ResultSheetHeader(R.string.viewer_similar_title)
    when (state) {
        SimilarUiState.Idle,
        is SimilarUiState.Searching,
        -> ResultLoading()
        is SimilarUiState.Failed -> if (state.sourceAssetId == sourceAssetId) {
            ResultMessage(R.string.viewer_similar_failed, onRetry)
        }
        is SimilarUiState.Ready -> if (state.sourceAssetId == sourceAssetId) {
            when {
                state.status == VisualSimilaritySearchStatus.SOURCE_OUTSIDE_SCOPE ->
                    ResultMessage(R.string.viewer_similar_outside_scope)
                state.status != VisualSimilaritySearchStatus.READY ->
                    ResultMessage(R.string.viewer_similar_not_ready)
                state.results.isEmpty() ->
                    ResultMessage(R.string.viewer_similar_empty)
                else ->
                    ResultList(
                        items = state.results,
                        accessRevision = accessRevision,
                        onLoadThumbnail = onLoadThumbnail,
                        onOpenAsset = onOpenAsset,
                    )
            }
        } else {
            ResultLoading()
        }
    }
}

@Composable
private fun DuplicateResults(
    sourceAssetId: Long,
    state: DuplicateUiState,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> android.graphics.Bitmap?,
    onOpenAsset: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    ResultSheetHeader(R.string.viewer_duplicates_title)
    when (state) {
        DuplicateUiState.Idle,
        is DuplicateUiState.Searching,
        -> ResultLoading()
        is DuplicateUiState.Failed -> if (state.sourceAssetId == sourceAssetId) {
            ResultMessage(R.string.viewer_duplicates_failed, onRetry)
        }
        is DuplicateUiState.Ready -> if (state.sourceAssetId == sourceAssetId) {
            when {
                state.status == PerceptualHashSearchStatus.SOURCE_OUTSIDE_SCOPE ->
                    ResultMessage(R.string.viewer_duplicates_outside_scope)
                state.status != PerceptualHashSearchStatus.READY ->
                    ResultMessage(R.string.viewer_duplicates_not_ready)
                state.results.isEmpty() ->
                    ResultMessage(R.string.viewer_duplicates_empty)
                else ->
                    DuplicateResultList(
                        items = state.results,
                        accessRevision = accessRevision,
                        onLoadThumbnail = onLoadThumbnail,
                        onOpenAsset = onOpenAsset,
                    )
            }
        } else {
            ResultLoading()
        }
    }
}

@Composable
private fun ResultSheetHeader(title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.padding(horizontal = NaytiSpacing.Screen),
        style = NaytiTheme.type.titleL,
        color = NaytiTheme.colors.ink,
    )
}

@Composable
private fun ResultLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ResultMessage(message: Int, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
    ) {
        Text(
            text = stringResource(message),
            style = NaytiTheme.type.bodyM,
            color = NaytiTheme.colors.inkMuted,
        )
        onRetry?.let {
            Text(
                text = stringResource(R.string.viewer_retry),
                modifier = Modifier.clickable(onClick = it),
                style = NaytiTheme.type.labelL,
                color = NaytiTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun ResultList(
    items: List<SimilarResultItem>,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> android.graphics.Bitmap?,
    onOpenAsset: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(420.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
    ) {
        items(items, key = { result -> result.asset.assetId }) { result ->
            ResultRow(
                asset = result.asset,
                supportingText = stringResource(R.string.viewer_visual_match),
                accessRevision = accessRevision,
                onLoadThumbnail = onLoadThumbnail,
                onClick = { onOpenAsset(result.asset.assetId) },
            )
        }
    }
}

@Composable
private fun DuplicateResultList(
    items: List<DuplicateResultItem>,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> android.graphics.Bitmap?,
    onOpenAsset: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(420.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(NaytiSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small),
    ) {
        items(items, key = { result -> result.asset.assetId }) { result ->
            ResultRow(
                asset = result.asset,
                supportingText =
                    stringResource(
                        if (result.match.distance == 0) {
                            R.string.viewer_duplicate_exact
                        } else {
                            R.string.viewer_duplicate_near
                        },
                    ),
                accessRevision = accessRevision,
                onLoadThumbnail = onLoadThumbnail,
                onClick = { onOpenAsset(result.asset.assetId) },
            )
        }
    }
}

@Composable
private fun ResultRow(
    asset: CatalogItem,
    supportingText: String,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> android.graphics.Bitmap?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NaytiTheme.shapes.card)
            .background(NaytiTheme.colors.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(NaytiSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(NaytiTheme.shapes.photo)) {
            PhotoThumbnail(
                key = asset.key,
                accessRevision = accessRevision,
                description = asset.displayName,
                onLoad = onLoadThumbnail,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = asset.displayName ?: stringResource(R.string.catalog_unnamed_photo, asset.assetId),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supportingText,
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun OcrRegionOverlay(
    imageWidth: Int,
    imageHeight: Int,
    regions: List<PhotoRegion>,
    matchedOrdinals: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val regularColor = NaytiTheme.colors.attention
    val matchedColor = NaytiTheme.colors.accent
    Canvas(modifier) {
        val imageAspect = imageWidth.toFloat() / imageHeight
        val canvasAspect = size.width / size.height
        val renderedWidth: Float
        val renderedHeight: Float
        val offsetX: Float
        val offsetY: Float
        if (canvasAspect > imageAspect) {
            renderedHeight = size.height
            renderedWidth = renderedHeight * imageAspect
            offsetX = (size.width - renderedWidth) / 2f
            offsetY = 0f
        } else {
            renderedWidth = size.width
            renderedHeight = renderedWidth / imageAspect
            offsetX = 0f
            offsetY = (size.height - renderedHeight) / 2f
        }
        regions.forEach { region ->
            val xs =
                listOf(region.x0Micros, region.x1Micros, region.x2Micros, region.x3Micros)
                    .map { value -> offsetX + renderedWidth * value / 1_000_000f }
            val ys =
                listOf(region.y0Micros, region.y1Micros, region.y2Micros, region.y3Micros)
                    .map { value -> offsetY + renderedHeight * value / 1_000_000f }
            val left = xs.min()
            val top = ys.min()
            val right = xs.max()
            val bottom = ys.max()
            drawRect(
                color =
                    if (region.ordinal in matchedOrdinals) {
                        matchedColor.copy(alpha = 0.48f)
                    } else {
                        regularColor.copy(alpha = 0.32f)
                    },
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(
                    width = max(1f, right - left),
                    height = max(1f, bottom - top),
                ),
            )
        }
    }
}

@Composable
private fun Long?.viewerDate(): String =
    this?.let { millis ->
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    } ?: stringResource(R.string.viewer_date_unknown)

private val PhotoChannel.shortLabel: Int
    get() =
        when (this) {
            PhotoChannel.Text -> R.string.viewer_channel_text
            PhotoChannel.Meaning -> R.string.viewer_channel_meaning
            PhotoChannel.Visual -> R.string.viewer_channel_visual
            PhotoChannel.Duplicates -> R.string.viewer_channel_duplicates
        }

private fun UnifiedSearchReason.label(): Int =
    when (this) {
        UnifiedSearchReason.EXACT_IDENTIFIER -> R.string.evidence_identifier
        UnifiedSearchReason.QUOTED_PHRASE -> R.string.evidence_phrase
        UnifiedSearchReason.PERSON_NAME -> R.string.evidence_person
        UnifiedSearchReason.LITERAL_TEXT -> R.string.evidence_text
        UnifiedSearchReason.FUZZY_TEXT -> R.string.evidence_fuzzy
        UnifiedSearchReason.SEMANTIC_TEXT -> R.string.evidence_semantic
        UnifiedSearchReason.VISUAL_CONTENT -> R.string.evidence_visual
    }

private val ViewerUnavailableReason.title: Int
    get() =
        when (this) {
            ViewerUnavailableReason.AccessRemoved,
            ViewerUnavailableReason.AccessChanged,
            -> R.string.viewer_access_changed
            ViewerUnavailableReason.VolumeOffline -> R.string.viewer_volume_offline
            ViewerUnavailableReason.Pending -> R.string.viewer_pending
            ViewerUnavailableReason.Trashed,
            ViewerUnavailableReason.Missing,
            -> R.string.viewer_missing
            ViewerUnavailableReason.CannotDecode -> R.string.viewer_cannot_decode
            ViewerUnavailableReason.TemporarilyUnavailable -> R.string.viewer_temporarily_unavailable
        }

private val ViewerUnavailableReason.details: Int
    get() =
        when (this) {
            ViewerUnavailableReason.AccessRemoved -> R.string.viewer_access_removed_details
            ViewerUnavailableReason.AccessChanged -> R.string.viewer_access_changed_details
            ViewerUnavailableReason.VolumeOffline -> R.string.viewer_volume_offline_details
            ViewerUnavailableReason.Pending -> R.string.viewer_pending_details
            ViewerUnavailableReason.Trashed -> R.string.viewer_trashed_details
            ViewerUnavailableReason.Missing -> R.string.viewer_missing_details
            ViewerUnavailableReason.CannotDecode -> R.string.viewer_cannot_decode_details
            ViewerUnavailableReason.TemporarilyUnavailable -> R.string.viewer_temporarily_unavailable_details
        }
