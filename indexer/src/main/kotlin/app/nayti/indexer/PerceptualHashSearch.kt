package app.nayti.indexer

import app.nayti.search.engine.similarity.PerceptualHashMatch
import app.nayti.search.engine.similarity.PerceptualHashRanker
import app.nayti.search.engine.similarity.PerceptualHashRecord
import app.nayti.search.engine.similarity.PerceptualHashV1
import app.nayti.storage.PerceptualHashDao

enum class PerceptualHashSearchStatus {
    READY,
    SOURCE_NOT_INDEXED,
}

data class PerceptualHashSearchResult(
    val status: PerceptualHashSearchStatus,
    val sourceAssetId: Long,
    val capturedPublicationEpoch: Long,
    val hits: List<PerceptualHashMatch>,
)

class PerceptualHashSearch(
    private val hashes: PerceptualHashDao,
) {
    suspend fun nearDuplicates(
        sourceAssetId: Long,
        maximumDistance: Int = PerceptualHashV1.DefaultNearDuplicateDistance,
        limit: Int = PerceptualHashRanker.DefaultLimit,
    ): PerceptualHashSearchResult {
        require(sourceAssetId > 0)
        val epoch =
            hashes.maximumPublicationEpoch(
                PerceptualHashV1.PipelineVersion,
                PerceptualHashV1.ComponentHash,
            )
        val rows =
            hashes.current(
                pipelineVersion = PerceptualHashV1.PipelineVersion,
                componentHash = PerceptualHashV1.ComponentHash,
                maximumPublicationEpoch = epoch,
            )
        val source = rows.singleOrNull { it.assetId == sourceAssetId }
            ?: return PerceptualHashSearchResult(
                PerceptualHashSearchStatus.SOURCE_NOT_INDEXED,
                sourceAssetId,
                epoch,
                emptyList(),
            )
        val records = rows.map { PerceptualHashRecord(it.assetId, it.hashBits, it.publicationEpoch) }
        return PerceptualHashSearchResult(
            status = PerceptualHashSearchStatus.READY,
            sourceAssetId = sourceAssetId,
            capturedPublicationEpoch = epoch,
            hits =
                PerceptualHashRanker.rank(
                    sourceAssetId = sourceAssetId,
                    sourceHash = source.hashBits,
                    records = records,
                    maximumDistance = maximumDistance,
                    limit = limit,
                ),
        )
    }
}
