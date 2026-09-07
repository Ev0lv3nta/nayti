package app.nayti.indexer

import app.nayti.search.engine.fusion.MultimodalPrimaryChannel
import app.nayti.search.engine.fusion.MultimodalQueryIntent
import app.nayti.search.engine.fusion.MultimodalQueryPlanner
import app.nayti.search.engine.fusion.MultimodalTextCandidate
import app.nayti.search.engine.fusion.MultimodalVisualCandidate
import app.nayti.search.engine.fusion.StrictMultimodalFusion
import app.nayti.search.engine.fusion.TextFusionReason
import app.nayti.storage.QuerySnapshotLeaseEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.CatalogDao
import app.nayti.storage.VectorIndexDao
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class UnifiedSearchReason {
    EXACT_IDENTIFIER,
    QUOTED_PHRASE,
    PERSON_NAME,
    LITERAL_TEXT,
    FUZZY_TEXT,
    SEMANTIC_TEXT,
    VISUAL_CONTENT,
}

data class UnifiedSearchHit(
    val assetId: Long,
    val rank: Int,
    val tier: Int,
    val reason: UnifiedSearchReason,
    val displaySnippet: String?,
    val matchedRegionOrdinals: List<Int>,
    val lexicalRank: Int?,
    val semanticRank: Int?,
    val visualRank: Int?,
    val visualScaledDotMicros: Int?,
)

data class UnifiedSearchResult(
    val intent: MultimodalQueryIntent,
    val channels: SearchChannelSelection,
    val snapshotId: String?,
    val accessRevision: Long?,
    val semanticGenerationId: String?,
    val visualGenerationId: String?,
    val semanticStatus: OcrSemanticSearchStatus,
    val visualStatus: VisualTextSearchStatus?,
    val hits: List<UnifiedSearchHit>,
)

/** Runs automatic or explicitly selected retrievers against one stable snapshot/access observation. */
class UnifiedSearch(
    private val vectors: VectorIndexDao,
    private val text: OcrHybridSearch,
    private val visual: VisualTextSearch,
    private val planner: MultimodalQueryPlanner = MultimodalQueryPlanner(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseTokens: () -> String = { "unified-query-${UUID.randomUUID()}" },
    private val catalog: CatalogDao? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun search(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int = DefaultLimit,
        filter: SearchFilter = SearchFilter.None,
        channels: SearchChannelSelection? = null,
    ): UnifiedSearchResult = withContext(dispatcher) {
        searchOnWorker(query, pipelineVersion, fallbackComponentHash, limit, filter, channels)
    }

    // Leased retrievers inherit this worker context, including synchronous native inference.
    private suspend fun searchOnWorker(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int,
        filter: SearchFilter,
        channels: SearchChannelSelection?,
    ): UnifiedSearchResult {
        require(limit in 1..MaximumResultLimit)
        val plan = planner.plan(query)
        val effectiveChannels = resolveUnifiedSearchChannels(plan.intent, channels)
        var consistencyFailure: QueryConsistencyChangedException? = null
        repeat(MaximumConsistencyAttempts) {
            currentCoroutineContext().ensureActive()
            val scope = catalog?.currentIndexingScope()
            val effectiveFilter = filter.constrainedFrom(scope?.takenFromMillis)
            val acquiredAt = clock()
            val lease =
                vectors.acquireCurrentSnapshotLease(
                    leaseToken = leaseTokens(),
                    nowMillis = acquiredAt,
                    expiresAtMillis = Math.addExact(acquiredAt, LeaseDurationMillis),
                )
            if (lease == null) {
                try {
                    return searchWithoutSnapshot(
                        query,
                        pipelineVersion,
                        fallbackComponentHash,
                        limit,
                        plan.intent,
                        effectiveFilter,
                        scope?.revision,
                        effectiveChannels,
                    )
                } catch (failure: QueryConsistencyChangedException) {
                    consistencyFailure = failure
                    return@repeat
                }
            }
            try {
                return searchLeased(
                    query,
                    pipelineVersion,
                    limit,
                    plan.intent,
                    lease,
                    effectiveFilter,
                    scope?.revision,
                    effectiveChannels,
                )
            } catch (failure: QueryConsistencyChangedException) {
                consistencyFailure = failure
            } finally {
                withContext(NonCancellable) { vectors.releaseQueryLease(lease.leaseToken) }
            }
        }
        throw checkNotNull(consistencyFailure)
    }

    private suspend fun searchLeased(
        query: String,
        pipelineVersion: String,
        limit: Int,
        intent: MultimodalQueryIntent,
        lease: QuerySnapshotLeaseEntity,
        filter: SearchFilter,
        scopeRevision: Long?,
        channels: SearchChannelSelection,
    ): UnifiedSearchResult {
        val textResult =
            if (channels.usesText) {
                text.searchLeased(
                    query = query,
                    pipelineVersion = pipelineVersion,
                    limit = MaximumRetrieverCandidates,
                    lease = lease,
                    filter = filter,
                    channels = channels,
                )
            } else {
                null
            }
        currentCoroutineContext().ensureActive()
        val visualResult =
            if (channels.visual) {
                visual.searchLeased(query, MaximumRetrieverCandidates, lease, filter)
            } else {
                null
            }
        if (visualResult?.status == VisualTextSearchStatus.READY && visualResult.snapshotId != lease.snapshotId) {
            throw QueryConsistencyChangedException()
        }
        val access = vectors.accessObservation()
        if (access?.accessScope == "None" || access?.processAccessRevision != lease.accessRevision) {
            throw QueryConsistencyChangedException()
        }
        if (scopeRevision != null && catalog?.currentIndexingScope()?.revision != scopeRevision) {
            throw QueryConsistencyChangedException()
        }

        return fuse(
            intent = intent,
            snapshotId = lease.snapshotId,
            accessRevision = lease.accessRevision,
            semanticGenerationId =
                if (channels.ocrSemantic) {
                    vectors.snapshotChannel(lease.snapshotId, IndexChannel.OCR_SEMANTIC)?.generationId
                } else {
                    null
                },
            visualGenerationId =
                if (channels.visual) {
                    vectors.snapshotChannel(lease.snapshotId, IndexChannel.VISUAL)?.generationId
                } else {
                    null
                },
            textResult = textResult,
            visualResult = visualResult,
            limit = limit,
            channels = channels,
        )
    }

    private suspend fun searchWithoutSnapshot(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int,
        intent: MultimodalQueryIntent,
        filter: SearchFilter,
        scopeRevision: Long?,
        channels: SearchChannelSelection,
    ): UnifiedSearchResult {
        val before = captureState(scopeRevision)
        val textResult =
            if (channels.usesText) {
                text.search(
                    query,
                    pipelineVersion,
                    fallbackComponentHash,
                    MaximumRetrieverCandidates,
                    filter,
                    channels,
                )
            } else {
                null
            }
        val visualResult =
            if (channels.visual) {
                visual.search(query, MaximumRetrieverCandidates, filter)
            } else {
                null
            }
        val after = captureState(scopeRevision)
        if (before != after) throw QueryConsistencyChangedException()
        return fuse(
            intent,
            before.snapshotId,
            before.accessRevision,
            null,
            null,
            textResult,
            visualResult,
            limit,
            channels,
        )
    }

    private fun fuse(
        intent: MultimodalQueryIntent,
        snapshotId: String?,
        accessRevision: Long?,
        semanticGenerationId: String?,
        visualGenerationId: String?,
        textResult: OcrHybridSearchResult?,
        visualResult: VisualTextSearchResult?,
        limit: Int,
        channels: SearchChannelSelection,
    ): UnifiedSearchResult {
        val textByAsset = textResult?.hits.orEmpty().associateBy(HybridOcrHit::assetId)
        val visualByAsset = visualResult?.hits.orEmpty().associateBy(VisualSimilarityHit::assetId)
        val fused =
            StrictMultimodalFusion.rank(
                intent = intent,
                text =
                    textResult?.hits.orEmpty().map { hit ->
                        MultimodalTextCandidate(hit.assetId, hit.rank, hit.tier)
                    },
                visual =
                    visualResult?.hits.orEmpty().map { hit ->
                        MultimodalVisualCandidate(hit.assetId, hit.rank)
                    },
                limit = limit,
                allowVisualFallback = channels.visual,
            )
        return UnifiedSearchResult(
            intent = intent,
            channels = channels,
            snapshotId = snapshotId,
            accessRevision = accessRevision,
            semanticGenerationId = semanticGenerationId,
            visualGenerationId = visualGenerationId,
            semanticStatus = textResult?.semanticStatus ?: OcrSemanticSearchStatus.NOT_REQUESTED,
            visualStatus = visualResult?.status,
            hits =
                fused.mapIndexed { index, candidate ->
                    val textHit = textByAsset[candidate.assetId]
                    val visualHit = visualByAsset[candidate.assetId]
                    UnifiedSearchHit(
                        assetId = candidate.assetId,
                        rank = index + 1,
                        tier = candidate.tier,
                        reason =
                            if (candidate.primaryChannel == MultimodalPrimaryChannel.VISUAL) {
                                UnifiedSearchReason.VISUAL_CONTENT
                            } else {
                                checkNotNull(textHit).evidence.toUnifiedReason()
                            },
                        displaySnippet = textHit?.displaySnippet,
                        matchedRegionOrdinals = textHit?.matchedRegionOrdinals.orEmpty(),
                        lexicalRank = textHit?.lexicalRank,
                        semanticRank = textHit?.semanticRank,
                        visualRank = visualHit?.rank,
                        visualScaledDotMicros = visualHit?.scaledDotMicros,
                    )
                },
        )
    }

    private suspend fun captureState(expectedScopeRevision: Long?): QueryConsistencyState {
        val access = vectors.accessObservation()
        val actualScopeRevision = catalog?.currentIndexingScope()?.revision
        if (expectedScopeRevision != null && actualScopeRevision != expectedScopeRevision) {
            throw QueryConsistencyChangedException()
        }
        return QueryConsistencyState(
            snapshotId = vectors.activeSnapshotId(),
            accessScope = access?.accessScope,
            accessRevision = access?.processAccessRevision,
            scopeRevision = actualScopeRevision,
        )
    }

    private fun TextFusionReason.toUnifiedReason(): UnifiedSearchReason =
        UnifiedSearchReason.valueOf(name)

    private data class QueryConsistencyState(
        val snapshotId: String?,
        val accessScope: String?,
        val accessRevision: Long?,
        val scopeRevision: Long?,
    )

    private class QueryConsistencyChangedException : Exception()

    companion object {
        const val DefaultLimit = 50
        const val MaximumResultLimit = 100
        private const val MaximumRetrieverCandidates = 100
        private const val MaximumConsistencyAttempts = 2
        private const val LeaseDurationMillis = 5 * 60 * 1_000L
    }
}
