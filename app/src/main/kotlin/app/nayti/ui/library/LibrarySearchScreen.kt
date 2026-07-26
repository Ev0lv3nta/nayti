package app.nayti.ui.library

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.indexer.CatalogItem
import app.nayti.indexer.LibraryAlbumFacet
import app.nayti.indexer.LibraryFilterFacets
import app.nayti.indexer.LibraryMimeFacet
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.SearchCapability
import app.nayti.indexer.SearchChannel
import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.SearchFilter
import app.nayti.indexer.UnifiedSearchReason
import app.nayti.platform.media.MediaAccessScope
import app.nayti.ui.LibraryUiState
import app.nayti.ui.PhotoThumbnail
import app.nayti.ui.SearchResultItem
import app.nayti.ui.SearchUiState
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.component.GlassSurface
import app.nayti.ui.designsystem.component.NaytiBackdrop
import app.nayti.ui.designsystem.component.naytiBackdropSource
import app.nayti.ui.designsystem.component.rememberNaytiBackdrop
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

private enum class SearchDateScope {
    Any,
    Month,
    Year,
    Custom,
}

private sealed interface LibraryGridEntry {
    data class Month(val key: String, val label: String) : LibraryGridEntry
    data class Photo(val item: CatalogItem) : LibraryGridEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySearchScreen(
    library: LibraryUiState,
    search: SearchUiState,
    indexing: OcrIndexingState,
    accessScope: MediaAccessScope,
    accessRevision: Long,
    modelReady: Boolean,
    onLoadThumbnail: suspend (app.nayti.platform.media.MediaKey, Long) -> android.graphics.Bitmap?,
    onLoadMore: () -> Unit,
    onRetryLibrary: () -> Unit,
    onRequestAccess: () -> Unit,
    onSearch: (String, SearchFilter, SearchChannelSelection) -> Unit,
    onCancelSearch: () -> Unit,
    onOpenAsset: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var dateScope by rememberSaveable { mutableStateOf(SearchDateScope.Any) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customBefore by rememberSaveable { mutableStateOf<Long?>(null) }
    var bucketId by rememberSaveable { mutableStateOf<Long?>(null) }
    var mimeType by rememberSaveable { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf(SearchChannelSelection.All) }
    var showWhere by rememberSaveable { mutableStateOf(false) }
    var showHow by rememberSaveable { mutableStateOf(false) }
    var longSearch by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val backdrop = rememberNaytiBackdrop()

    LaunchedEffect(library.facets) {
        if (bucketId != null && library.facets.albums.none { it.bucketId == bucketId }) bucketId = null
        if (mimeType != null && library.facets.mimeTypes.none { it.mimeType == mimeType }) mimeType = null
    }
    LaunchedEffect(search) {
        longSearch = false
        if (search is SearchUiState.Searching) {
            delay(LongSearchThresholdMillis)
            longSearch = true
        }
    }

    val submit = {
        if (query.isNotBlank() && modelReady && search !is SearchUiState.Searching) {
            keyboardController?.hide()
            focusManager.clearFocus()
            onSearch(
                query,
                buildSearchFilter(dateScope, customFrom, customBefore, bucketId, mimeType),
                channels,
            )
        }
    }

    Box(modifier.fillMaxSize()) {
        LibraryOrResultsGrid(
            library = library,
            search = search,
            indexing = indexing,
            accessScope = accessScope,
            accessRevision = accessRevision,
            onLoadThumbnail = onLoadThumbnail,
            onLoadMore = onLoadMore,
            onRetryLibrary = onRetryLibrary,
            onRequestAccess = onRequestAccess,
            onOpenAsset = onOpenAsset,
            modifier = Modifier.fillMaxSize().naytiBackdropSource(backdrop),
        )
        SearchChrome(
            query = query,
            onQueryChange = { query = it },
            onSubmit = submit,
            onClear = {
                query = ""
                onSearch("", SearchFilter.None, channels)
            },
            onOpenWhere = { showWhere = true },
            onOpenHow = { showHow = true },
            onOpenSettings = onOpenSettings,
            canSubmit = query.isNotBlank() && modelReady && search !is SearchUiState.Searching,
            searching = search is SearchUiState.Searching,
            longSearch = longSearch,
            imeVisible = imeVisible,
            filtersActive =
                dateScope != SearchDateScope.Any || bucketId != null || mimeType != null,
            methodsActive = channels != SearchChannelSelection.All,
            modelReady = modelReady,
            onCancelSearch = onCancelSearch,
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Medium),
        )
    }

    if (showWhere) {
        WhereToSearchSheet(
            facets = library.facets,
            dateScope = dateScope,
            bucketId = bucketId,
            mimeType = mimeType,
            onDateScope = { selected ->
                if (selected == SearchDateScope.Custom) {
                    showDateRangePicker(context) { from, before ->
                        customFrom = from
                        customBefore = before
                        dateScope = SearchDateScope.Custom
                    }
                } else {
                    dateScope = selected
                }
            },
            onBucket = { bucketId = it },
            onMimeType = { mimeType = it },
            onReset = {
                dateScope = SearchDateScope.Any
                customFrom = null
                customBefore = null
                bucketId = null
                mimeType = null
            },
            onDismiss = { showWhere = false },
        )
    }
    if (showHow) {
        HowToSearchSheet(
            selection = channels,
            indexing = indexing,
            onSelection = { channels = it },
            onDismiss = { showHow = false },
        )
    }
}

@Composable
private fun LibraryOrResultsGrid(
    library: LibraryUiState,
    search: SearchUiState,
    indexing: OcrIndexingState,
    accessScope: MediaAccessScope,
    accessRevision: Long,
    onLoadThumbnail: suspend (app.nayti.platform.media.MediaKey, Long) -> android.graphics.Bitmap?,
    onLoadMore: () -> Unit,
    onRetryLibrary: () -> Unit,
    onRequestAccess: () -> Unit,
    onOpenAsset: (Long) -> Unit,
    modifier: Modifier,
) {
    val unknownDateLabel = stringResource(R.string.library_unknown_date)
    val gridEntries = remember(library.items, unknownDateLabel) {
        libraryEntries(library.items, unknownDateLabel)
    }
    val libraryGridState = rememberLazyGridState()
    val resultsGridState = rememberLazyGridState()
    LaunchedEffect(search) {
        if (search is SearchUiState.Ready) resultsGridState.scrollToItem(0)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = if (search is SearchUiState.Ready) resultsGridState else libraryGridState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = SearchChromeClearance, top = NaytiSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.PhotoGutter),
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.PhotoGutter),
    ) {
        when {
            accessScope == MediaAccessScope.None -> item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryMessage(
                    title = stringResource(R.string.library_access_title),
                    body = stringResource(R.string.library_access_body),
                    action = stringResource(R.string.library_access_action),
                    onAction = onRequestAccess,
                )
            }
            search is SearchUiState.Ready -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchCoverageLine(search, indexing)
                }
                if (search.results.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SearchEmptyState() }
                } else {
                    items(
                        items = search.results,
                        key = { result -> result.asset.assetId },
                    ) { result ->
                        SearchResultTile(
                            result = result,
                            accessRevision = accessRevision,
                            onLoadThumbnail = onLoadThumbnail,
                            onClick = { onOpenAsset(result.asset.assetId) },
                        )
                    }
                }
            }
            library.initialLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                LoadingLine(stringResource(R.string.library_opening))
            }
            library.errorCode != null && library.items.isEmpty() ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryMessage(
                        title = stringResource(R.string.library_failed_title),
                        action = stringResource(R.string.library_retry),
                        onAction = onRetryLibrary,
                    )
                }
            library.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryMessage(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                )
            }
            else -> {
                if (search is SearchUiState.Cancelled) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        InfoLine(stringResource(R.string.search_surface_cancelled))
                    }
                } else if (search is SearchUiState.Failed) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        InfoLine(stringResource(R.string.search_surface_failed))
                    }
                }
                items(
                    items = gridEntries,
                    key = { entry ->
                        when (entry) {
                            is LibraryGridEntry.Month -> "month:${entry.key}"
                            is LibraryGridEntry.Photo -> "photo:${entry.item.assetId}"
                        }
                    },
                    span = { entry ->
                        if (entry is LibraryGridEntry.Month) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    },
                ) { entry ->
                    when (entry) {
                        is LibraryGridEntry.Month -> MonthHeader(entry.label)
                        is LibraryGridEntry.Photo -> {
                            LibraryPhotoTile(
                                item = entry.item,
                                accessRevision = accessRevision,
                                onLoadThumbnail = onLoadThumbnail,
                                onClick = { onOpenAsset(entry.item.assetId) },
                            )
                            if (
                                library.canLoadMore &&
                                entry.item.assetId == library.items.lastOrNull()?.assetId
                            ) {
                                LaunchedEffect(entry.item.assetId) { onLoadMore() }
                            }
                        }
                    }
                }
                if (library.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadingLine(stringResource(R.string.library_loading_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchChrome(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onOpenWhere: () -> Unit,
    onOpenHow: () -> Unit,
    onOpenSettings: () -> Unit,
    canSubmit: Boolean,
    searching: Boolean,
    longSearch: Boolean,
    imeVisible: Boolean,
    filtersActive: Boolean,
    methodsActive: Boolean,
    modelReady: Boolean,
    onCancelSearch: () -> Unit,
    backdrop: NaytiBackdrop,
    modifier: Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        backdrop = backdrop,
        material = ChromeMaterial.Glass,
        shape = RoundedCornerShape(28.dp),
        hairlineOnTop = false,
    ) {
        Column(Modifier.fillMaxWidth().padding(NaytiSpacing.Medium)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        stringResource(R.string.search_surface_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { NaytiIconMark(NaytiIcon.Search) },
                trailingIcon = {
                    if (query.isNotEmpty() && !searching) {
                        IconButton(onClick = onClear) {
                            NaytiIconMark(NaytiIcon.Close)
                        }
                    } else {
                        IconButton(onClick = onSubmit, enabled = canSubmit) {
                            if (searching) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                NaytiIconMark(NaytiIcon.Search)
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
            )
            if (!modelReady) {
                Text(
                    text = stringResource(R.string.search_surface_model_required),
                    style = NaytiTheme.type.labelS,
                    color = NaytiTheme.colors.attention,
                    modifier = Modifier.padding(horizontal = NaytiSpacing.Small, vertical = NaytiSpacing.XSmall),
                )
            }
            when {
                longSearch -> SearchRunningControls(onCancelSearch)
                !imeVisible && !searching -> Row(modifier = Modifier.fillMaxWidth()) {
                    ChromeAction(
                        icon = NaytiIcon.Filters,
                        label = stringResource(R.string.search_surface_where) + if (filtersActive) " •" else "",
                        onClick = onOpenWhere,
                        modifier = Modifier.weight(1f),
                    )
                    ChromeAction(
                        icon = NaytiIcon.Methods,
                        label = stringResource(R.string.search_surface_how) + if (methodsActive) " •" else "",
                        onClick = onOpenHow,
                        modifier = Modifier.weight(1f),
                    )
                    ChromeAction(
                        icon = NaytiIcon.Settings,
                        label = stringResource(R.string.search_surface_settings),
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChromeAction(
    icon: NaytiIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = NaytiSpacing.XSmall),
    ) {
        NaytiIconMark(icon = icon, size = 16.dp)
        Spacer(Modifier.width(NaytiSpacing.XSmall))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = NaytiTheme.type.labelS,
        )
    }
}

@Composable
private fun LibraryPhotoTile(
    item: CatalogItem,
    accessRevision: Long,
    onLoadThumbnail: suspend (app.nayti.platform.media.MediaKey, Long) -> android.graphics.Bitmap?,
    onClick: () -> Unit,
) {
    val description = photoDescription(item)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        PhotoThumbnail(
            key = item.key,
            accessRevision = accessRevision,
            description = description,
            onLoad = onLoadThumbnail,
            shape = RoundedCornerShape(3.dp),
        )
    }
}

@Composable
private fun SearchResultTile(
    result: SearchResultItem,
    accessRevision: Long,
    onLoadThumbnail: suspend (app.nayti.platform.media.MediaKey, Long) -> android.graphics.Bitmap?,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            PhotoThumbnail(
                key = result.asset.key,
                accessRevision = accessRevision,
                description = photoDescription(result.asset),
                onLoad = onLoadThumbnail,
                shape = RoundedCornerShape(3.dp),
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                color = NaytiTheme.colors.accentContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(
                    text = evidenceLabel(result.hit.reason),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    style = NaytiTheme.type.labelS,
                    color = NaytiTheme.colors.onAccentContainer,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = result.hit.displaySnippet ?: stringResource(R.string.search_visual_result),
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp),
            style = NaytiTheme.type.labelS,
            color = NaytiTheme.colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MonthHeader(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .background(NaytiTheme.colors.background.copy(alpha = 0.94f))
            .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Medium)
            .semantics { heading() },
        style = NaytiTheme.type.titleM,
        color = NaytiTheme.colors.ink,
    )
}

@Composable
private fun SearchCoverageLine(search: SearchUiState.Ready, indexing: OcrIndexingState) {
    val resultCount = search.results.size
    val resultText = pluralStringResource(R.plurals.search_surface_results, resultCount, resultCount)
    val channelCoverage = buildList {
        if (search.channels.ocrLiteral) {
            add(coverageLabel(R.string.search_how_literal, SearchCapability.TEXT, indexing))
        }
        if (search.channels.ocrSemantic) {
            add(coverageLabel(R.string.search_how_semantic, SearchCapability.MEANING, indexing))
        }
        if (search.channels.visual) {
            add(coverageLabel(R.string.search_how_visual, SearchCapability.VISUAL, indexing))
        }
    }.joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Medium)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(resultText, style = NaytiTheme.type.titleM, color = NaytiTheme.colors.ink)
        Text(
            stringResource(R.string.search_surface_coverage, channelCoverage),
            style = NaytiTheme.type.bodyM,
            color = NaytiTheme.colors.inkMuted,
        )
        if (resultCount >= SearchResultLimit) {
            Text(
                pluralStringResource(
                    R.plurals.search_surface_results_limit,
                    resultCount,
                    resultCount,
                ),
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkFaint,
            )
        }
    }
}

@Composable
private fun coverageLabel(
    labelResource: Int,
    capability: SearchCapability,
    indexing: OcrIndexingState,
): String {
    val count = indexing.capabilities.firstOrNull { it.capability == capability }?.committed ?: 0
    return pluralStringResource(
        R.plurals.search_surface_coverage_channel,
        count.toInt(),
        stringResource(labelResource),
        count,
    )
}

@Composable
private fun SearchRunningControls(onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NaytiSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.search_surface_searching_long),
            modifier = Modifier.weight(1f),
            style = NaytiTheme.type.bodyM,
            color = NaytiTheme.colors.ink,
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.search_surface_cancel)) }
    }
}

@Composable
private fun SearchEmptyState() {
    LibraryMessage(
        title = stringResource(R.string.search_surface_empty_title),
        body = stringResource(R.string.search_surface_empty_body),
    )
}

@Composable
private fun LibraryMessage(
    title: String,
    body: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(NaytiSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
    ) {
        Text(title, style = NaytiTheme.type.titleL, color = NaytiTheme.colors.ink)
        body?.let {
            Text(it, style = NaytiTheme.type.bodyM, color = NaytiTheme.colors.inkMuted)
        }
        if (action != null && onAction != null) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(NaytiSpacing.Section),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(NaytiSpacing.Small))
        Text(text, color = NaytiTheme.colors.inkMuted)
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Screen),
        style = NaytiTheme.type.bodyM,
        color = NaytiTheme.colors.inkMuted,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhereToSearchSheet(
    facets: LibraryFilterFacets,
    dateScope: SearchDateScope,
    bucketId: Long?,
    mimeType: String?,
    onDateScope: (SearchDateScope) -> Unit,
    onBucket: (Long?) -> Unit,
    onMimeType: (String?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = NaytiSpacing.Screen,
                end = NaytiSpacing.Screen,
                bottom = NaytiSpacing.Section,
            ),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
        ) {
            item {
                Text(
                    stringResource(R.string.search_where_title),
                    style = NaytiTheme.type.titleL,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item { SheetLabel(stringResource(R.string.search_where_period)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
                    items(SearchDateScope.entries) { option ->
                        FilterChip(
                            selected = dateScope == option,
                            onClick = { onDateScope(option) },
                            label = { Text(stringResource(option.labelResource)) },
                        )
                    }
                }
            }
            item { SheetLabel(stringResource(R.string.search_where_album)) }
            item {
                FacetRow(
                    anyLabel = stringResource(R.string.search_where_any_album),
                    anySelected = bucketId == null,
                    onAny = { onBucket(null) },
                    items = facets.albums,
                    selected = { it.bucketId == bucketId },
                    label = LibraryAlbumFacet::displayName,
                    onSelect = { onBucket(it.bucketId) },
                )
            }
            item { SheetLabel(stringResource(R.string.search_where_file_type)) }
            item {
                FacetRow(
                    anyLabel = stringResource(R.string.search_where_any_type),
                    anySelected = mimeType == null,
                    onAny = { onMimeType(null) },
                    items = facets.mimeTypes,
                    selected = { it.mimeType == mimeType },
                    label = { it.mimeType.substringAfter('/').uppercase() },
                    onSelect = { onMimeType(it.mimeType) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.search_where_reset)) }
                    Button(onClick = onDismiss) { Text(stringResource(R.string.search_where_apply)) }
                }
            }
        }
    }
}

@Composable
private fun <T> FacetRow(
    anyLabel: String,
    anySelected: Boolean,
    onAny: () -> Unit,
    items: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
        item {
            FilterChip(selected = anySelected, onClick = onAny, label = { Text(anyLabel) })
        }
        items(items) { facet ->
            FilterChip(
                selected = selected(facet),
                onClick = { onSelect(facet) },
                label = { Text(label(facet)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HowToSearchSheet(
    selection: SearchChannelSelection,
    indexing: OcrIndexingState,
    onSelection: (SearchChannelSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(
                start = NaytiSpacing.Screen,
                end = NaytiSpacing.Screen,
                bottom = NaytiSpacing.Section,
            ),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Medium),
        ) {
            Text(
                stringResource(R.string.search_how_title),
                style = NaytiTheme.type.titleL,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.search_how_note),
                style = NaytiTheme.type.bodyM,
                color = NaytiTheme.colors.inkMuted,
            )
            SearchMethodRow(
                title = stringResource(R.string.search_how_literal),
                icon = NaytiIcon.Text,
                checked = selection.ocrLiteral,
                ready = committed(indexing, SearchCapability.TEXT),
                onChecked = { onSelection(selection.set(SearchChannel.OCR_LITERAL, it)) },
            )
            SearchMethodRow(
                title = stringResource(R.string.search_how_semantic),
                icon = NaytiIcon.Meaning,
                checked = selection.ocrSemantic,
                ready = committed(indexing, SearchCapability.MEANING),
                onChecked = { onSelection(selection.set(SearchChannel.OCR_SEMANTIC, it)) },
            )
            SearchMethodRow(
                title = stringResource(R.string.search_how_visual),
                icon = NaytiIcon.Scene,
                checked = selection.visual,
                ready = committed(indexing, SearchCapability.VISUAL),
                onChecked = { onSelection(selection.set(SearchChannel.VISUAL, it)) },
            )
            Text(
                stringResource(R.string.search_how_keep_one),
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkFaint,
            )
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.search_where_apply))
            }
        }
    }
}

@Composable
private fun SearchMethodRow(
    title: String,
    icon: NaytiIcon,
    checked: Boolean,
    ready: Long,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NaytiIconMark(icon, color = NaytiTheme.colors.inkMuted)
        Spacer(Modifier.width(NaytiSpacing.Medium))
        Column(Modifier.weight(1f)) {
            Text(title, style = NaytiTheme.type.bodyL, fontWeight = FontWeight.Medium)
            Text(
                pluralStringResource(R.plurals.search_how_ready, ready.toInt(), ready),
                style = NaytiTheme.type.labelS,
                color = NaytiTheme.colors.inkMuted,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = NaytiTheme.type.titleM, color = NaytiTheme.colors.ink)
}

@Composable
private fun photoDescription(item: CatalogItem): String {
    val date = item.dateTakenMillis?.let {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: stringResource(R.string.library_unknown_date)
    return stringResource(
        R.string.library_photo_description,
        date,
        item.bucketDisplayName ?: stringResource(R.string.library_unknown_album),
    )
}

@Composable
private fun evidenceLabel(reason: UnifiedSearchReason): String = stringResource(
    when (reason) {
        UnifiedSearchReason.EXACT_IDENTIFIER -> R.string.evidence_identifier
        UnifiedSearchReason.QUOTED_PHRASE -> R.string.evidence_phrase
        UnifiedSearchReason.PERSON_NAME -> R.string.evidence_person
        UnifiedSearchReason.LITERAL_TEXT -> R.string.evidence_text
        UnifiedSearchReason.FUZZY_TEXT -> R.string.evidence_fuzzy
        UnifiedSearchReason.SEMANTIC_TEXT -> R.string.evidence_semantic
        UnifiedSearchReason.VISUAL_CONTENT -> R.string.evidence_visual
    },
)

private fun libraryEntries(
    items: List<CatalogItem>,
    unknownDateLabel: String,
): List<LibraryGridEntry> {
    val formatter = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    var previousKey: String? = null
    return buildList {
        items.forEach { item ->
            val date = item.dateTakenMillis?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            val key = date?.let { "${it.year}-${it.monthValue}" } ?: "unknown"
            if (key != previousKey) {
                add(
                    LibraryGridEntry.Month(
                        key = key,
                        label = date?.format(formatter)?.replaceFirstChar(Char::uppercase)
                            ?: unknownDateLabel,
                    ),
                )
                previousKey = key
            }
            add(LibraryGridEntry.Photo(item))
        }
    }
}

private fun buildSearchFilter(
    dateScope: SearchDateScope,
    customFrom: Long?,
    customBefore: Long?,
    bucketId: Long?,
    mimeType: String?,
): SearchFilter {
    val now = System.currentTimeMillis()
    return SearchFilter(
        takenFromMillis = when (dateScope) {
            SearchDateScope.Any -> null
            SearchDateScope.Month -> now - 30L * DayMillis
            SearchDateScope.Year -> now - 365L * DayMillis
            SearchDateScope.Custom -> customFrom
        },
        takenBeforeMillis = if (dateScope == SearchDateScope.Custom) customBefore else null,
        bucketId = bucketId,
        mimeType = mimeType,
    )
}

private val SearchDateScope.labelResource: Int
    get() = when (this) {
        SearchDateScope.Any -> R.string.search_where_all
        SearchDateScope.Month -> R.string.search_where_month
        SearchDateScope.Year -> R.string.search_where_year
        SearchDateScope.Custom -> R.string.search_where_custom
    }

private fun committed(indexing: OcrIndexingState, capability: SearchCapability): Long =
    indexing.capabilities.firstOrNull { it.capability == capability }?.committed ?: 0

private fun showDateRangePicker(
    context: Context,
    onSelected: (fromMillis: Long, beforeMillis: Long) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialStart = LocalDate.now(zone).minusMonths(1)
    DatePickerDialog(
        context,
        { _, startYear, startMonth, startDay ->
            val start = LocalDate.of(startYear, startMonth + 1, startDay)
            val endDialog = DatePickerDialog(
                context,
                { _, endYear, endMonth, endDay ->
                    val end = LocalDate.of(endYear, endMonth + 1, endDay)
                    onSelected(
                        start.atStartOfDay(zone).toInstant().toEpochMilli(),
                        end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    )
                },
                LocalDate.now(zone).year,
                LocalDate.now(zone).monthValue - 1,
                LocalDate.now(zone).dayOfMonth,
            )
            endDialog.datePicker.minDate = start.atStartOfDay(zone).toInstant().toEpochMilli()
            endDialog.show()
        },
        initialStart.year,
        initialStart.monthValue - 1,
        initialStart.dayOfMonth,
    ).show()
}

private const val LongSearchThresholdMillis = 800L
private const val DayMillis = 24L * 60 * 60 * 1_000
private const val SearchResultLimit = 50
private val SearchChromeClearance = 164.dp
