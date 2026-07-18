package app.nayti.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nayti.indexing.IndexingServiceController
import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrSemanticSearchStatus
import app.nayti.indexer.PerceptualHashSearch
import app.nayti.indexer.PerceptualHashSearchStatus
import app.nayti.indexer.UnifiedSearch
import app.nayti.indexer.UnifiedSearchHit
import app.nayti.indexer.VisualSimilarityHit
import app.nayti.indexer.VisualSimilaritySearch
import app.nayti.indexer.VisualSimilaritySearchStatus
import app.nayti.indexer.VisualTextSearchStatus
import app.nayti.platform.media.DecodedMediaImage
import app.nayti.ml.runtime.pack.SafModelPackSource
import app.nayti.storage.CatalogAssetEntity
import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import app.nayti.storage.OcrRegionEntity
import app.nayti.search.engine.similarity.PerceptualHashMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface ViewerProbeState {
    data object Idle : ViewerProbeState

    data object Loading : ViewerProbeState

    data class Ready(
        val image: DecodedMediaImage,
        val regions: List<OcrRegionEntity>,
        val matchedRegionOrdinals: Set<Int>,
    ) : ViewerProbeState

    data class Failed(val code: String) : ViewerProbeState
}

data class SearchResultItem(
    val asset: CatalogAssetEntity,
    val hit: UnifiedSearchHit,
)

sealed interface SearchUiState {
    data object Idle : SearchUiState

    data object Searching : SearchUiState

    data class Ready(
        val query: String,
        val results: List<SearchResultItem>,
        val semanticStatus: OcrSemanticSearchStatus,
        val visualStatus: VisualTextSearchStatus?,
    ) : SearchUiState

    data class Failed(val code: String) : SearchUiState
}

data class SimilarResultItem(
    val asset: CatalogAssetEntity,
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
    val asset: CatalogAssetEntity,
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

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val runtime: CatalogRuntime,
    private val modelPacks: ModelPackRuntime,
    private val ocrIndexing: OcrIndexingRuntime,
    private val unifiedSearch: UnifiedSearch,
    private val visualSimilarity: VisualSimilaritySearch,
    private val perceptualHashes: PerceptualHashSearch,
    private val indexingService: IndexingServiceController,
    private val storage: CatalogStorage,
    private val onboardingStore: OnboardingStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val catalog: StateFlow<CatalogRuntimeState> = runtime.state
    val modelPack: StateFlow<ModelPackRuntimeState> = modelPacks.state
    val indexing: StateFlow<OcrIndexingState> = ocrIndexing.state
    val onboardingCompleted: StateFlow<Boolean> = onboardingStore.completed

    private val mutableViewerProbe = MutableStateFlow<ViewerProbeState>(ViewerProbeState.Idle)
    val viewerProbe: StateFlow<ViewerProbeState> = mutableViewerProbe.asStateFlow()
    private val mutableSearch = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = mutableSearch.asStateFlow()
    private val mutableSimilar = MutableStateFlow<SimilarUiState>(SimilarUiState.Idle)
    val similar: StateFlow<SimilarUiState> = mutableSimilar.asStateFlow()
    private val mutableDuplicates = MutableStateFlow<DuplicateUiState>(DuplicateUiState.Idle)
    val duplicates: StateFlow<DuplicateUiState> = mutableDuplicates.asStateFlow()
    private val searchGeneration = AtomicLong(0)
    private val viewerGeneration = AtomicLong(0)
    private val similarGeneration = AtomicLong(0)
    private val duplicateGeneration = AtomicLong(0)

    init {
        viewModelScope.launch {
            combine(catalog, modelPack) { catalogState, packState ->
                catalogState.access.value to packState.installed
            }.collectLatest { (_, pack) -> ocrIndexing.refresh(pack) }
        }
    }

    fun refresh(forceFull: Boolean = false) {
        runtime.refreshAccess(forceFull)
    }

    fun onPermissionResult() {
        runtime.onPermissionResult()
    }

    fun completeOnboarding() = onboardingStore.complete()

    fun probe(assetId: Long) {
        val accessPin = catalog.value.access
        val generation = viewerGeneration.incrementAndGet()
        mutableViewerProbe.value = ViewerProbeState.Loading
        viewModelScope.launch {
            val result =
                try {
                    val image = runtime.decode(assetId, accessPin)
                    try {
                        val activeSnapshotId = storage.vectorIndexDao.activeSnapshotId()
                        val ocrComponent = activeSnapshotId?.let { snapshotId ->
                            storage.vectorIndexDao.snapshotChannel(snapshotId, IndexChannel.OCR)
                        }
                        val evidence =
                            if (ocrComponent == null) {
                                null
                            } else {
                                storage.ocrDao.eligibleAsset(
                                    assetId,
                                    ocrComponent.pipelineVersion,
                                    ocrComponent.componentHash,
                                )
                            }
                        val matched =
                            (search.value as? SearchUiState.Ready)?.results
                                ?.firstOrNull { result -> result.asset.assetId == assetId }
                                ?.hit?.matchedRegionOrdinals.orEmpty().toSet()
                        ViewerProbeState.Ready(image, evidence?.regions.orEmpty(), matched)
                    } catch (failure: Exception) {
                        image.close()
                        throw failure
                    }
                } catch (failure: Exception) {
                    ViewerProbeState.Failed(failure::class.java.simpleName.uppercase())
                }
            if (viewerGeneration.get() == generation) {
                mutableViewerProbe.value = result
            } else {
                (result as? ViewerProbeState.Ready)?.image?.close()
            }
        }
    }

    fun clearProbe() {
        viewerGeneration.incrementAndGet()
        mutableViewerProbe.value = ViewerProbeState.Idle
    }

    override fun onCleared() {
        closeViewerImage()
    }

    private fun closeViewerImage() {
        (mutableViewerProbe.value as? ViewerProbeState.Ready)?.image?.close()
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

    fun search(query: String) {
        val normalizedQuery = query.trim()
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
        mutableSearch.value = SearchUiState.Searching
        viewModelScope.launch {
            val result =
                try {
                    val searchResult =
                        unifiedSearch.search(
                            query = normalizedQuery,
                            pipelineVersion = OcrIndexingRuntime.PipelineVersion,
                            fallbackComponentHash = pack.manifestSha256,
                        )
                    val hydrated = searchResult.hits.mapNotNull { hit ->
                        storage.catalogDao.asset(hit.assetId)?.let { asset -> SearchResultItem(asset, hit) }
                    }
                    SearchUiState.Ready(
                        normalizedQuery,
                        hydrated,
                        searchResult.semanticStatus,
                        searchResult.visualStatus,
                    )
                } catch (failure: Exception) {
                    SearchUiState.Failed(failure::class.java.simpleName.uppercase())
                }
            if (searchGeneration.get() == generation) mutableSearch.value = result
        }
    }

    fun findSimilar(sourceAssetId: Long) {
        val generation = similarGeneration.incrementAndGet()
        mutableSimilar.value = SimilarUiState.Searching(sourceAssetId)
        viewModelScope.launch {
            val result =
                try {
                    val searchResult = visualSimilarity.searchSimilar(sourceAssetId)
                    val hydrated = searchResult.hits.mapNotNull { hit ->
                        storage.catalogDao.asset(hit.assetId)?.let { asset -> SimilarResultItem(asset, hit) }
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
                        storage.catalogDao.asset(match.assetId)?.let { asset -> DuplicateResultItem(asset, match) }
                    }
                    DuplicateUiState.Ready(sourceAssetId, searchResult.status, hydrated)
                } catch (failure: Exception) {
                    DuplicateUiState.Failed(sourceAssetId, failure::class.java.simpleName.uppercase())
                }
            if (duplicateGeneration.get() == generation) mutableDuplicates.value = result
        }
    }
}
