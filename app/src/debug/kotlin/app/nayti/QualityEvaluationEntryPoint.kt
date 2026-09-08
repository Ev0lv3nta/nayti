package app.nayti

import app.nayti.indexer.CatalogRuntime
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.PerceptualHashSearch
import app.nayti.indexer.UnifiedSearch
import app.nayti.indexing.IndexingServiceController
import app.nayti.storage.CatalogStorage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Access to the actual graph, only in the debug artifact used by the opt-in corpus test. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QualityEvaluationEntryPoint {
    fun catalog(): CatalogRuntime
    fun packs(): ModelPackRuntime
    fun indexing(): OcrIndexingRuntime
    fun controller(): IndexingServiceController
    fun storage(): CatalogStorage
    fun search(): UnifiedSearch
    fun duplicates(): PerceptualHashSearch
}
