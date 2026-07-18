package app.nayti.indexer

import app.nayti.search.engine.fusion.StrictTextFusion
import app.nayti.search.engine.fusion.TextFusionIntent
import app.nayti.search.engine.fusion.TextFusionReason
import app.nayti.search.engine.fusion.TextLexicalCandidate
import app.nayti.search.engine.fusion.TextLexicalEvidence
import app.nayti.search.engine.fusion.TextSemanticCandidate
import app.nayti.search.engine.lexical.LexicalIntent
import app.nayti.storage.OcrDao
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
    ocr: OcrDao,
    private val vectors: VectorIndexDao,
    private val semantic: OcrSemanticSearch,
    private val lexical: OcrLexicalSearch = OcrLexicalSearch(ocr),
) {
    suspend fun search(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int = DefaultLimit,
    ): OcrHybridSearchResult {
        require(limit in 1..MaximumResultLimit)
        var accessFailure: SemanticAccessChangedException? = null
        repeat(MaximumAccessAttempts) {
            try {
                return searchOnce(query, pipelineVersion, fallbackComponentHash, limit)
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
    ): OcrHybridSearchResult {
        require(limit in 1..MaximumResultLimit)
        val snapshot = checkNotNull(vectors.snapshot(lease.snapshotId))
        val lexicalResult =
            lexical.searchAtEpoch(
                query = query,
                pipelineVersion = pipelineVersion,
                componentHash = snapshot.packManifestSha256,
                maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
                limit = MaximumRetrieverCandidates,
            )
        val semanticResult =
            if (lexicalResult.intent == LexicalIntent.ORDINARY_TEXT) {
                semantic.searchLeased(query.trim(), MaximumRetrieverCandidates, lease)
            } else {
                null
            }
        if (semanticResult?.status == OcrSemanticSearchStatus.READY) {
            check(semanticResult.snapshotId == snapshot.snapshotId)
            check(semanticResult.accessRevision == lease.accessRevision)
            check(semanticResult.lexicalPublicationEpoch == snapshot.lexicalPublicationEpoch)
            check(semanticResult.componentHash == snapshot.packManifestSha256)
        }
        return fuse(lexicalResult, semanticResult, limit)
    }

    private suspend fun searchOnce(
        query: String,
        pipelineVersion: String,
        fallbackComponentHash: String,
        limit: Int,
    ): OcrHybridSearchResult {
        val activeSnapshot = vectors.activeSnapshotId()?.let { snapshotId -> vectors.snapshot(snapshotId) }
        var lexicalResult =
            if (activeSnapshot == null) {
                lexical.search(query, pipelineVersion, fallbackComponentHash, MaximumRetrieverCandidates)
            } else {
                lexical.searchAtEpoch(
                    query = query,
                    pipelineVersion = pipelineVersion,
                    componentHash = activeSnapshot.packManifestSha256,
                    maximumPublicationEpoch = activeSnapshot.lexicalPublicationEpoch,
                    limit = MaximumRetrieverCandidates,
                )
            }
        val semanticResult =
            if (lexicalResult.intent == LexicalIntent.ORDINARY_TEXT) {
                semantic.search(query, MaximumRetrieverCandidates)
            } else {
                null
            }
        if (semanticResult?.status == OcrSemanticSearchStatus.READY) {
            val semanticEpoch = checkNotNull(semanticResult.lexicalPublicationEpoch)
            val semanticComponent = checkNotNull(semanticResult.componentHash)
            if (
                lexicalResult.capturedPublicationEpoch != semanticEpoch ||
                activeSnapshot?.packManifestSha256 != semanticComponent
            ) {
                lexicalResult =
                    lexical.searchAtEpoch(
                        query = query,
                        pipelineVersion = pipelineVersion,
                        componentHash = semanticComponent,
                        maximumPublicationEpoch = semanticEpoch,
                        limit = MaximumRetrieverCandidates,
                    )
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

        return fuse(lexicalResult, semanticResult, limit)
    }

    private fun fuse(
        lexicalResult: OcrLexicalSearchResult,
        semanticResult: OcrSemanticSearchResult?,
        limit: Int,
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
