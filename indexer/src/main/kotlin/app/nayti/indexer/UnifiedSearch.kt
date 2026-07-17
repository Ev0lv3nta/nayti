package app.nayti.indexer

import app.nayti.search.engine.fusion.MultimodalPrimaryChannel
import app.nayti.search.engine.fusion.MultimodalQueryIntent
import app.nayti.search.engine.fusion.MultimodalQueryPlanner
import app.nayti.search.engine.fusion.MultimodalTextCandidate
import app.nayti.search.engine.fusion.MultimodalVisualCandidate
import app.nayti.search.engine.fusion.StrictMultimodalFusion
import app.nayti.search.engine.fusion.TextFusionReason
import app.nayti.storage.VectorIndexDao

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
    val visualSimilarityMicros: Int?,
)

data class UnifiedSearchResult(
    val intent: MultimodalQueryIntent,
    val semanticStatus: OcrSemanticSearchStatus,
    val visualStatus: VisualTextSearchStatus?,
    val hits: List<UnifiedSearchHit>,
)

/** Runs intent-selected retrievers and accepts results only across one stable snapshot/access observation. */
class UnifiedSearch(
    private val vectors: VectorIndexDao,
    private val text: OcrHybridSearch,
    private val visual: VisualTextSearch,
    private val planner: MultimodalQueryPlanner = MultimodalQueryPlanner(),
) {
    suspend fun search(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int = DefaultLimit,
    ): UnifiedSearchResult {
        require(limit in 1..MaximumResultLimit)
        val plan = planner.plan(query)
        var consistencyFailure: QueryConsistencyChangedException? = null
        repeat(MaximumConsistencyAttempts) {
            try {
                return searchOnce(query, pipelineVersion, fallbackComponentHash, limit, plan.intent, plan.usesVisualRetriever)
            } catch (failure: QueryConsistencyChangedException) {
                consistencyFailure = failure
            }
        }
        throw checkNotNull(consistencyFailure)
    }

    private suspend fun searchOnce(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int,
        intent: MultimodalQueryIntent,
        usesVisualRetriever: Boolean,
    ): UnifiedSearchResult {
        val before = captureState()
        val textResult =
            text.search(
                query = query,
                pipelineVersion = pipelineVersion,
                fallbackComponentHash = fallbackComponentHash,
                limit = MaximumRetrieverCandidates,
            )
        val visualResult =
            if (usesVisualRetriever) {
                visual.search(query, MaximumRetrieverCandidates)
            } else {
                null
            }
        val after = captureState()
        if (
            before != after ||
            (visualResult?.status == VisualTextSearchStatus.READY && visualResult.snapshotId != before.snapshotId)
        ) {
            throw QueryConsistencyChangedException()
        }

        val textByAsset = textResult.hits.associateBy(HybridOcrHit::assetId)
        val visualByAsset = visualResult?.hits.orEmpty().associateBy(VisualSimilarityHit::assetId)
        val fused =
            StrictMultimodalFusion.rank(
                intent = intent,
                text =
                    textResult.hits.map { hit ->
                        MultimodalTextCandidate(hit.assetId, hit.rank, hit.tier)
                    },
                visual =
                    visualResult?.hits.orEmpty().map { hit ->
                        MultimodalVisualCandidate(hit.assetId, hit.rank)
                    },
                limit = limit,
            )
        return UnifiedSearchResult(
            intent = intent,
            semanticStatus = textResult.semanticStatus,
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
                        visualSimilarityMicros = visualHit?.similarityMicros,
                    )
                },
        )
    }

    private suspend fun captureState(): QueryConsistencyState {
        val access = vectors.accessObservation()
        return QueryConsistencyState(
            snapshotId = vectors.activeSnapshotId(),
            accessScope = access?.accessScope,
            accessRevision = access?.processAccessRevision,
        )
    }

    private fun TextFusionReason.toUnifiedReason(): UnifiedSearchReason =
        UnifiedSearchReason.valueOf(name)

    private data class QueryConsistencyState(
        val snapshotId: String?,
        val accessScope: String?,
        val accessRevision: Long?,
    )

    private class QueryConsistencyChangedException : Exception()

    companion object {
        const val DefaultLimit = 50
        const val MaximumResultLimit = 100
        private const val MaximumRetrieverCandidates = 100
        private const val MaximumConsistencyAttempts = 2
    }
}
