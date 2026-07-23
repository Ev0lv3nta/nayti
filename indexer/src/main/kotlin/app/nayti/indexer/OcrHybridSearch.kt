package app.nayti.indexer

import app.nayti.search.engine.fusion.StrictTextFusion
import app.nayti.search.engine.fusion.TextFusionIntent
import app.nayti.search.engine.fusion.TextFusionReason
import app.nayti.search.engine.fusion.TextLexicalCandidate
import app.nayti.search.engine.fusion.TextLexicalEvidence
import app.nayti.search.engine.fusion.TextSemanticCandidate
import app.nayti.search.engine.lexical.LexicalIntent
import app.nayti.search.engine.lexical.LexicalQueryPlanner
import app.nayti.storage.OcrDao
import app.nayti.storage.IndexChannel
import app.nayti.storage.QuerySnapshotLeaseEntity
import app.nayti.storage.VectorIndexDao

data class HybridOcrHit(
    val assetId: Long,
    val rank: Int,
    val tier: Int,
    val evidence: TextFusionReason,
    val displaySnippet: String,
    val matchedRegionOrdinals: List<Int>,
    val semanticChunkId: String?,
    val lexicalRank: Int?,
    val semanticRank: Int?,
    val publicationEpoch: Long,
)

data class OcrHybridSearchResult(
    val intent: LexicalIntent,
    val lexicalPublicationEpoch: Long,
    val semanticStatus: OcrSemanticSearchStatus,
    val hits: List<HybridOcrHit>,
)

class OcrHybridSearch(
    private val ocr: OcrDao,
    private val vectors: VectorIndexDao,
    private val semantic: OcrSemanticSearch,
    private val lexical: OcrLexicalSearch = OcrLexicalSearch(ocr),
    private val planner: LexicalQueryPlanner = LexicalQueryPlanner(),
) {
    suspend fun search(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int = DefaultLimit,
        filter: SearchFilter = SearchFilter.None,
        channels: SearchChannelSelection? = null,
    ): OcrHybridSearchResult {
        require(limit in 1..MaximumResultLimit)
        var accessFailure: SemanticAccessChangedException? = null
        repeat(MaximumAccessAttempts) {
            try {
                return searchOnce(query, pipelineVersion, fallbackComponentHash, limit, filter, channels)
            } catch (failure: SemanticAccessChangedException) {
                accessFailure = failure
            }
        }
        throw checkNotNull(accessFailure)
    }

    internal suspend fun searchLeased(
        query: String,
        pipelineVersion: String,
        limit: Int,
        lease: QuerySnapshotLeaseEntity,
        filter: SearchFilter = SearchFilter.None,
        channels: SearchChannelSelection? = null,
    ): OcrHybridSearchResult {
        require(limit in 1..MaximumResultLimit)
        val snapshot = checkNotNull(vectors.snapshot(lease.snapshotId))
        val ocrComponent = checkNotNull(vectors.snapshotChannel(snapshot.snapshotId, IndexChannel.OCR))
        val intent = planner.plan(query).intent
        val effectiveChannels = channels ?: intent.defaultSearchChannels()
        require(effectiveChannels.usesText)
        val lexicalResult =
            if (effectiveChannels.ocrLiteral) {
                lexical.searchAtEpoch(
                    query = query,
                    pipelineVersion = pipelineVersion,
                    componentHash = ocrComponent.componentHash,
                    maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
                    limit = MaximumRetrieverCandidates,
                    filter = filter,
                )
            } else {
                OcrLexicalSearchResult(intent, snapshot.lexicalPublicationEpoch, emptyList())
            }
        val semanticResult =
            if (effectiveChannels.ocrSemantic) {
                semantic.searchLeased(query.trim(), MaximumRetrieverCandidates, lease, filter)
            } else {
                null
            }
        if (semanticResult?.status == OcrSemanticSearchStatus.READY) {
            val semanticComponent = checkNotNull(vectors.snapshotChannel(snapshot.snapshotId, IndexChannel.OCR_SEMANTIC))
            check(semanticResult.snapshotId == snapshot.snapshotId)
            check(semanticResult.accessRevision == lease.accessRevision)
            check(semanticResult.lexicalPublicationEpoch == snapshot.lexicalPublicationEpoch)
            check(semanticResult.componentHash == semanticComponent.componentHash)
        }
        return fuse(lexicalResult, semanticResult, limit, effectiveChannels)
    }

    private suspend fun searchOnce(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int,
        filter: SearchFilter,
        channels: SearchChannelSelection?,
    ): OcrHybridSearchResult {
        val activeSnapshot = vectors.activeSnapshotId()?.let { snapshotId -> vectors.snapshot(snapshotId) }
        val activeOcrComponent = activeSnapshot?.let { snapshot ->
            vectors.snapshotChannel(snapshot.snapshotId, IndexChannel.OCR)?.componentHash
        }
        val intent = planner.plan(query).intent
        val effectiveChannels = channels ?: intent.defaultSearchChannels()
        require(effectiveChannels.usesText)
        var lexicalResult =
            if (!effectiveChannels.ocrLiteral) {
                OcrLexicalSearchResult(
                    intent = intent,
                    capturedPublicationEpoch =
                        activeSnapshot?.lexicalPublicationEpoch ?: ocr.publicationClock()?.lastEpoch ?: 0,
                    hits = emptyList(),
                )
            } else if (activeSnapshot == null) {
                lexical.search(query, pipelineVersion, fallbackComponentHash, MaximumRetrieverCandidates, filter)
            } else {
                lexical.searchAtEpoch(
                    query = query,
                    pipelineVersion = pipelineVersion,
                    componentHash = activeOcrComponent ?: activeSnapshot.packManifestSha256,
                    maximumPublicationEpoch = activeSnapshot.lexicalPublicationEpoch,
                    limit = MaximumRetrieverCandidates,
                    filter = filter,
                )
            }
        val semanticResult =
            if (effectiveChannels.ocrSemantic) {
                semantic.search(query, MaximumRetrieverCandidates, filter)
            } else {
                null
            }
        if (semanticResult?.status == OcrSemanticSearchStatus.READY) {
            val semanticEpoch = checkNotNull(semanticResult.lexicalPublicationEpoch)
            if (effectiveChannels.ocrLiteral && lexicalResult.capturedPublicationEpoch != semanticEpoch) {
                lexicalResult =
                    lexical.searchAtEpoch(
                        query = query,
                        pipelineVersion = pipelineVersion,
                        componentHash = activeOcrComponent ?: activeSnapshot?.packManifestSha256 ?: fallbackComponentHash,
                        maximumPublicationEpoch = semanticEpoch,
                        limit = MaximumRetrieverCandidates,
                        filter = filter,
                    )
            } else if (!effectiveChannels.ocrLiteral && lexicalResult.capturedPublicationEpoch != semanticEpoch) {
                lexicalResult = lexicalResult.copy(capturedPublicationEpoch = semanticEpoch)
            }
            val access = vectors.accessObservation()
            if (
                access == null ||
                access.accessScope == "None" ||
                access.processAccessRevision != semanticResult.accessRevision
            ) {
                throw SemanticAccessChangedException()
            }
        }

        return fuse(lexicalResult, semanticResult, limit, effectiveChannels)
    }

    private fun fuse(
        lexicalResult: OcrLexicalSearchResult,
        semanticResult: OcrSemanticSearchResult?,
        limit: Int,
        channels: SearchChannelSelection,
    ): OcrHybridSearchResult {
        val lexicalByAsset = lexicalResult.hits.associateBy(OcrLexicalHit::assetId)
        val semanticByAsset = semanticResult?.hits.orEmpty().associateBy(OcrSemanticHit::assetId)
        val fused =
            StrictTextFusion.rank(
                intent = lexicalResult.intent.toFusionIntent(),
                lexical = lexicalResult.hits.map { hit ->
                    TextLexicalCandidate(hit.assetId, hit.rank, hit.evidence.toFusionEvidence())
                },
                semantic = semanticResult?.hits.orEmpty().map { hit ->
                    TextSemanticCandidate(hit.assetId, hit.rank)
                },
                limit = limit,
                allowSemanticFallback = channels.ocrSemantic,
            )
        return OcrHybridSearchResult(
            intent = lexicalResult.intent,
            lexicalPublicationEpoch = lexicalResult.capturedPublicationEpoch,
            semanticStatus = semanticResult?.status ?: OcrSemanticSearchStatus.NOT_REQUESTED,
            hits = fused.mapIndexed { index, candidate ->
                val lexicalHit = lexicalByAsset[candidate.assetId]
                val semanticHit = semanticByAsset[candidate.assetId]
                val matchedLines =
                    (lexicalHit?.matchedRegionOrdinals.orEmpty() + semanticHit?.matchedLineOrdinals.orEmpty())
                        .distinct()
                HybridOcrHit(
                    assetId = candidate.assetId,
                    rank = index + 1,
                    tier = candidate.tier,
                    evidence = candidate.reason,
                    displaySnippet = lexicalHit?.displaySnippet ?: checkNotNull(semanticHit).displaySnippet,
                    matchedRegionOrdinals = matchedLines,
                    semanticChunkId = semanticHit?.semanticChunkId,
                    lexicalRank = candidate.lexicalRank,
                    semanticRank = candidate.semanticRank,
                    publicationEpoch = lexicalHit?.publicationEpoch ?: checkNotNull(semanticHit).publicationEpoch,
                )
            },
        )
    }

    private fun LexicalIntent.toFusionIntent(): TextFusionIntent =
        when (this) {
            LexicalIntent.QUOTED_PHRASE -> TextFusionIntent.QUOTED_EXACT
            LexicalIntent.IDENTIFIER -> TextFusionIntent.IDENTIFIER
            LexicalIntent.PERSON_NAME -> TextFusionIntent.PERSON_NAME
            LexicalIntent.ORDINARY_TEXT -> TextFusionIntent.TEXT_CONCEPT
            LexicalIntent.EMPTY -> error("Empty query has no fusion intent")
        }

    private fun LexicalIntent.defaultSearchChannels(): SearchChannelSelection =
        SearchChannelSelection(
            ocrLiteral = true,
            ocrSemantic = this == LexicalIntent.ORDINARY_TEXT,
            visual = false,
        )

    private fun LexicalEvidence.toFusionEvidence(): TextLexicalEvidence =
        TextLexicalEvidence.valueOf(name)

    private class SemanticAccessChangedException : Exception()

    companion object {
        const val DefaultLimit = 50
        const val MaximumResultLimit = 100
        private const val MaximumRetrieverCandidates = 100
        private const val MaximumAccessAttempts = 2
    }
}
