package app.nayti.indexer

import app.nayti.search.engine.fusion.MultimodalPrimaryChannel
import app.nayti.search.engine.fusion.MultimodalQueryIntent
import app.nayti.search.engine.fusion.MultimodalQueryPlanner
import app.nayti.search.engine.fusion.MultimodalTextCandidate
import app.nayti.search.engine.fusion.MultimodalVisualCandidate
import app.nayti.search.engine.fusion.StrictMultimodalFusion
import app.nayti.search.engine.fusion.TextFusionReason
import app.nayti.storage.QuerySnapshotLeaseEntity
import app.nayti.storage.VectorIndexDao
import java.util.UUID

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
    val snapshotId: String?,
    val accessRevision: Long?,
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseTokens: () -> String = { "unified-query-${UUID.randomUUID()}" },
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
                        plan.usesVisualRetriever,
                    )
                } catch (failure: QueryConsistencyChangedException) {
                    consistencyFailure = failure
                    return@repeat
                }
            }
            try {
                return searchLeased(query, pipelineVersion, limit, plan.intent, plan.usesVisualRetriever, lease)
            } catch (failure: QueryConsistencyChangedException) {
                consistencyFailure = failure
            } finally {
                vectors.releaseQueryLease(lease.leaseToken)
            }
        }
        throw checkNotNull(consistencyFailure)
    }

    private suspend fun searchLeased(
        query: String,
        pipelineVersion: String,
        limit: Int,
        intent: MultimodalQueryIntent,
        usesVisualRetriever: Boolean,
        lease: QuerySnapshotLeaseEntity,
    ): UnifiedSearchResult {
        val textResult =
            text.searchLeased(
                query = query,
                pipelineVersion = pipelineVersion,
                limit = MaximumRetrieverCandidates,
                lease = lease,
            )
        val visualResult =
            if (usesVisualRetriever) {
                visual.searchLeased(query, MaximumRetrieverCandidates, lease)
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

        return fuse(intent, lease.snapshotId, lease.accessRevision, textResult, visualResult, limit)
    }

    private suspend fun searchWithoutSnapshot(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int,
        intent: MultimodalQueryIntent,
        usesVisualRetriever: Boolean,
    ): UnifiedSearchResult {
        val before = captureState()
        val textResult = text.search(query, pipelineVersion, fallbackComponentHash, MaximumRetrieverCandidates)
        val visualResult = if (usesVisualRetriever) visual.search(query, MaximumRetrieverCandidates) else null
        val after = captureState()
        if (before != after) throw QueryConsistencyChangedException()
        return fuse(intent, before.snapshotId, before.accessRevision, textResult, visualResult, limit)
    }

    private fun fuse(
        intent: MultimodalQueryIntent,
        snapshotId: String?,
        accessRevision: Long?,
        textResult: OcrHybridSearchResult,
        visualResult: VisualTextSearchResult?,
        limit: Int,
    ): UnifiedSearchResult {
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
            snapshotId = snapshotId,
            accessRevision = accessRevision,
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
        private const val LeaseDurationMillis = 5 * 60 * 1_000L
    }
}
