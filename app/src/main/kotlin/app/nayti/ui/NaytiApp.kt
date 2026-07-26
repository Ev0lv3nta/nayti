package app.nayti.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.nayti.R
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.CatalogSummary
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.SearchFilter
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaKey
import app.nayti.platform.media.MediaPermissionEvaluator
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiTheme
import app.nayti.ui.library.LibrarySearchScreen
import app.nayti.ui.preparation.PreparationSheet
import app.nayti.ui.shell.ShellStatusBar
import app.nayti.ui.shell.ShellStatusMapper
import app.nayti.ui.viewer.PhotoViewerScreen

private enum class RootDestination(
    val route: String,
    @param:StringRes val title: Int,
    val icon: NaytiIcon,
) {
    Search("search", R.string.nav_search, NaytiIcon.Search),
    Data("data", R.string.nav_data, NaytiIcon.Settings),
}

private const val ViewerRoute = "viewer/{assetId}"
private val NavigationRailBreakpoint = 700.dp

@Composable
fun NaytiApp(viewModel: CatalogViewModel = viewModel()) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val modelPack by viewModel.modelPack.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val similar by viewModel.similar.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
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
            library = library,
            search = search,
            similar = similar,
            duplicates = duplicates,
            viewer = viewer,
            onLoadThumbnail = viewModel::loadThumbnail,
            localStorage = localStorage,
            diagnosticsExport = diagnosticsExport,
            searchDataReset = searchDataReset,
            modelPackRollback = modelPackRollback,
            onRequestAccess = requestAccess,
            onImportModelPack = { modelPackLauncher.launch(arrayOf("application/octet-stream")) },
            onSearch = viewModel::search,
            onCancelSearch = viewModel::cancelSearch,
            onLoadMoreLibrary = viewModel::loadMoreLibrary,
            onRetryLibrary = viewModel::retryLibrary,
            onFindSimilar = viewModel::findSimilar,
            onFindDuplicates = viewModel::findDuplicates,
            onStartIndexing = startIndexing,
            onPauseIndexing = viewModel::pauseIndexing,
            onCancelIndexing = viewModel::cancelIndexing,
            onRetryIndexingGaps = viewModel::retryIndexingGaps,
            onSelectIndexingMonths = viewModel::setIndexingScopeMonths,
            onSelectIndexingStartDate = viewModel::setIndexingScopeFrom,
            onOpenViewer = viewModel::openViewer,
            onCloseViewer = viewModel::closeViewer,
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
    library: LibraryUiState,
    search: SearchUiState,
    similar: SimilarUiState,
    duplicates: DuplicateUiState,
    viewer: ViewerUiState,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    localStorage: LocalStorageSummary,
    diagnosticsExport: DiagnosticsExportState,
    searchDataReset: SearchDataResetState,
    modelPackRollback: ModelPackRollbackState,
    onRequestAccess: () -> Unit,
    onImportModelPack: () -> Unit,
    onSearch: (String, SearchFilter, SearchChannelSelection) -> Unit,
    onCancelSearch: () -> Unit,
    onLoadMoreLibrary: () -> Unit,
    onRetryLibrary: () -> Unit,
    onFindSimilar: (Long) -> Unit,
    onFindDuplicates: (Long) -> Unit,
    onStartIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onRetryIndexingGaps: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit,
    onSelectIndexingStartDate: (Long) -> Unit,
    onOpenViewer: (Long) -> Unit,
    onCloseViewer: (Long) -> Unit,
    onRefreshStorage: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onResetSearchData: () -> Unit,
    onRollbackModelPack: () -> Unit,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showRootNavigation = RootDestination.entries.any { it.route == currentRoute }
    val shellStatus = ShellStatusMapper.map(catalog, modelPack, indexing)
    var showPreparation by rememberSaveable { mutableStateOf(false) }

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
                Column(modifier = Modifier.weight(1f)) {
                    ShellStatusBar(
                        status = shellStatus,
                        onOpenDetails = { showPreparation = true },
                    )
                    RootNavHost(
                        navController = navController,
                        catalog = catalog,
                        modelPack = modelPack,
                        indexing = indexing,
                        library = library,
                        search = search,
                        similar = similar,
                        duplicates = duplicates,
                        viewer = viewer,
                        onLoadThumbnail = onLoadThumbnail,
                        localStorage = localStorage,
                        diagnosticsExport = diagnosticsExport,
                        searchDataReset = searchDataReset,
                        modelPackRollback = modelPackRollback,
                        onRequestAccess = onRequestAccess,
                        onImportModelPack = onImportModelPack,
                        onSearch = onSearch,
                        onCancelSearch = onCancelSearch,
                        onLoadMoreLibrary = onLoadMoreLibrary,
                        onRetryLibrary = onRetryLibrary,
                        onFindSimilar = onFindSimilar,
                        onFindDuplicates = onFindDuplicates,
                        onStartIndexing = onStartIndexing,
                        onOpenPreparation = { showPreparation = true },
                        onSelectIndexingMonths = onSelectIndexingMonths,
                        onSelectIndexingStartDate = onSelectIndexingStartDate,
                        onOpenViewer = onOpenViewer,
                        onCloseViewer = onCloseViewer,
                        onRefreshStorage = onRefreshStorage,
                        onExportDiagnostics = onExportDiagnostics,
                        onResetSearchData = onResetSearchData,
                        onRollbackModelPack = onRollbackModelPack,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (showRootNavigation) {
                        ShellStatusBar(
                            status = shellStatus,
                            onOpenDetails = { showPreparation = true },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                        )
                    }
                },
            ) { innerPadding ->
                RootNavHost(
                    navController = navController,
                    catalog = catalog,
                    modelPack = modelPack,
                    indexing = indexing,
                    library = library,
                    search = search,
                    similar = similar,
                    duplicates = duplicates,
                    viewer = viewer,
                    onLoadThumbnail = onLoadThumbnail,
                    localStorage = localStorage,
                    diagnosticsExport = diagnosticsExport,
                    searchDataReset = searchDataReset,
                    modelPackRollback = modelPackRollback,
                    onRequestAccess = onRequestAccess,
                    onImportModelPack = onImportModelPack,
                    onSearch = onSearch,
                    onCancelSearch = onCancelSearch,
                    onLoadMoreLibrary = onLoadMoreLibrary,
                    onRetryLibrary = onRetryLibrary,
                    onFindSimilar = onFindSimilar,
                    onFindDuplicates = onFindDuplicates,
                    onStartIndexing = onStartIndexing,
                    onOpenPreparation = { showPreparation = true },
                    onSelectIndexingMonths = onSelectIndexingMonths,
                    onSelectIndexingStartDate = onSelectIndexingStartDate,
                    onOpenViewer = onOpenViewer,
                    onCloseViewer = onCloseViewer,
                    onRefreshStorage = onRefreshStorage,
                    onExportDiagnostics = onExportDiagnostics,
                    onResetSearchData = onResetSearchData,
                    onRollbackModelPack = onRollbackModelPack,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
    if (showPreparation) {
        PreparationSheet(
            catalog = catalog,
            modelPack = modelPack,
            indexing = indexing,
            onDismiss = { showPreparation = false },
            onRequestAccess = {
                showPreparation = false
                onRequestAccess()
            },
            onImportModels = {
                showPreparation = false
                onImportModelPack()
            },
            onStart = onStartIndexing,
            onPause = onPauseIndexing,
            onCancel = onCancelIndexing,
            onRetryGaps = onRetryIndexingGaps,
            onSelectMonths = onSelectIndexingMonths,
            onSelectStartDate = onSelectIndexingStartDate,
            onOpenSettings = {
                showPreparation = false
                navController.navigateToRoot(RootDestination.Data.route)
            },
        )
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
                icon = { NaytiIconMark(destination.icon) },
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
                icon = { NaytiIconMark(destination.icon) },
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

private fun NavHostController.navigateToViewer(
    assetId: Long,
    replaceCurrent: Boolean = false,
) {
    navigate("viewer/$assetId") {
        if (replaceCurrent) {
            popUpTo(ViewerRoute) {
                inclusive = true
            }
        }
        launchSingleTop = true
    }
}

internal fun viewerSequence(
    assetId: Long,
    library: LibraryUiState,
    search: SearchUiState,
    similar: SimilarUiState,
    duplicates: DuplicateUiState,
): List<Long> {
    val candidates =
        listOf(
            (search as? SearchUiState.Ready)?.results?.map { result -> result.asset.assetId },
            (similar as? SimilarUiState.Ready)?.results?.map { result -> result.asset.assetId },
            (duplicates as? DuplicateUiState.Ready)?.results?.map { result -> result.asset.assetId },
            library.items.map { item -> item.assetId },
        )
    return candidates
        .firstOrNull { ids -> ids?.contains(assetId) == true }
        .orEmpty()
        .ifEmpty { listOf(assetId) }
}

@Composable
private fun RootNavHost(
    navController: NavHostController,
    catalog: CatalogRuntimeState,
    modelPack: ModelPackRuntimeState,
    indexing: OcrIndexingState,
    library: LibraryUiState,
    search: SearchUiState,
    similar: SimilarUiState,
    duplicates: DuplicateUiState,
    viewer: ViewerUiState,
    onLoadThumbnail: suspend (MediaKey, Long) -> Bitmap?,
    localStorage: LocalStorageSummary,
    diagnosticsExport: DiagnosticsExportState,
    searchDataReset: SearchDataResetState,
    modelPackRollback: ModelPackRollbackState,
    onRequestAccess: () -> Unit,
    onImportModelPack: () -> Unit,
    onSearch: (String, SearchFilter, SearchChannelSelection) -> Unit,
    onCancelSearch: () -> Unit,
    onLoadMoreLibrary: () -> Unit,
    onRetryLibrary: () -> Unit,
    onFindSimilar: (Long) -> Unit,
    onFindDuplicates: (Long) -> Unit,
    onStartIndexing: () -> Unit,
    onOpenPreparation: () -> Unit,
    onSelectIndexingMonths: (Long?) -> Unit,
    onSelectIndexingStartDate: (Long) -> Unit,
    onOpenViewer: (Long) -> Unit,
    onCloseViewer: (Long) -> Unit,
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
                LibrarySearchScreen(
                    library = library,
                    search = search,
                    indexing = indexing,
                    accessScope = catalog.access.permission.scope,
                    accessRevision = catalog.access.value,
                    modelReady =
                        catalog.access.permission.scope != MediaAccessScope.None &&
                            modelPack.installed != null &&
                            modelPack.status != ModelPackRuntimeStatus.Installing,
                    onLoadThumbnail = onLoadThumbnail,
                    onLoadMore = onLoadMoreLibrary,
                    onRetryLibrary = onRetryLibrary,
                    onRequestAccess = onRequestAccess,
                    onSearch = onSearch,
                    onCancelSearch = onCancelSearch,
                    onOpenAsset = { assetId -> navController.navigateToViewer(assetId) },
                    onOpenSettings = {
                        navController.navigateToRoot(RootDestination.Data.route)
                    },
                )
            }
            composable(RootDestination.Data.route) {
                SettingsScreen(
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
                    onStartIndexing = onStartIndexing,
                    onOpenPreparation = onOpenPreparation,
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
                val sequence = viewerSequence(assetId, library, search, similar, duplicates)
                val position = sequence.indexOf(assetId)
                PhotoViewerScreen(
                    assetId = assetId,
                    state = viewer,
                    searchProvenance = searchResult?.hit,
                    previousAssetId = sequence.getOrNull(position - 1),
                    nextAssetId = sequence.getOrNull(position + 1),
                    accessRevision = catalog.access.value,
                    similarState = similar,
                    duplicateState = duplicates,
                    onLoadThumbnail = onLoadThumbnail,
                    onBack = navController::popBackStack,
                    onOpen = { onOpenViewer(assetId) },
                    onClose = onCloseViewer,
                    onOpenAsset = { target -> navController.navigateToViewer(target, replaceCurrent = true) },
                    onFindSimilar = { onFindSimilar(assetId) },
                    onFindDuplicates = { onFindDuplicates(assetId) },
                )
            }
        }
}


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
            library = LibraryUiState(),
            search = SearchUiState.Idle,
            similar = SimilarUiState.Idle,
            duplicates = DuplicateUiState.Idle,
            viewer = ViewerUiState.Idle,
            onLoadThumbnail = { _, _ -> null },
            localStorage = LocalStorageSummary(0L, 0L),
            diagnosticsExport = DiagnosticsExportState.Idle,
            searchDataReset = SearchDataResetState.Idle,
            modelPackRollback = ModelPackRollbackState.Unavailable(null),
            onRequestAccess = {},
            onImportModelPack = {},
            onSearch = { _, _, _ -> },
            onCancelSearch = {},
            onLoadMoreLibrary = {},
            onRetryLibrary = {},
            onFindSimilar = {},
            onFindDuplicates = {},
            onStartIndexing = {},
            onPauseIndexing = {},
            onCancelIndexing = {},
            onRetryIndexingGaps = {},
            onSelectIndexingMonths = {},
            onSelectIndexingStartDate = {},
            onOpenViewer = {},
            onCloseViewer = {},
            onRefreshStorage = {},
            onExportDiagnostics = {},
            onResetSearchData = {},
            onRollbackModelPack = {},
        )
    }
}
