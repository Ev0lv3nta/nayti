package app.nayti.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nayti.indexing.IndexingServiceController
import app.nayti.BuildConfig
import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.CatalogItem
import app.nayti.indexer.LibraryFeed
import app.nayti.indexer.LibraryFilterFacets
import app.nayti.indexer.PhotoAvailability
import app.nayti.indexer.PhotoEvidence
import app.nayti.indexer.ModelPackActivationRuntime
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrSemanticSearchStatus
import app.nayti.indexer.PerceptualHashSearch
import app.nayti.indexer.PerceptualHashSearchStatus
import app.nayti.indexer.SearchFilter
import app.nayti.indexer.SearchChannelSelection
import app.nayti.indexer.QuarantineGarbageCollector
import app.nayti.indexer.UnifiedSearch
import app.nayti.indexer.UnifiedSearchHit
import app.nayti.indexer.VisualSimilarityHit
import app.nayti.indexer.VisualSimilaritySearch
import app.nayti.indexer.VisualSimilaritySearchStatus
import app.nayti.indexer.VisualTextSearchStatus
import app.nayti.platform.media.DecodedMediaImage
import app.nayti.platform.media.MediaDecodeAccessException
import app.nayti.platform.media.MediaDecodeContentException
import app.nayti.platform.media.MediaDecodeIoException
import app.nayti.platform.media.MediaKey
import app.nayti.ml.runtime.pack.SafModelPackSource
import app.nayti.storage.CatalogStorage
import app.nayti.storage.SearchFilterFacets
import app.nayti.search.engine.similarity.PerceptualHashMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

enum class ViewerUnavailableReason {
    AccessRemoved,
    AccessChanged,
    VolumeOffline,
    Pending,
    Trashed,
    Missing,
    CannotDecode,
    TemporarilyUnavailable,
}

private val PhotoAvailability.unavailableReason: ViewerUnavailableReason?
    get() =
        when (this) {
            PhotoAvailability.Available -> null
            PhotoAvailability.AccessRemoved -> ViewerUnavailableReason.AccessRemoved
            PhotoAvailability.VolumeOffline -> ViewerUnavailableReason.VolumeOffline
            PhotoAvailability.Pending -> ViewerUnavailableReason.Pending
            PhotoAvailability.Trashed -> ViewerUnavailableReason.Trashed
            PhotoAvailability.Missing -> ViewerUnavailableReason.Missing
        }

sealed interface ViewerUiState {
    data object Idle : ViewerUiState

    data class Loading(val assetId: Long) : ViewerUiState

    data class Ready(
        val evidence: PhotoEvidence,
        val image: DecodedMediaImage,
        val matchedRegionOrdinals: Set<Int>,
    ) : ViewerUiState

    data class Unavailable(
        val assetId: Long,
        val reason: ViewerUnavailableReason,
        val evidence: PhotoEvidence? = null,
    ) : ViewerUiState

    data class Failed(
        val assetId: Long,
        val code: String,
    ) : ViewerUiState
}

private val ViewerUiState.assetId: Long?
    get() =
        when (this) {
            ViewerUiState.Idle -> null
            is ViewerUiState.Loading -> assetId
            is ViewerUiState.Ready -> evidence.item.assetId
            is ViewerUiState.Unavailable -> assetId
            is ViewerUiState.Failed -> assetId
        }

data class SearchResultItem(
    val asset: CatalogItem,
    val hit: UnifiedSearchHit,
)

sealed interface SearchUiState {
    data object Idle : SearchUiState

    data class Searching(
        val query: String,
        val filter: SearchFilter,
        val channels: SearchChannelSelection,
    ) : SearchUiState

    data class Cancelled(val query: String) : SearchUiState

    data class Ready(
        val query: String,
        val filter: SearchFilter,
        val results: List<SearchResultItem>,
        val channels: SearchChannelSelection,
        val semanticStatus: OcrSemanticSearchStatus,
        val visualStatus: VisualTextSearchStatus?,
    ) : SearchUiState

    data class Failed(val code: String) : SearchUiState
}

data class LibraryUiState(
    val items: List<CatalogItem> = emptyList(),
    val totalCount: Long = 0,
    val facets: LibraryFilterFacets = LibraryFilterFacets.Empty,
    val initialLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val errorCode: String? = null,
) {
    val canLoadMore: Boolean
        get() = items.size.toLong() < totalCount
}

data class SimilarResultItem(
    val asset: CatalogItem,
    val hit: VisualSimilarityHit,
)

sealed interface SimilarUiState {
    data object Idle : SimilarUiState

    data class Searching(val sourceAssetId: Long) : SimilarUiState

    data class Ready(
        val sourceAssetId: Long,
        val status: VisualSimilaritySearchStatus,
        val results: List<SimilarResultItem>,
    ) : SimilarUiState

    data class Failed(val sourceAssetId: Long, val code: String) : SimilarUiState
}

data class DuplicateResultItem(
    val asset: CatalogItem,
    val match: PerceptualHashMatch,
)

sealed interface DuplicateUiState {
    data object Idle : DuplicateUiState

    data class Searching(val sourceAssetId: Long) : DuplicateUiState

    data class Ready(
        val sourceAssetId: Long,
        val status: PerceptualHashSearchStatus,
        val results: List<DuplicateResultItem>,
    ) : DuplicateUiState

    data class Failed(val sourceAssetId: Long, val code: String) : DuplicateUiState
}

sealed interface SearchDataResetState {
    data object Idle : SearchDataResetState

    data object Resetting : SearchDataResetState

    data object Succeeded : SearchDataResetState

    data object Failed : SearchDataResetState
}

sealed interface ModelPackRollbackState {
    data object Loading : ModelPackRollbackState

    data class Unavailable(
        val activeVersion: String?,
        val rollbackCompleted: Boolean = false,
    ) : ModelPackRollbackState

    data class Available(
        val activeVersion: String,
        val targetVersion: String,
        val rollbackCompleted: Boolean = false,
    ) : ModelPackRollbackState

    data class RollingBack(val targetVersion: String) : ModelPackRollbackState

    data class Failed(val activeVersion: String, val targetVersion: String) : ModelPackRollbackState
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val runtime: CatalogRuntime,
    private val libraryFeed: LibraryFeed,
    private val modelPacks: ModelPackRuntime,
    private val modelPackActivation: ModelPackActivationRuntime,
    private val ocrIndexing: OcrIndexingRuntime,
    private val unifiedSearch: UnifiedSearch,
    private val visualSimilarity: VisualSimilaritySearch,
    private val perceptualHashes: PerceptualHashSearch,
    private val indexingService: IndexingServiceController,
    private val storage: CatalogStorage,
    private val onboardingStore: OnboardingStore,
    private val thumbnailLoader: ThumbnailLoader,
    private val storageInspector: LocalStorageInspector,
    private val diagnosticsExporter: DiagnosticsExporter,
    private val searchDataResetter: SearchDataResetter,
    private val quarantineGarbageCollector: QuarantineGarbageCollector,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val catalog: StateFlow<CatalogRuntimeState> = runtime.state
    val modelPack: StateFlow<ModelPackRuntimeState> = modelPacks.state
    val indexing: StateFlow<OcrIndexingState> =
        combine(ocrIndexing.state, modelPack, modelPackActivation.state) { active, packs, candidate ->
            if (packs.candidate == null) active else candidate
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ocrIndexing.state.value)
    val onboardingCompleted: StateFlow<Boolean> = onboardingStore.completed
    private val mutableLocalStorage = MutableStateFlow(LocalStorageSummary(0L, 0L))
    val localStorage: StateFlow<LocalStorageSummary> = mutableLocalStorage.asStateFlow()
    private val mutableDiagnosticsExport =
        MutableStateFlow<DiagnosticsExportState>(DiagnosticsExportState.Idle)
    val diagnosticsExport: StateFlow<DiagnosticsExportState> = mutableDiagnosticsExport.asStateFlow()
    private val mutableSearchDataReset =
        MutableStateFlow<SearchDataResetState>(SearchDataResetState.Idle)
    val searchDataReset: StateFlow<SearchDataResetState> = mutableSearchDataReset.asStateFlow()
    private val mutableModelPackRollback =
        MutableStateFlow<ModelPackRollbackState>(ModelPackRollbackState.Loading)
    val modelPackRollback: StateFlow<ModelPackRollbackState> = mutableModelPackRollback.asStateFlow()

    private val mutableViewer = MutableStateFlow<ViewerUiState>(ViewerUiState.Idle)
    val viewer: StateFlow<ViewerUiState> = mutableViewer.asStateFlow()
    private val mutableSearch = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = mutableSearch.asStateFlow()
    private val mutableLibrary = MutableStateFlow(LibraryUiState(initialLoading = true))
    val library: StateFlow<LibraryUiState> = mutableLibrary.asStateFlow()
    private val mutableSearchFilterFacets = MutableStateFlow(SearchFilterFacets(emptyList(), emptyList()))
    val searchFilterFacets: StateFlow<SearchFilterFacets> = mutableSearchFilterFacets.asStateFlow()
    private val mutableSimilar = MutableStateFlow<SimilarUiState>(SimilarUiState.Idle)
    val similar: StateFlow<SimilarUiState> = mutableSimilar.asStateFlow()
    private val mutableDuplicates = MutableStateFlow<DuplicateUiState>(DuplicateUiState.Idle)
    val duplicates: StateFlow<DuplicateUiState> = mutableDuplicates.asStateFlow()
    private val searchGeneration = AtomicLong(0)
    private val libraryGeneration = AtomicLong(0)
    private val viewerGeneration = AtomicLong(0)
    private val similarGeneration = AtomicLong(0)
    private val duplicateGeneration = AtomicLong(0)
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            combine(catalog, modelPack) { catalogState, packState ->
                Triple(catalogState.access.value, packState.installed, packState.candidate)
            }.collectLatest { (_, active, candidate) ->
                ocrIndexing.refresh(active)
                modelPackActivation.refresh(candidate)
            }
        }
        viewModelScope.launch {
            catalog
                .map { state -> state.access.value to state.catalogRevision }
                .distinctUntilChanged()
                .collect { (accessRevision, catalogRevision) ->
                    thumbnailLoader.onCatalogState(accessRevision, catalogRevision)
                    clearDerivedUiState()
                }
        }
        viewModelScope.launch {
            combine(
                catalog.map { state ->
                    Triple(state.status, state.access.value, state.catalogRevision)
                },
                indexing.map { state -> state.scope.revision },
            ) { catalogKey, scopeRevision -> catalogKey to scopeRevision }
                .distinctUntilChanged()
                .collectLatest { (catalogKey, _) ->
                    val (status, _, _) = catalogKey
                    if (status == CatalogRuntimeStatus.Ready) {
                        refreshLibrary()
                    } else {
                        libraryGeneration.incrementAndGet()
                        mutableLibrary.value =
                            LibraryUiState(initialLoading = status == CatalogRuntimeStatus.Reconciling)
                    }
                }
        }
        viewModelScope.launch {
            modelPack
                .map { state -> state.installed?.packVersion to state.candidate?.packVersion }
                .distinctUntilChanged()
                .collect { refreshModelPackRollback() }
        }
        viewModelScope.launch {
            catalog.collectLatest { state ->
                mutableSearchFilterFacets.value =
                    if (state.access.permission.scope == app.nayti.platform.media.MediaAccessScope.None) {
                        SearchFilterFacets(emptyList(), emptyList())
                    } else {
                        storage.catalogDao.searchFilterFacets()
                    }
            }
        }
        viewModelScope.launch {
            catalog
                .map { state -> state.status to state.access.value }
                .distinctUntilChanged()
                .collectLatest { (status, _) ->
                    if (status != CatalogRuntimeStatus.Ready) return@collectLatest
                    try {
                        if (quarantineGarbageCollector.runOnce().purgedAssets > 0) {
                            runtime.refreshAccess()
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Privacy maintenance is retried after the next catalog reconciliation or app start.
                    }
                }
        }
    }

    fun refresh(forceFull: Boolean = false) {
        runtime.refreshAccess(forceFull)
    }

    fun onPermissionResult() {
        runtime.onPermissionResult()
    }

    fun completeOnboarding() = onboardingStore.complete()

    fun setIndexingScopeMonths(months: Long?) {
        require(months == null || months in setOf(1L, 3L, 6L, 12L))
        val takenFromMillis =
            months?.let { value ->
                indexingCutoffMillis(System.currentTimeMillis(), value, ZoneId.systemDefault())
            }
        setIndexingScopeFrom(takenFromMillis)
    }

    fun setIndexingScopeFrom(takenFromMillis: Long?) {
        require(takenFromMillis == null || takenFromMillis in 0..System.currentTimeMillis())
        viewModelScope.launch {
            if (ocrIndexing.setIndexingScope(takenFromMillis)) {
                clearDerivedUiState()
                mutableSearchFilterFacets.value = storage.catalogDao.searchFilterFacets()
                runtime.refreshIndexingScope()
            }
        }
    }

    suspend fun loadThumbnail(key: MediaKey, accessRevision: Long) =
        thumbnailLoader.load(key, accessRevision)

    fun loadMoreLibrary() {
        val current = mutableLibrary.value
        if (current.initialLoading || current.loadingMore || !current.canLoadMore) return
        val generation = libraryGeneration.get()
        val offset = current.items.size
        mutableLibrary.value = current.copy(loadingMore = true, errorCode = null)
        viewModelScope.launch {
            val result = runCatching { libraryFeed.loadPage(offset, LibraryPageSize) }
            if (libraryGeneration.get() != generation) return@launch
            mutableLibrary.value =
                result.fold(
                    onSuccess = { page ->
                        val existingIds = current.items.asSequence().map(CatalogItem::assetId).toHashSet()
                        current.copy(
                            items = current.items + page.items.filterNot { it.assetId in existingIds },
                            totalCount = page.totalCount,
                            facets = page.facets,
                            loadingMore = false,
                        )
                    },
                    onFailure = { failure ->
                        if (failure is CancellationException) throw failure
                        current.copy(loadingMore = false, errorCode = failure::class.java.simpleName.uppercase())
                    },
                )
        }
    }

    fun retryLibrary() = refreshLibrary()

    fun refreshLocalStorage() {
        val modelBytes = modelPack.value.installed?.payloadBytes ?: 0L
        viewModelScope.launch {
            mutableLocalStorage.value = storageInspector.measure(modelBytes)
        }
    }

    fun exportDiagnostics(destination: Uri) {
        if (mutableDiagnosticsExport.value == DiagnosticsExportState.Writing) return
        mutableDiagnosticsExport.value = DiagnosticsExportState.Writing
        viewModelScope.launch {
            mutableDiagnosticsExport.value =
                try {
                    diagnosticsExporter.export(
                        destination = destination,
                        snapshot = DiagnosticsSnapshot(
                            appVersion = BuildConfig.VERSION_NAME,
                            sdkInt = Build.VERSION.SDK_INT,
                            device = "${Build.MANUFACTURER} ${Build.MODEL}",
                            catalogStatus = catalog.value.status.name,
                            accessScope = catalog.value.access.permission.scope.name,
                            catalogTotal = catalog.value.summary.total,
                            catalogAvailable = catalog.value.summary.available,
                            modelPackStatus = modelPack.value.status.name,
                            activeModelPackVersion = modelPack.value.installed?.packVersion,
                            candidateModelPackVersion = modelPack.value.candidate?.packVersion,
                            preparationStatus = indexing.value.status.name,
                            preparationErrorCode = indexing.value.errorCode,
                            capabilities = indexing.value.capabilities,
                            storage = localStorage.value,
                            indexingScopeMode = indexing.value.scope.mode,
                            indexingScopeTakenFromMillis = indexing.value.scope.takenFromMillis,
                            indexingScopeRevision = indexing.value.scope.revision,
                            indexingScopeEligibleAssets = indexing.value.scope.eligibleAssets,
                            preparationActiveDurationMillis = indexing.value.activeDurationMillis,
                            estimatedAllMediaDurationMillis = indexing.value.estimatedAllMediaDurationMillis,
                        ),
                    )
                    DiagnosticsExportState.Saved
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    DiagnosticsExportState.Failed
                }
        }
    }

    fun resetSearchData() {
        if (mutableSearchDataReset.value == SearchDataResetState.Resetting) return
        mutableSearchDataReset.value = SearchDataResetState.Resetting
        modelPackActivation.requestStop()
        viewModelScope.launch {
            mutableSearchDataReset.value =
                try {
                    ocrIndexing.cancel()
                    searchDataResetter.reset()
                    clearDerivedUiState()
                    ocrIndexing.refresh(modelPack.value.installed)
                    mutableLocalStorage.value =
                        storageInspector.measure(modelPack.value.installed?.payloadBytes ?: 0L)
                    SearchDataResetState.Succeeded
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    SearchDataResetState.Failed
                }
        }
    }

    fun rollbackModelPack() {
        val current = mutableModelPackRollback.value
        val targetVersion =
            when (current) {
                is ModelPackRollbackState.Available -> current.targetVersion
                is ModelPackRollbackState.Failed -> current.targetVersion
                else -> return
            }
        mutableModelPackRollback.value = ModelPackRollbackState.RollingBack(targetVersion)
        modelPackActivation.requestStop()
        viewModelScope.launch {
            try {
                ocrIndexing.cancel()
                val pointer = checkNotNull(modelPackActivation.rollback())
                val activeSnapshot = checkNotNull(
                    pointer.snapshotId?.let { snapshotId -> storage.vectorIndexDao.snapshot(snapshotId) },
                )
                val activePack = checkNotNull(
                    storage.modelPackDao.pack(activeSnapshot.packId, activeSnapshot.packVersion),
                )
                modelPacks.refresh()
                ocrIndexing.refresh(activePack)
                mutableModelPackRollback.value = loadModelPackRollback(rollbackCompleted = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val activeVersion = modelPack.value.installed?.packVersion ?: "—"
                mutableModelPackRollback.value =
                    ModelPackRollbackState.Failed(activeVersion, targetVersion)
            }
        }
    }

    private suspend fun refreshModelPackRollback() {
        if (mutableModelPackRollback.value is ModelPackRollbackState.RollingBack) return
        mutableModelPackRollback.value = loadModelPackRollback()
    }

    private suspend fun loadModelPackRollback(rollbackCompleted: Boolean = false): ModelPackRollbackState {
        val pointer = storage.vectorIndexDao.activePointer()
        val active = pointer?.snapshotId?.let { snapshotId -> storage.vectorIndexDao.snapshot(snapshotId) }
        val rollback = pointer?.rollbackSnapshotId?.let { snapshotId -> storage.vectorIndexDao.snapshot(snapshotId) }
        return if (active != null && rollback != null) {
            ModelPackRollbackState.Available(
                activeVersion = active.packVersion,
                targetVersion = rollback.packVersion,
                rollbackCompleted = rollbackCompleted,
            )
        } else {
            ModelPackRollbackState.Unavailable(
                activeVersion = active?.packVersion ?: modelPack.value.installed?.packVersion,
                rollbackCompleted = rollbackCompleted,
            )
        }
    }

    fun acknowledgeSearchDataReset() {
        if (mutableSearchDataReset.value != SearchDataResetState.Resetting) {
            mutableSearchDataReset.value = SearchDataResetState.Idle
        }
    }

    private fun clearDerivedUiState() {
        searchJob?.cancel()
        searchJob = null
        searchGeneration.incrementAndGet()
        similarGeneration.incrementAndGet()
        duplicateGeneration.incrementAndGet()
        viewerGeneration.incrementAndGet()
        mutableSearch.value = SearchUiState.Idle
        mutableSimilar.value = SimilarUiState.Idle
        mutableDuplicates.value = DuplicateUiState.Idle
        replaceViewerState(ViewerUiState.Idle)
    }

    fun openViewer(assetId: Long) {
        val accessPin = catalog.value.access
        val generation = viewerGeneration.incrementAndGet()
        replaceViewerState(ViewerUiState.Loading(assetId))
        viewModelScope.launch {
            val result =
                try {
                    val evidence = libraryFeed.photoEvidence(assetId)
                        ?: return@launch publishViewerResult(
                            generation,
                            ViewerUiState.Unavailable(assetId, ViewerUnavailableReason.Missing),
                        )
                    val unavailableReason = evidence.availability.unavailableReason
                    if (unavailableReason != null) {
                        ViewerUiState.Unavailable(assetId, unavailableReason, evidence)
                    } else {
                        val image = runtime.decode(assetId, accessPin)
                        val matched =
                            (search.value as? SearchUiState.Ready)?.results
                                ?.firstOrNull { result -> result.asset.assetId == assetId }
                                ?.hit?.matchedRegionOrdinals.orEmpty().toSet()
                        ViewerUiState.Ready(evidence, image, matched)
                    }
                } catch (_: MediaDecodeAccessException) {
                    ViewerUiState.Unavailable(assetId, ViewerUnavailableReason.AccessChanged)
                } catch (_: MediaDecodeContentException) {
                    ViewerUiState.Unavailable(assetId, ViewerUnavailableReason.CannotDecode)
                } catch (_: MediaDecodeIoException) {
                    ViewerUiState.Unavailable(assetId, ViewerUnavailableReason.TemporarilyUnavailable)
                } catch (_: SecurityException) {
                    ViewerUiState.Unavailable(assetId, ViewerUnavailableReason.AccessChanged)
                } catch (_: IllegalStateException) {
                    ViewerUiState.Unavailable(assetId, ViewerUnavailableReason.AccessChanged)
                } catch (failure: Exception) {
                    ViewerUiState.Failed(assetId, failure::class.java.simpleName.uppercase())
                }
            publishViewerResult(generation, result)
        }
    }

    fun closeViewer(assetId: Long) {
        if (mutableViewer.value.assetId == assetId) {
            viewerGeneration.incrementAndGet()
            replaceViewerState(ViewerUiState.Idle)
        }
    }

    override fun onCleared() {
        replaceViewerState(ViewerUiState.Idle)
    }

    private fun publishViewerResult(generation: Long, result: ViewerUiState) {
        if (viewerGeneration.get() == generation) {
            replaceViewerState(result)
        } else {
            (result as? ViewerUiState.Ready)?.image?.close()
        }
    }

    private fun replaceViewerState(next: ViewerUiState) {
        val previous = mutableViewer.value
        mutableViewer.value = next
        if (previous !== next) {
            (previous as? ViewerUiState.Ready)?.image?.close()
        }
    }

    fun importModelPack(uri: Uri) {
        modelPacks.install(SafModelPackSource(context.contentResolver, uri))
    }

    fun startIndexing(): Boolean {
        if (!indexingService.notificationsGranted) return false
        return indexingService.start()
    }

    fun pauseIndexing() = indexingService.pause()

    fun stopIndexingForNow() = indexingService.stopForNow()

    fun cancelIndexing() = indexingService.cancel()

    fun retryIndexingGaps() {
        viewModelScope.launch {
            if (ocrIndexing.retryPermanentGaps()) indexingService.start()
        }
    }

    fun search(
        query: String,
        filter: SearchFilter = SearchFilter.None,
        channels: SearchChannelSelection = SearchChannelSelection.All,
    ) {
        val normalizedQuery = query.trim()
        searchJob?.cancel()
        searchJob = null
        if (normalizedQuery.isEmpty()) {
            searchGeneration.incrementAndGet()
            mutableSearch.value = SearchUiState.Idle
            return
        }
        val generation = searchGeneration.incrementAndGet()
        val pack = modelPack.value.installed
        if (modelPack.value.status == ModelPackRuntimeStatus.Installing || pack == null) {
            mutableSearch.value = SearchUiState.Failed("MODEL_PACK_REQUIRED")
            return
        }
        mutableSearch.value = SearchUiState.Searching(normalizedQuery, filter, channels)
        searchJob = viewModelScope.launch {
            val result =
                try {
                    val searchResult =
                        unifiedSearch.search(
                            query = normalizedQuery,
                            pipelineVersion = OcrIndexingRuntime.PipelineVersion,
                            fallbackComponentHash = pack.manifestSha256,
                            filter = filter,
                            channels = channels,
                        )
                    val hydrated = searchResult.hits.mapNotNull { hit ->
                        libraryFeed.item(hit.assetId)?.let { asset -> SearchResultItem(asset, hit) }
                    }
                    SearchUiState.Ready(
                        normalizedQuery,
                        filter,
                        hydrated,
                        searchResult.channels,
                        searchResult.semanticStatus,
                        searchResult.visualStatus,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    SearchUiState.Failed(failure::class.java.simpleName.uppercase())
                }
            if (searchGeneration.get() == generation) mutableSearch.value = result
        }
    }

    fun cancelSearch() {
        val query = when (val state = mutableSearch.value) {
            is SearchUiState.Searching -> state.query
            is SearchUiState.Ready -> state.query
            is SearchUiState.Cancelled -> state.query
            else -> ""
        }
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        mutableSearch.value = if (query.isBlank()) SearchUiState.Idle else SearchUiState.Cancelled(query)
    }

    fun findSimilar(sourceAssetId: Long) {
        val generation = similarGeneration.incrementAndGet()
        mutableSimilar.value = SimilarUiState.Searching(sourceAssetId)
        viewModelScope.launch {
            val result =
                try {
                    val searchResult = visualSimilarity.searchSimilar(sourceAssetId)
                    val hydrated = searchResult.hits.mapNotNull { hit ->
                        libraryFeed.item(hit.assetId)?.let { asset -> SimilarResultItem(asset, hit) }
                    }
                    SimilarUiState.Ready(sourceAssetId, searchResult.status, hydrated)
                } catch (failure: Exception) {
                    SimilarUiState.Failed(sourceAssetId, failure::class.java.simpleName.uppercase())
                }
            if (similarGeneration.get() == generation) mutableSimilar.value = result
        }
    }

    fun findDuplicates(sourceAssetId: Long) {
        val generation = duplicateGeneration.incrementAndGet()
        mutableDuplicates.value = DuplicateUiState.Searching(sourceAssetId)
        viewModelScope.launch {
            val result =
                try {
                    val searchResult = perceptualHashes.nearDuplicates(sourceAssetId)
                    val hydrated = searchResult.hits.mapNotNull { match ->
                        libraryFeed.item(match.assetId)?.let { asset -> DuplicateResultItem(asset, match) }
                    }
                    DuplicateUiState.Ready(sourceAssetId, searchResult.status, hydrated)
                } catch (failure: Exception) {
                    DuplicateUiState.Failed(sourceAssetId, failure::class.java.simpleName.uppercase())
                }
            if (duplicateGeneration.get() == generation) mutableDuplicates.value = result
        }
    }

    private fun refreshLibrary() {
        val generation = libraryGeneration.incrementAndGet()
        mutableLibrary.value = LibraryUiState(initialLoading = true)
        viewModelScope.launch {
            val result = runCatching { libraryFeed.loadPage(0, LibraryPageSize) }
            if (libraryGeneration.get() != generation) return@launch
            mutableLibrary.value =
                result.fold(
                    onSuccess = { page ->
                        LibraryUiState(
                            items = page.items,
                            totalCount = page.totalCount,
                            facets = page.facets,
                        )
                    },
                    onFailure = { failure ->
                        if (failure is CancellationException) throw failure
                        LibraryUiState(errorCode = failure::class.java.simpleName.uppercase())
                    },
                )
        }
    }

    private companion object {
        const val LibraryPageSize = 120
    }
}

internal fun indexingCutoffMillis(
    nowMillis: Long,
    months: Long,
    zoneId: ZoneId,
): Long {
    require(nowMillis >= 0)
    require(months > 0)
    return Instant.ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .minusMonths(months)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}
