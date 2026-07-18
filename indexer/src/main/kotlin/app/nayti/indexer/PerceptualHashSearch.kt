package app.nayti.indexer

import app.nayti.search.engine.similarity.PerceptualHashMatch
import app.nayti.search.engine.similarity.PerceptualHashRanker
import app.nayti.search.engine.similarity.PerceptualHashRecord
import app.nayti.search.engine.similarity.PerceptualHashV1
import app.nayti.storage.PerceptualHashDao
import app.nayti.storage.IndexChannel
import app.nayti.storage.VectorIndexDao
import java.util.UUID

enum class PerceptualHashSearchStatus {
    READY,
    NO_ACTIVE_SNAPSHOT,
    SOURCE_NOT_INDEXED,
}

data class PerceptualHashSearchResult(
    val status: PerceptualHashSearchStatus,
    val sourceAssetId: Long,
    val snapshotId: String?,
    val accessRevision: Long?,
    val capturedPublicationEpoch: Long,
    val hits: List<PerceptualHashMatch>,
)

class PerceptualHashSearch(
    private val hashes: PerceptualHashDao,
    private val vectors: VectorIndexDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseTokens: () -> String = { "phash-query-${UUID.randomUUID()}" },
) {
    suspend fun nearDuplicates(
        sourceAssetId: Long,
        maximumDistance: Int = PerceptualHashV1.DefaultNearDuplicateDistance,
        limit: Int = PerceptualHashRanker.DefaultLimit,
    ): PerceptualHashSearchResult {
        require(sourceAssetId > 0)
        val acquiredAt = clock()
        val lease =
            vectors.acquireCurrentSnapshotLease(
                leaseToken = leaseTokens(),
                nowMillis = acquiredAt,
                expiresAtMillis = acquiredAt + LeaseDurationMillis,
            ) ?: return PerceptualHashSearchResult(
                PerceptualHashSearchStatus.NO_ACTIVE_SNAPSHOT,
                sourceAssetId,
                null,
                null,
                0,
                emptyList(),
            )
        return try {
            val snapshot = checkNotNull(vectors.snapshot(lease.snapshotId))
            val component = checkNotNull(vectors.snapshotChannel(snapshot.snapshotId, IndexChannel.PHASH))
            val rows =
                hashes.current(
                    pipelineVersion = component.pipelineVersion,
                    componentHash = component.componentHash,
                    maximumPublicationEpoch = snapshot.pHashPublicationEpoch,
                )
            val access = vectors.accessObservation()
            check(access?.accessScope != "None" && access?.processAccessRevision == lease.accessRevision)
            val source = rows.singleOrNull { it.assetId == sourceAssetId }
            if (source == null) {
                PerceptualHashSearchResult(
                    PerceptualHashSearchStatus.SOURCE_NOT_INDEXED,
                    sourceAssetId,
                    snapshot.snapshotId,
                    lease.accessRevision,
                    snapshot.pHashPublicationEpoch,
                    emptyList(),
                )
            } else {
                val records = rows.map { PerceptualHashRecord(it.assetId, it.hashBits, it.publicationEpoch) }
                PerceptualHashSearchResult(
                    status = PerceptualHashSearchStatus.READY,
                    sourceAssetId = sourceAssetId,
                    snapshotId = snapshot.snapshotId,
                    accessRevision = lease.accessRevision,
                    capturedPublicationEpoch = snapshot.pHashPublicationEpoch,
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
        } finally {
            vectors.releaseQueryLease(lease.leaseToken)
        }
    }

    private companion object {
        const val LeaseDurationMillis = 5 * 60 * 1_000L
    }
}
