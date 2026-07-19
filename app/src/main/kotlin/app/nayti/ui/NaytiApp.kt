package app.nayti.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.nayti.BuildConfig
import app.nayti.R
import app.nayti.indexer.CatalogItem
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.CatalogSummary
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.OcrSemanticSearchStatus
import app.nayti.indexer.PerceptualHashSearchStatus
import app.nayti.indexer.SearchCapability
import app.nayti.indexer.SearchCapabilityCoverage
import app.nayti.indexer.SearchFilter
import app.nayti.indexer.UnifiedSearchReason
import app.nayti.indexer.VisualSimilaritySearchStatus
import app.nayti.indexer.VisualTextSearchStatus
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaKey
import app.nayti.platform.media.MediaPermissionEvaluator
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.storage.OcrRegionEntity
import app.nayti.storage.IndexOperationState
import app.nayti.storage.SearchFilterFacets
import app.nayti.ui.theme.NaytiSpacing
import app.nayti.ui.theme.NaytiTheme

private enum class RootDestination(
    val route: String,
    @param:StringRes val title: Int,
    val icon: ImageVector,
) {
    Search("search", R.string.nav_search, Icons.Outlined.Search),
    Readiness("readiness", R.string.nav_readiness, Icons.Outlined.CheckCircle),
    Data("data", R.string.nav_data, Icons.Outlined.Settings),
}

private const val ViewerRoute = "viewer/{assetId}"
private val NavigationRailBreakpoint = 700.dp

private enum class SearchDateFilter(
    @param:StringRes val label: Int,
    val lookbackMillis: Long?,
) {
    Any(R.string.search_date_any, null),
    Month(R.string.search_date_month, 30L * 24 * 60 * 60 * 1_000),
    Year(R.string.search_date_year, 365L * 24 * 60 * 60 * 1_000),
}

@Composable
fun NaytiApp(viewModel: CatalogViewModel = viewModel()) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val modelPack by viewModel.modelPack.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val searchFilterFacets by viewModel.searchFilterFacets.collectAsStateWithLifecycle()
    val similar by viewModel.similar.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val viewerProbe by viewModel.viewerProbe.collectAsStateWithLifecycle()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val localStorage by viewModel.localStorage.collectAsStateWithLifecycle()
    val diagnosticsExport by viewModel.diagnosticsExport.collectAsStateWithLifecycle()
    val searchDataReset by viewModel.searchDataReset.collectAsStateWithLifecycle()
    val modelPackRollback by viewModel.modelPackRollback.collectAsStateWithLifecycle()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onPermissionResult()
        }
    val modelPackLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importModelPack(uri)
        }
    val diagnosticsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportDiagnostics(uri)
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startIndexing()
        }
    val requestAccess = {
        permissionLauncher.launch(
            MediaPermissionEvaluator.requestPermissions(Build.VERSION.SDK_INT),
        )
    }
    val startIndexing = {
        if (!viewModel.startIndexing() && Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (!onboardingCompleted) {
        SetupScreen(
            catalog = catalog,
            modelPack = modelPack,
            indexing = indexing,
            onImportModelPack = { modelPackLauncher.launch(arrayOf("application/octet-stream")) },
            onRequestAccess = requestAccess,
            onStartIndexing = startIndexing,
            onSelectIndexingMonths = viewModel::setIndexingScopeMonths,
            onSelectIndexingStartDate = viewModel::setIndexingScopeFrom,
            onComplete = viewModel::completeOnboarding,
        )
    } else {
        NaytiAppContent(
            catalog = catalog,
            modelPack = modelPack,
            indexing = indexing,
            search = search,
            searchFilterFacets = searchFilterFacets,
            similar = similar,
            duplicates = duplicates,
            viewerProbe = viewerProbe,
            onLoadThumbnail = viewModel::loadThumbnail,
            localStorage = localStorage,
            diagnosticsExport = diagnosticsExport,
            searchDataReset = searchDataReset,
            modelPackRollback = modelPackRollback,
            onRequestAccess = requestAccess,
            onRefresh = { viewModel.refresh(forceFull = true) },
            onImportModelPack = { modelPackLauncher.launch(arrayOf("application/octet-stream")) },
            onSearch = viewModel::search,
            onFindSimilar = viewModel::findSimilar,
            onFindDuplicates = viewModel::findDuplicates,
            onStartIndexing = startIndexing,
            onPauseIndexing = viewModel::pauseIndexing,
            onStopIndexing = viewModel::stopIndexingForNow,
            onCancelIndexing = viewModel::cancelIndexing,
            onRetryIndexingGaps = viewModel::retryIndexingGaps,
            onSelectIndexingMonths = viewModel::setIndexingScopeMonths,
            onSelectIndexingStartDate = viewModel::setIndexingScopeFrom,
            onProbe = viewModel::probe,
            onClearProbe = viewModel::clearProbe,
            onRefreshStorage = viewModel::refreshLocalStorage,
            onExportDiagnostics = { diagnosticsLauncher.launch("nayti-diagnostics.json") },
            onResetSearchData = viewModel::resetSearchData,
            onRollbackModelPack = viewModel::rollbackModelPack,
        )
    }
}

@Composable
private fun NaytiAppContent(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    search: SearchUiState,
    searchFilterFacets: SearchFilterFacets,
    similar: SimilarUiState,
    duplicates: DuplicateUiState,
    viewerProbe: ViewerProbeState,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    localStorage: LocalStorageSummary,
    diagnosticsExport: DiagnosticsExportState,
    searchDataReset: SearchDataResetState,
    modelPackRollback: ModelPackRollbackState,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onImportModelPack: () -> Unit,
    onSearch: (String, SearchFilter) -> Unit,
    onFindSimilar: (Long) -> Unit,
    onFindDuplicates: (Long) -> Unit,
    onStartIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onStopIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onRetryIndexingGaps: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit,
    onSelectIndexingStartDate: (Long) -> Unit,
    onProbe: (Long) -> Unit,
    onClearProbe: () -> Unit,
    onRefreshStorage: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onResetSearchData: () -> Unit,
    onRollbackModelPack: () -> Unit,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showRootNavigation = RootDestination.entries.any { it.route == currentRoute }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= NavigationRailBreakpoint
        if (useNavigationRail && showRootNavigation) {
            Row(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                RootNavigationRail(
                    currentRoute = currentRoute,
                    onNavigate = navController::navigateToRoot,
                )
                RootNavHost(
                    navController = navController,
                    catalog = catalog,
                    modelPack = modelPack,
                    indexing = indexing,
                    search = search,
                    searchFilterFacets = searchFilterFacets,
                    similar = similar,
                    duplicates = duplicates,
                    viewerProbe = viewerProbe,
                    onLoadThumbnail = onLoadThumbnail,
                    localStorage = localStorage,
                    diagnosticsExport = diagnosticsExport,
                    searchDataReset = searchDataReset,
                    modelPackRollback = modelPackRollback,
                    onRequestAccess = onRequestAccess,
                    onRefresh = onRefresh,
                    onImportModelPack = onImportModelPack,
                    onSearch = onSearch,
                    onFindSimilar = onFindSimilar,
                    onFindDuplicates = onFindDuplicates,
                    onStartIndexing = onStartIndexing,
                    onPauseIndexing = onPauseIndexing,
                    onStopIndexing = onStopIndexing,
                    onCancelIndexing = onCancelIndexing,
                    onRetryIndexingGaps = onRetryIndexingGaps,
                    onSelectIndexingMonths = onSelectIndexingMonths,
                    onSelectIndexingStartDate = onSelectIndexingStartDate,
                    onProbe = onProbe,
                    onClearProbe = onClearProbe,
                    onRefreshStorage = onRefreshStorage,
                    onExportDiagnostics = onExportDiagnostics,
                    onResetSearchData = onResetSearchData,
                    onRollbackModelPack = onRollbackModelPack,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (showRootNavigation) {
                        RootNavigationBar(
                            currentRoute = currentRoute,
                            onNavigate = navController::navigateToRoot,
                        )
                    }
                },
            ) { innerPadding ->
                RootNavHost(
                    navController = navController,
                    catalog = catalog,
                    modelPack = modelPack,
                    indexing = indexing,
                    search = search,
                    searchFilterFacets = searchFilterFacets,
                    similar = similar,
                    duplicates = duplicates,
                    viewerProbe = viewerProbe,
                    onLoadThumbnail = onLoadThumbnail,
                    localStorage = localStorage,
                    diagnosticsExport = diagnosticsExport,
                    searchDataReset = searchDataReset,
                    modelPackRollback = modelPackRollback,
                    onRequestAccess = onRequestAccess,
                    onRefresh = onRefresh,
                    onImportModelPack = onImportModelPack,
                    onSearch = onSearch,
                    onFindSimilar = onFindSimilar,
                    onFindDuplicates = onFindDuplicates,
                    onStartIndexing = onStartIndexing,
                    onPauseIndexing = onPauseIndexing,
                    onStopIndexing = onStopIndexing,
                    onCancelIndexing = onCancelIndexing,
                    onRetryIndexingGaps = onRetryIndexingGaps,
                    onSelectIndexingMonths = onSelectIndexingMonths,
                    onSelectIndexingStartDate = onSelectIndexingStartDate,
                    onProbe = onProbe,
                    onClearProbe = onClearProbe,
                    onRefreshStorage = onRefreshStorage,
                    onExportDiagnostics = onExportDiagnostics,
                    onResetSearchData = onResetSearchData,
                    onRollbackModelPack = onRollbackModelPack,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun RootNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        RootDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.title)) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun RootNavigationRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        RootDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.title)) },
            )
        }
    }
}

private fun NavHostController.navigateToRoot(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun RootNavHost(
    navController: NavHostController,
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    search: SearchUiState,
    searchFilterFacets: SearchFilterFacets,
    similar: SimilarUiState,
    duplicates: DuplicateUiState,
    viewerProbe: ViewerProbeState,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    localStorage: LocalStorageSummary,
    diagnosticsExport: DiagnosticsExportState,
    searchDataReset: SearchDataResetState,
    modelPackRollback: ModelPackRollbackState,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onImportModelPack: () -> Unit,
    onSearch: (String, SearchFilter) -> Unit,
    onFindSimilar: (Long) -> Unit,
    onFindDuplicates: (Long) -> Unit,
    onStartIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onStopIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onRetryIndexingGaps: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit,
    onSelectIndexingStartDate: (Long) -> Unit,
    onProbe: (Long) -> Unit,
    onClearProbe: () -> Unit,
    onRefreshStorage: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onResetSearchData: () -> Unit,
    onRollbackModelPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = RootDestination.Search.route,
        modifier = modifier,
    ) {
            composable(RootDestination.Search.route) {
                SearchScreen(
                    catalog = catalog,
                    modelPack = modelPack,
                    search = search,
                    searchFilterFacets = searchFilterFacets,
                    onLoadThumbnail = onLoadThumbnail,
                    onSearch = onSearch,
                    onOpenAsset = { assetId -> navController.navigate("viewer/$assetId") },
                )
            }
            composable(RootDestination.Readiness.route) {
                ReadinessScreen(
                    catalog = catalog,
                    modelPack = modelPack,
                    indexing = indexing,
                    onLoadThumbnail = onLoadThumbnail,
                    onRequestAccess = onRequestAccess,
                    onRefresh = onRefresh,
                    onStartIndexing = onStartIndexing,
                    onPauseIndexing = onPauseIndexing,
                    onStopIndexing = onStopIndexing,
                    onCancelIndexing = onCancelIndexing,
                    onRetryIndexingGaps = onRetryIndexingGaps,
                    onSelectIndexingMonths = onSelectIndexingMonths,
                    onSelectIndexingStartDate = onSelectIndexingStartDate,
                    onOpenItem = { item -> navController.navigate("viewer/${item.assetId}") },
                )
            }
            composable(RootDestination.Data.route) {
                DataScreen(
                    catalog = catalog,
                    modelPack = modelPack,
                    localStorage = localStorage,
                    diagnosticsExport = diagnosticsExport,
                    searchDataReset = searchDataReset,
                    modelPackRollback = modelPackRollback,
                    indexing = indexing,
                    onRequestAccess = onRequestAccess,
                    onImportModelPack = onImportModelPack,
                    onRefreshStorage = onRefreshStorage,
                    onExportDiagnostics = onExportDiagnostics,
                    onResetSearchData = onResetSearchData,
                    onRollbackModelPack = onRollbackModelPack,
                    onSelectIndexingMonths = onSelectIndexingMonths,
                    onSelectIndexingStartDate = onSelectIndexingStartDate,
                )
            }
            composable(
                route = ViewerRoute,
                arguments = listOf(navArgument("assetId") { type = NavType.LongType }),
            ) { entry ->
                val assetId = checkNotNull(entry.arguments?.getLong("assetId"))
                val searchResult = (search as? SearchUiState.Ready)?.results
                    ?.firstOrNull { result -> result.asset.assetId == assetId }
                val item = catalog.recentItems.firstOrNull { it.assetId == assetId }
                    ?: searchResult?.toCatalogItem()
                    ?: (similar as? SimilarUiState.Ready)?.results
                        ?.firstOrNull { result -> result.asset.assetId == assetId }
                        ?.asset?.toCatalogItem()
                    ?: (duplicates as? DuplicateUiState.Ready)?.results
                        ?.firstOrNull { result -> result.asset.assetId == assetId }
                        ?.asset?.toCatalogItem()
                ViewerScreen(
                    item = item,
                    searchProvenance = searchResult?.hit,
                    accessRevision = catalog.access.value,
                    probeState = viewerProbe,
                    similarState = similar,
                    duplicateState = duplicates,
                    onLoadThumbnail = onLoadThumbnail,
                    onBack = navController::popBackStack,
                    onProbe = { onProbe(assetId) },
                    onFindSimilar = { onFindSimilar(assetId) },
                    onFindDuplicates = { onFindDuplicates(assetId) },
                    onOpenSimilar = { similarAssetId -> navController.navigate("viewer/$similarAssetId") },
                    onClearProbe = onClearProbe,
                )
            }
        }
}

@Composable
internal fun ScreenHeader(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = eyebrow.uppercase(),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.4.sp,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    search: SearchUiState,
    searchFilterFacets: SearchFilterFacets,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onSearch: (String, SearchFilter) -> Unit,
    onOpenAsset: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var dateFilter by rememberSaveable { mutableStateOf(SearchDateFilter.Any) }
    var bucketId by rememberSaveable { mutableStateOf<Long?>(null) }
    var mimeType by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(searchFilterFacets) {
        if (bucketId != null && searchFilterFacets.albums.none { it.bucketId == bucketId }) bucketId = null
        if (mimeType != null && searchFilterFacets.mimeTypes.none { it.mimeType == mimeType }) mimeType = null
    }
    val canSearch =
        catalog.access.permission.scope != MediaAccessScope.None &&
            modelPack.installed != null &&
            modelPack.status != ModelPackRuntimeStatus.Installing

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = NaytiSpacing.ScreenHorizontal,
            vertical = NaytiSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { AppIdentity() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                eyebrow = stringResource(R.string.search_eyebrow),
                title = stringResource(R.string.search_title),
                subtitle = stringResource(R.string.search_subtitle),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSearch,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (catalog.access.permission.scope == MediaAccessScope.None) {
                                R.string.search_waiting_access
                            } else if (modelPack.installed == null) {
                                R.string.search_waiting_model_pack
                            } else {
                                R.string.search_catalog_ready
                            },
                        ),
                    )
                },
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchFilterControls(
                facets = searchFilterFacets,
                dateFilter = dateFilter,
                bucketId = bucketId,
                mimeType = mimeType,
                onDateFilter = { dateFilter = it },
                onBucket = { bucketId = it },
                onMimeType = { mimeType = it },
                onReset = {
                    dateFilter = SearchDateFilter.Any
                    bucketId = null
                    mimeType = null
                },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    onSearch(
                        query,
                        SearchFilter(
                            takenFromMillis = dateFilter.lookbackMillis?.let { lookback -> now - lookback },
                            bucketId = bucketId,
                            mimeType = mimeType,
                        ),
                    )
                },
                enabled = canSearch && query.isNotBlank() && search !is SearchUiState.Searching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (search is SearchUiState.Searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.search_action))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { LibraryStatusCard(catalog) }
        when (search) {
            SearchUiState.Idle,
            SearchUiState.Searching,
            -> Unit
            is SearchUiState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.search_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is SearchUiState.Ready -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.search_result_count,
                            search.results.size,
                            search.results.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (
                    search.semanticStatus == OcrSemanticSearchStatus.NO_ACTIVE_SNAPSHOT ||
                    search.semanticStatus == OcrSemanticSearchStatus.NO_SEMANTIC_MANIFEST
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.search_lexical_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (
                    search.visualStatus == VisualTextSearchStatus.NO_ACTIVE_SNAPSHOT ||
                    search.visualStatus == VisualTextSearchStatus.NO_VISUAL_MANIFEST
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.search_visual_pending),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (search.results.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SearchEmptyState(
                            partial =
                                search.semanticStatus in setOf(
                                    OcrSemanticSearchStatus.NO_ACTIVE_SNAPSHOT,
                                    OcrSemanticSearchStatus.NO_SEMANTIC_MANIFEST,
                                ) ||
                                    search.visualStatus in setOf(
                                        VisualTextSearchStatus.NO_ACTIVE_SNAPSHOT,
                                        VisualTextSearchStatus.NO_VISUAL_MANIFEST,
                                    ),
                        )
                    }
                }
                gridItems(search.results, key = { result -> result.asset.assetId }) { result ->
                    SearchResultCard(
                        result = result,
                        accessRevision = catalog.access.value,
                        onLoadThumbnail = onLoadThumbnail,
                        onClick = { onOpenAsset(result.asset.assetId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchFilterControls(
    facets: SearchFilterFacets,
    dateFilter: SearchDateFilter,
    bucketId: Long?,
    mimeType: String?,
    onDateFilter: (SearchDateFilter) -> Unit,
    onBucket: (Long?) -> Unit,
    onMimeType: (String?) -> Unit,
    onReset: () -> Unit,
) {
    var albumsExpanded by rememberSaveable { mutableStateOf(false) }
    var typesExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedAlbum = facets.albums.firstOrNull { it.bucketId == bucketId }
    val hasFilter = dateFilter != SearchDateFilter.Any || bucketId != null || mimeType != null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.search_filters_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasFilter) {
                TextButton(onClick = onReset) { Text(stringResource(R.string.search_filters_reset)) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchDateFilter.entries.forEach { option ->
                FilterChip(
                    selected = dateFilter == option,
                    onClick = { onDateFilter(option) },
                    label = { Text(stringResource(option.label)) },
                )
            }
            Box {
                OutlinedButton(onClick = { albumsExpanded = true }) {
                    Text(selectedAlbum?.displayName ?: stringResource(R.string.search_album_all))
                }
                DropdownMenu(
                    expanded = albumsExpanded,
                    onDismissRequest = { albumsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_album_all)) },
                        onClick = {
                            onBucket(null)
                            albumsExpanded = false
                        },
                    )
                    facets.albums.forEach { album ->
                        DropdownMenuItem(
                            text = { Text("${album.displayName} · ${album.assetCount}") },
                            onClick = {
                                onBucket(album.bucketId)
                                albumsExpanded = false
                            },
                        )
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { typesExpanded = true }) {
                    Text(mimeType?.toDisplayMimeType() ?: stringResource(R.string.search_type_all))
                }
                DropdownMenu(
                    expanded = typesExpanded,
                    onDismissRequest = { typesExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_type_all)) },
                        onClick = {
                            onMimeType(null)
                            typesExpanded = false
                        },
                    )
                    facets.mimeTypes.forEach { type ->
                        DropdownMenuItem(
                            text = { Text("${type.mimeType.toDisplayMimeType()} · ${type.assetCount}") },
                            onClick = {
                                onMimeType(type.mimeType)
                                typesExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun String.toDisplayMimeType(): String =
    substringAfter('/').uppercase().replace("JPEG", "JPG")

@Composable
private fun SearchResultCard(
    result: SearchResultItem,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                PhotoThumbnail(
                    key = MediaKey(result.asset.volumeName, result.asset.mediaStoreId),
                    accessRevision = accessRevision,
                    description = result.asset.displayName,
                    onLoad = onLoadThumbnail,
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = evidenceLabel(result.hit.reason),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = result.asset.displayName ?: stringResource(
                        R.string.catalog_unnamed_photo,
                        result.asset.assetId,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = result.hit.displaySnippet ?: stringResource(R.string.search_visual_result),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(
    key: MediaKey,
    accessRevision: Long,
    description: String?,
    onLoad: suspend (MediaKey, Long) -> Bitmap?,
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = key,
        key2 = accessRevision,
    ) {
        value = null
        value = onLoad(key, accessRevision)
    }
    if (bitmap != null) {
        Image(
            bitmap = checkNotNull(bitmap).asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SearchEmptyState(partial: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
        ) {
            Text(
                text = stringResource(R.string.search_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (partial) R.string.search_empty_partial else R.string.search_empty_ready,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun evidenceLabel(evidence: UnifiedSearchReason): String =
    stringResource(
        when (evidence) {
            UnifiedSearchReason.EXACT_IDENTIFIER -> R.string.evidence_identifier
            UnifiedSearchReason.QUOTED_PHRASE -> R.string.evidence_phrase
            UnifiedSearchReason.PERSON_NAME -> R.string.evidence_person
            UnifiedSearchReason.LITERAL_TEXT -> R.string.evidence_text
            UnifiedSearchReason.FUZZY_TEXT -> R.string.evidence_fuzzy
            UnifiedSearchReason.SEMANTIC_TEXT -> R.string.evidence_semantic
            UnifiedSearchReason.VISUAL_CONTENT -> R.string.evidence_visual
        },
    )

@Composable
private fun AppIdentity() {
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
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(100)) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.local_only),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LibraryStatusCard(catalog: CatalogRuntimeState) {
    val connected = catalog.access.permission.scope != MediaAccessScope.None
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (catalog.status == CatalogRuntimeStatus.Reconciling) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text =
                        if (connected) {
                            stringResource(R.string.library_connected)
                        } else {
                            stringResource(R.string.library_not_connected)
                        },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        if (connected) {
                            pluralStringResource(
                                R.plurals.catalog_available_photos,
                                catalog.summary.available.toInt(),
                                catalog.summary.available,
                            )
                        } else {
                            stringResource(R.string.library_not_connected_details)
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReadinessScreen(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onStartIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onStopIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onRetryIndexingGaps: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit,
    onSelectIndexingStartDate: (Long) -> Unit,
    onOpenItem: (CatalogItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = stringResource(R.string.readiness_eyebrow),
                title = stringResource(R.string.readiness_title),
                subtitle = stringResource(R.string.readiness_subtitle),
            )
        }
        item { AccessCard(catalog, onRequestAccess, onRefresh) }
        item {
            IndexingScopeCard(
                indexing = indexing,
                onSelectMonths = onSelectIndexingMonths,
                onSelectStartDate = onSelectIndexingStartDate,
            )
        }
        item {
            OcrReadinessCard(
                catalog = catalog,
                modelPack = modelPack,
                indexing = indexing,
                onStartIndexing = onStartIndexing,
                onPauseIndexing = onPauseIndexing,
                onStopIndexing = onStopIndexing,
                onCancelIndexing = onCancelIndexing,
                onRetryIndexingGaps = onRetryIndexingGaps,
            )
        }
        item {
            Text(
                text = stringResource(R.string.capability_section_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (indexing.capabilities.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.capability_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(indexing.capabilities, key = { coverage -> coverage.capability }) { coverage ->
                CapabilityCoverageCard(coverage)
            }
        }
        item {
            MetricCard(
                title = stringResource(R.string.catalog_available),
                value = catalog.summary.available.toString(),
                supporting = stringResource(R.string.catalog_available_details),
                icon = Icons.Outlined.CheckCircle,
            )
        }
        if (catalog.summary.outOfScope + catalog.summary.offline + catalog.summary.missing > 0) {
            item {
                MetricCard(
                    title = stringResource(R.string.catalog_not_visible),
                    value =
                        (catalog.summary.outOfScope + catalog.summary.offline + catalog.summary.missing)
                            .toString(),
                    supporting = stringResource(R.string.catalog_not_visible_details),
                    icon = Icons.Outlined.Lock,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.catalog_recent_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (catalog.recentItems.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.catalog_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(catalog.recentItems, key = CatalogItem::assetId) { item ->
                CatalogItemCard(
                    item = item,
                    accessRevision = catalog.access.value,
                    onLoadThumbnail = onLoadThumbnail,
                    onClick = { onOpenItem(item) },
                )
            }
        }
    }
}

@Composable
private fun CapabilityCoverageCard(coverage: SearchCapabilityCoverage) {
    val progress =
        if (coverage.accessible == 0L) {
            0f
        } else {
            (coverage.committed.toDouble() / coverage.accessible.toDouble()).toFloat()
                .coerceIn(0f, 1f)
        }
    Card(shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = capabilityTitle(coverage.capability),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = capabilityDescription(coverage.capability),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.capability_counts,
                    coverage.committed,
                    coverage.accessible,
                    coverage.permanentGaps,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun capabilityTitle(capability: SearchCapability): String = stringResource(
    when (capability) {
        SearchCapability.TEXT -> R.string.capability_text_title
        SearchCapability.MEANING -> R.string.capability_meaning_title
        SearchCapability.VISUAL -> R.string.capability_visual_title
        SearchCapability.DUPLICATES -> R.string.capability_duplicates_title
    },
)

@Composable
private fun capabilityDescription(capability: SearchCapability): String = stringResource(
    when (capability) {
        SearchCapability.TEXT -> R.string.capability_text_body
        SearchCapability.MEANING -> R.string.capability_meaning_body
        SearchCapability.VISUAL -> R.string.capability_visual_body
        SearchCapability.DUPLICATES -> R.string.capability_duplicates_body
    },
)

@Composable
internal fun OcrReadinessCard(
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    onStartIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onStopIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onRetryIndexingGaps: () -> Unit,
) {
    val canStart =
        catalog.summary.available > 0 &&
            modelPack.installed != null &&
            modelPack.status != ModelPackRuntimeStatus.Installing &&
            indexing.outstanding > 0 &&
            indexing.status != OcrIndexingStatus.Running
    val resumable = indexing.status == OcrIndexingStatus.Paused ||
        indexing.status == OcrIndexingStatus.Waiting
    val cancellable = indexing.operationId != null && indexing.operationState !in setOf(
        IndexOperationState.COMPLETED,
        IndexOperationState.COMPLETED_WITH_GAPS,
        IndexOperationState.CANCELLED,
    )
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ocr_readiness_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.ocr_readiness_counts,
                            indexing.committed,
                            indexing.accessible,
                            indexing.permanentGaps,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (indexing.status == OcrIndexingStatus.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            if (indexing.status == OcrIndexingStatus.Running) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onPauseIndexing) {
                        Text(stringResource(R.string.ocr_pause))
                    }
                    TextButton(onClick = onStopIndexing) {
                        Text(stringResource(R.string.ocr_stop_for_now))
                    }
                }
            } else {
                Button(onClick = onStartIndexing, enabled = canStart) {
                    Text(
                        stringResource(
                            if (resumable) R.string.ocr_resume else R.string.ocr_start,
                        ),
                    )
                }
            }
            if (cancellable) {
                TextButton(onClick = onCancelIndexing) {
                    Text(stringResource(R.string.ocr_cancel))
                }
            }
            if (indexing.permanentGaps > 0 && indexing.status != OcrIndexingStatus.Running) {
                OutlinedButton(onClick = onRetryIndexingGaps) {
                    Text(stringResource(R.string.ocr_retry_gaps))
                }
            }
            indexing.errorCode?.let { code ->
                Text(
                    text = preparationIssue(code),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun AccessCard(
    catalog: CatalogRuntimeState,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = accessTitle(catalog.access.permission.scope),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (catalog.status == CatalogRuntimeStatus.Reconciling) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                }
            }
            Text(
                text = accessDescription(catalog.access.permission.scope),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (catalog.access.permission.scope == MediaAccessScope.None) {
                    Button(onClick = onRequestAccess) {
                        Text(stringResource(R.string.connect_library))
                    }
                } else {
                    FilledTonalButton(onClick = onRefresh) {
                        Text(stringResource(R.string.refresh_catalog))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        TextButton(onClick = onRequestAccess) {
                            Text(stringResource(R.string.change_selection))
                        }
                    }
                }
            }
            catalog.lastErrorCode?.let {
                Text(
                    text = stringResource(R.string.catalog_retryable_error),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun accessTitle(scope: MediaAccessScope): String =
    when (scope) {
        MediaAccessScope.None -> stringResource(R.string.access_none_title)
        MediaAccessScope.Selected -> stringResource(R.string.access_selected_title)
        MediaAccessScope.Full -> stringResource(R.string.access_full_title)
    }

@Composable
private fun accessDescription(scope: MediaAccessScope): String =
    when (scope) {
        MediaAccessScope.None -> stringResource(R.string.access_none_details)
        MediaAccessScope.Selected -> stringResource(R.string.access_selected_details)
        MediaAccessScope.Full -> stringResource(R.string.access_full_details)
    }

@Composable
private fun CatalogItemCard(
    item: CatalogItem,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)) {
                PhotoThumbnail(
                    key = item.key,
                    accessRevision = accessRevision,
                    description = item.displayName,
                    onLoad = onLoadThumbnail,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName ?: stringResource(R.string.catalog_unnamed_photo, item.assetId),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.catalog_item_details,
                        item.width,
                        item.height,
                        item.bucketDisplayName.orEmpty(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ViewerScreen(
    item: CatalogItem?,
    searchProvenance: app.nayti.indexer.UnifiedSearchHit?,
    accessRevision: Long,
    probeState: ViewerProbeState,
    similarState: SimilarUiState,
    duplicateState: DuplicateUiState,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onBack: () -> Unit,
    onProbe: () -> Unit,
    onFindSimilar: () -> Unit,
    onFindDuplicates: () -> Unit,
    onOpenSimilar: (Long) -> Unit,
    onClearProbe: () -> Unit,
) {
    LaunchedEffect(item?.assetId, accessRevision) {
        if (item != null) onProbe()
    }
    DisposableEffect(Unit) { onDispose(onClearProbe) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.viewer_back)) } }
        if (item == null) {
            item {
                ScreenHeader(
                    eyebrow = stringResource(R.string.viewer_eyebrow),
                    title = stringResource(R.string.viewer_access_changed),
                    subtitle = stringResource(R.string.viewer_access_changed_details),
                )
            }
            searchProvenance?.let { provenance ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NaytiSpacing.Card),
                            verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Compact),
                        ) {
                            Text(
                                text = stringResource(R.string.viewer_match_reason_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = evidenceLabel(provenance.reason),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            provenance.displaySnippet?.takeIf(String::isNotBlank)?.let { snippet ->
                                Text(
                                    text = snippet,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            item {
                ScreenHeader(
                    eyebrow = stringResource(R.string.viewer_eyebrow),
                    title = item.displayName ?: stringResource(R.string.catalog_unnamed_photo, item.assetId),
                    subtitle = stringResource(R.string.viewer_subtitle),
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (probeState) {
                            ViewerProbeState.Idle,
                            ViewerProbeState.Loading,
                            -> CircularProgressIndicator()
                            is ViewerProbeState.Ready -> {
                                DisposableEffect(probeState.image) {
                                    onDispose(probeState.image::close)
                                }
                                Image(
                                    bitmap = probeState.image.bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                                OcrRegionOverlay(
                                    imageWidth = probeState.image.decodedWidth,
                                    imageHeight = probeState.image.decodedHeight,
                                    regions = probeState.regions,
                                    matchedOrdinals = probeState.matchedRegionOrdinals,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            is ViewerProbeState.Failed ->
                                Text(
                                    text = stringResource(R.string.viewer_read_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(24.dp),
                                )
                        }
                    }
                }
            }
            item {
                Text(
                    text =
                        if (probeState is ViewerProbeState.Ready) {
                            stringResource(
                                R.string.viewer_ocr_details,
                                probeState.image.sourceWidth,
                                probeState.image.sourceHeight,
                                probeState.regions.size,
                            )
                        } else {
                            stringResource(R.string.viewer_privacy_note)
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FilledTonalButton(
                    onClick = onFindSimilar,
                    enabled = similarState !is SimilarUiState.Searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (similarState is SimilarUiState.Searching && similarState.sourceAssetId == item.assetId) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.viewer_find_similar))
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onFindDuplicates,
                    enabled = duplicateState !is DuplicateUiState.Searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (duplicateState is DuplicateUiState.Searching && duplicateState.sourceAssetId == item.assetId) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.viewer_find_duplicates))
                    }
                }
            }
            when (similarState) {
                SimilarUiState.Idle,
                is SimilarUiState.Searching,
                -> Unit
                is SimilarUiState.Failed -> if (similarState.sourceAssetId == item.assetId) {
                    item {
                        Text(
                            text = stringResource(R.string.viewer_similar_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is SimilarUiState.Ready -> if (similarState.sourceAssetId == item.assetId) {
                    if (similarState.status != VisualSimilaritySearchStatus.READY) {
                        item {
                            Text(
                                text = stringResource(R.string.viewer_similar_not_ready),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (similarState.results.isEmpty()) {
                        item { Text(stringResource(R.string.viewer_similar_empty)) }
                    } else {
                        item {
                            Text(
                                text = stringResource(R.string.viewer_similar_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(similarState.results, key = { result -> result.asset.assetId }) { result ->
                            SimilarResultCard(
                                result = result,
                                accessRevision = accessRevision,
                                onLoadThumbnail = onLoadThumbnail,
                                onClick = { onOpenSimilar(result.asset.assetId) },
                            )
                        }
                    }
                }
            }
            when (duplicateState) {
                DuplicateUiState.Idle,
                is DuplicateUiState.Searching,
                -> Unit
                is DuplicateUiState.Failed -> if (duplicateState.sourceAssetId == item.assetId) {
                    item {
                        Text(
                            text = stringResource(R.string.viewer_duplicates_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is DuplicateUiState.Ready -> if (duplicateState.sourceAssetId == item.assetId) {
                    if (duplicateState.status != PerceptualHashSearchStatus.READY) {
                        item {
                            Text(
                                text = stringResource(R.string.viewer_duplicates_not_ready),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (duplicateState.results.isEmpty()) {
                        item { Text(stringResource(R.string.viewer_duplicates_empty)) }
                    } else {
                        item {
                            Text(
                                text = stringResource(R.string.viewer_duplicates_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(duplicateState.results, key = { result -> result.asset.assetId }) { result ->
                            DuplicateResultCard(
                                result = result,
                                accessRevision = accessRevision,
                                onLoadThumbnail = onLoadThumbnail,
                                onClick = { onOpenSimilar(result.asset.assetId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarResultCard(
    result: SimilarResultItem,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)) {
                PhotoThumbnail(
                    key = MediaKey(result.asset.volumeName, result.asset.mediaStoreId),
                    accessRevision = accessRevision,
                    description = result.asset.displayName,
                    onLoad = onLoadThumbnail,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = result.asset.displayName ?: stringResource(
                        R.string.catalog_unnamed_photo,
                        result.asset.assetId,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.evidence_visual),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DuplicateResultCard(
    result: DuplicateResultItem,
    accessRevision: Long,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(NaytiSpacing.Item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)) {
                PhotoThumbnail(
                    key = MediaKey(result.asset.volumeName, result.asset.mediaStoreId),
                    accessRevision = accessRevision,
                    description = result.asset.displayName,
                    onLoad = onLoadThumbnail,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = result.asset.displayName ?: stringResource(
                        R.string.catalog_unnamed_photo,
                        result.asset.assetId,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (result.match.distance == 0) {
                            R.string.viewer_duplicate_exact
                        } else {
                            R.string.viewer_duplicate_near
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OcrRegionOverlay(
    imageWidth: Int,
    imageHeight: Int,
    regions: List<OcrRegionEntity>,
    matchedOrdinals: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val regularColor = MaterialTheme.colorScheme.tertiary
    val matchedColor = MaterialTheme.colorScheme.primary
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
            fun x(value: Int) = offsetX + value / 1_000_000f * renderedWidth
            fun y(value: Int) = offsetY + value / 1_000_000f * renderedHeight
            val path =
                Path().apply {
                    moveTo(x(region.x0Micros), y(region.y0Micros))
                    lineTo(x(region.x1Micros), y(region.y1Micros))
                    lineTo(x(region.x2Micros), y(region.y2Micros))
                    lineTo(x(region.x3Micros), y(region.y3Micros))
                    close()
                }
            drawPath(
                path = path,
                color = if (region.ordinal in matchedOrdinals) matchedColor else regularColor,
                style = Stroke(width = if (region.ordinal in matchedOrdinals) 5f else 2.5f),
            )
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, supporting: String, icon: ImageVector) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun preparationIssue(code: String): String = stringResource(
    when (code) {
        "THERMAL_SEVERE" -> R.string.preparation_paused_thermal
        "MEMORY_PRESSURE" -> R.string.preparation_paused_memory
        "STORAGE_RESERVE" -> R.string.preparation_paused_storage
        "BATTERY_SAVER" -> R.string.preparation_paused_battery_saver
        "BATTERY_LOW" -> R.string.preparation_paused_battery_low
        else -> R.string.ocr_index_failed
    },
)

private fun SearchResultItem.toCatalogItem(): CatalogItem =
    asset.toCatalogItem()

private fun app.nayti.storage.CatalogAssetEntity.toCatalogItem(): CatalogItem =
    CatalogItem(
        assetId = assetId,
        key = app.nayti.platform.media.MediaKey(volumeName, mediaStoreId),
        displayName = displayName,
        bucketDisplayName = bucketDisplayName,
        mimeType = mimeType,
        width = width,
        height = height,
        dateTakenMillis = dateTakenMillis,
    )

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun NaytiPreview() {
    NaytiTheme(darkTheme = false) {
        NaytiAppContent(
            catalog =
                CatalogRuntimeState(
                    status = CatalogRuntimeStatus.PermissionRequired,
                    access =
                        AccessRevision(
                            1,
                            MediaPermissionSnapshot(MediaAccessScope.None, false, false),
                        ),
                    summary = CatalogSummary.Empty,
                    recentItems = emptyList(),
                    lastErrorCode = null,
                ),
            modelPack =
                ModelPackRuntimeState(
                    status = ModelPackRuntimeStatus.Missing,
                    installed = null,
                    candidate = null,
                    errorCode = null,
                ),
            indexing =
                OcrIndexingState(
                    status = OcrIndexingStatus.Idle,
                    accessible = 0,
                    committed = 0,
                    permanentGaps = 0,
                    outstanding = 0,
                    lastSlicePublished = 0,
                    errorCode = null,
                ),
            search = SearchUiState.Idle,
            searchFilterFacets = SearchFilterFacets(emptyList(), emptyList()),
            similar = SimilarUiState.Idle,
            duplicates = DuplicateUiState.Idle,
            viewerProbe = ViewerProbeState.Idle,
            onLoadThumbnail = { _, _ -> null },
            localStorage = LocalStorageSummary(0L, 0L),
            diagnosticsExport = DiagnosticsExportState.Idle,
            searchDataReset = SearchDataResetState.Idle,
            modelPackRollback = ModelPackRollbackState.Unavailable(null),
            onRequestAccess = {},
            onRefresh = {},
            onImportModelPack = {},
            onSearch = { _, _ -> },
            onFindSimilar = {},
            onFindDuplicates = {},
            onStartIndexing = {},
            onPauseIndexing = {},
            onStopIndexing = {},
            onCancelIndexing = {},
            onRetryIndexingGaps = {},
            onSelectIndexingMonths = {},
            onSelectIndexingStartDate = {},
            onProbe = {},
            onClearProbe = {},
            onRefreshStorage = {},
            onExportDiagnostics = {},
            onResetSearchData = {},
            onRollbackModelPack = {},
        )
    }
}
