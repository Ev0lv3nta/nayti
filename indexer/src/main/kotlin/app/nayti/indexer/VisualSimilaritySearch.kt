package app.nayti.indexer

import app.nayti.search.engine.NativeVectorIndex
import app.nayti.search.engine.NativeVectorSearchHit
import app.nayti.search.engine.VectorSegmentChannel
import app.nayti.search.engine.VectorSegmentV1Reader
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.QuerySnapshotLeaseEntity
import app.nayti.storage.VectorGenerationEntity
import app.nayti.storage.VectorGenerationState
import app.nayti.storage.VectorIndexDao
import app.nayti.storage.VectorSegmentArtifactEntity
import app.nayti.storage.VisualVectorEvidence
import java.io.File
import java.nio.file.Files
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VisualSimilaritySearchStatus {
    READY,
    NO_ACTIVE_SNAPSHOT,
    NO_VISUAL_MANIFEST,
    SOURCE_NOT_INDEXED,
}

data class VisualSimilarityHit(
    val assetId: Long,
    val rank: Int,
    val rawScore: Int,
    val similarityMicros: Int,
    val sourceFingerprint: String,
)

data class VisualSimilaritySearchResult(
    val status: VisualSimilaritySearchStatus,
    val sourceAssetId: Long,
    val snapshotId: String?,
    val manifestRevision: String?,
    val accessRevision: Long?,
    val hits: List<VisualSimilarityHit>,
)

data class VisualQueryContract(
    val packId: String,
    val packVersion: String,
    val packManifestSha256: String,
    val embeddingSpaceHash: String,
    val dimension: Int,
)

internal data class EncodedVisualSearchResult(
    val status: VisualSimilaritySearchStatus,
    val snapshotId: String?,
    val manifestRevision: String?,
    val accessRevision: Long?,
    val hits: List<VisualSimilarityHit>,
)

/** Searches the leased visual snapshot using the source asset's already-published vector. */
class VisualSimilaritySearch(
    private val vectors: VectorIndexDao,
    vectorRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseTokens: () -> String = { "visual-query-${UUID.randomUUID()}" },
) {
    private val files = ImmutableVectorFiles(vectorRoot)

    suspend fun searchSimilar(
        sourceAssetId: Long,
        limit: Int = DefaultLimit,
    ): VisualSimilaritySearchResult {
        require(sourceAssetId > 0)
        require(limit in 1..MaximumResultLimit)
        val acquiredAt = clock()
        val lease =
            vectors.acquireCurrentSnapshotLease(
                leaseToken = leaseTokens(),
                nowMillis = acquiredAt,
                expiresAtMillis = Math.addExact(acquiredAt, LeaseDurationMillis),
            ) ?: return empty(VisualSimilaritySearchStatus.NO_ACTIVE_SNAPSHOT, sourceAssetId)
        return try {
            withContext(Dispatchers.Default) {
                searchLeased(sourceAssetId, limit, lease)
            }
        } finally {
            vectors.releaseQueryLease(lease.leaseToken)
        }
    }

    internal suspend fun searchEncoded(
        limit: Int = DefaultLimit,
        encoder: suspend (VisualQueryContract) -> ByteArray,
    ): EncodedVisualSearchResult {
        require(limit in 1..MaximumResultLimit)
        val acquiredAt = clock()
        val lease =
            vectors.acquireCurrentSnapshotLease(
                leaseToken = leaseTokens(),
                nowMillis = acquiredAt,
                expiresAtMillis = Math.addExact(acquiredAt, LeaseDurationMillis),
            ) ?: return EncodedVisualSearchResult(
                VisualSimilaritySearchStatus.NO_ACTIVE_SNAPSHOT,
                null,
                null,
                null,
                emptyList(),
            )
        return try {
            withContext(Dispatchers.Default) {
                searchEncodedLeased(limit, lease, encoder)
            }
        } finally {
            vectors.releaseQueryLease(lease.leaseToken)
        }
    }

    internal suspend fun searchLeased(
        sourceAssetId: Long,
        limit: Int,
        lease: QuerySnapshotLeaseEntity,
    ): VisualSimilaritySearchResult {
        val snapshot = checkNotNull(vectors.snapshot(lease.snapshotId))
        check(snapshot.engineContractVersion == NativeVectorIndex.contractVersion())
        val manifestRevision = snapshot.visualManifestRevision
            ?: return empty(
                VisualSimilaritySearchStatus.NO_VISUAL_MANIFEST,
                sourceAssetId,
                snapshot.snapshotId,
                accessRevision = lease.accessRevision,
            )
        val index = validatedIndex(snapshot, manifestRevision)

        var leaseExpiresAt = lease.expiresAtMillis
        var queryVector: ByteArray? = null
        for (artifact in index.artifacts.asReversed()) {
            leaseExpiresAt = renewIfNeeded(lease, leaseExpiresAt)
            val eligible =
                vectors.currentEligibleVisualRecordIds(
                    manifestRevision = manifestRevision,
                    segmentSha256 = artifact.sha256,
                    visualPipelineVersion = index.generation.pipelineVersion,
                    componentHash = index.generation.componentHash,
                )
            if (sourceAssetId !in eligible) continue
            val decoded = decodeVerified(artifact)
            queryVector = decoded.records.single { record -> record.recordId == sourceAssetId }.vector
            break
        }
        val sourceVector = queryVector
            ?: return empty(
                VisualSimilaritySearchStatus.SOURCE_NOT_INDEXED,
                sourceAssetId,
                snapshot.snapshotId,
                manifestRevision,
                lease.accessRevision,
            )
        check(sourceVector.size == index.generation.dimension)
        val hits = scan(index, sourceVector, sourceAssetId, limit, lease, leaseExpiresAt)
        return ready(sourceAssetId, snapshot.snapshotId, manifestRevision, lease.accessRevision, hits)
    }

    internal suspend fun searchEncodedLeased(
        limit: Int,
        lease: QuerySnapshotLeaseEntity,
        encoder: suspend (VisualQueryContract) -> ByteArray,
    ): EncodedVisualSearchResult {
        val snapshot = checkNotNull(vectors.snapshot(lease.snapshotId))
        check(snapshot.engineContractVersion == NativeVectorIndex.contractVersion())
        val manifestRevision = snapshot.visualManifestRevision
            ?: return EncodedVisualSearchResult(
                VisualSimilaritySearchStatus.NO_VISUAL_MANIFEST,
                snapshot.snapshotId,
                null,
                lease.accessRevision,
                emptyList(),
            )
        val index = validatedIndex(snapshot, manifestRevision)
        val queryVector =
            encoder(
                VisualQueryContract(
                    packId = snapshot.packId,
                    packVersion = snapshot.packVersion,
                    packManifestSha256 = snapshot.packManifestSha256,
                    embeddingSpaceHash = index.generation.embeddingSpaceHash,
                    dimension = index.generation.dimension,
                ),
            )
        check(queryVector.size == index.generation.dimension)
        val hits = scan(index, queryVector, null, limit, lease, lease.expiresAtMillis)
        return EncodedVisualSearchResult(
            VisualSimilaritySearchStatus.READY,
            snapshot.snapshotId,
            manifestRevision,
            lease.accessRevision,
            hits,
        )
    }

    private suspend fun validatedIndex(
        snapshot: ActivationSnapshotEntity,
        manifestRevision: String,
    ): LeasedVisualIndex {
        val component = checkNotNull(vectors.snapshotChannel(snapshot.snapshotId, IndexChannel.VISUAL))
        val manifest = checkNotNull(vectors.manifest(manifestRevision))
        val generation = checkNotNull(vectors.generation(manifest.generationId))
        check(
            manifest.channel == IndexChannel.VISUAL &&
                generation.channel == IndexChannel.VISUAL &&
                generation.generationId == manifest.generationId &&
                generation.generationId == component.generationId &&
                generation.pipelineVersion == component.pipelineVersion &&
                generation.componentHash == component.componentHash &&
                generation.embeddingSpaceHash == component.embeddingSpaceHash &&
                manifest.revision == component.manifestRevision &&
                generation.state in setOf(VectorGenerationState.BUILDING, VectorGenerationState.SEALED),
        )
        val entries = vectors.manifestSegments(manifestRevision)
        check(entries.size == manifest.segmentCount && entries.map { it.ordinal } == entries.indices.toList())
        val artifacts = entries.map { entry ->
            checkNotNull(vectors.segment(entry.segmentSha256)).also { artifact ->
                check(
                    artifact.sha256 == entry.segmentSha256 &&
                        artifact.channel == IndexChannel.VISUAL &&
                        artifact.embeddingSpaceHash == generation.embeddingSpaceHash &&
                        artifact.dimension == generation.dimension,
                )
            }
        }
        check(artifacts.sumOf { it.recordCount.toLong() } == manifest.recordCount)
        return LeasedVisualIndex(manifestRevision, generation, artifacts)
    }

    private suspend fun scan(
        index: LeasedVisualIndex,
        queryVector: ByteArray,
        excludedAssetId: Long?,
        limit: Int,
        lease: QuerySnapshotLeaseEntity,
        initialLeaseExpiresAt: Long,
    ): List<VisualSimilarityHit> {
        var leaseExpiresAt = initialLeaseExpiresAt
        val candidates = PriorityQueue(MaximumNativeCandidates, CandidateOrder.reversed())
        index.artifacts.forEachIndexed { manifestOrdinal, artifact ->
            leaseExpiresAt = renewIfNeeded(lease, leaseExpiresAt)
            val eligible =
                vectors.currentEligibleVisualRecordIds(
                    manifestRevision = index.manifestRevision,
                    segmentSha256 = artifact.sha256,
                    visualPipelineVersion = index.generation.pipelineVersion,
                    componentHash = index.generation.componentHash,
                ).filterNot { recordId -> recordId == excludedAssetId }
            if (eligible.isEmpty()) return@forEachIndexed
            NativeVectorIndex.exactTopK(
                path = safeArtifactFile(artifact.relativePath).absolutePath,
                expectedLength = artifact.byteLength,
                expectedSha256 = artifact.sha256.hexToBytes(),
                query = queryVector,
                k = minOf(MaximumNativeCandidates, eligible.size),
                channel = VectorSegmentChannel.VISUAL,
                embeddingSpaceHash = index.generation.embeddingSpaceHash.hexToBytes(),
                eligibleRecordIds = eligible.toLongArray(),
            ).forEach { hit ->
                candidates.retain(
                    NativeCandidate(
                        segmentSha256 = artifact.sha256,
                        manifestOrdinal = manifestOrdinal,
                        hit = hit,
                    ),
                )
            }
        }
        val ordered = candidates.toList().sortedWith(CandidateOrder)
        val evidence =
            if (ordered.isEmpty()) {
                emptyList()
            } else {
                vectors.currentVisualEvidence(
                    manifestRevision = index.manifestRevision,
                    recordIds = ordered.map { it.hit.recordId }.distinct(),
                    visualPipelineVersion = index.generation.pipelineVersion,
                    componentHash = index.generation.componentHash,
                )
            }
        val evidenceByRecord = evidence.associateBy { row -> RecordKey(row.segmentSha256, row.recordId) }
        check(evidenceByRecord.size == evidence.size)
        val selected =
            ordered.mapNotNull { candidate ->
                val row = evidenceByRecord[RecordKey(candidate.segmentSha256, candidate.hit.recordId)]
                    ?: return@mapNotNull null
                check(row.assetId == candidate.hit.assetId && candidate.hit.ordinal == 0)
                CandidateEvidence(candidate, row)
            }.distinctBy { it.evidence.assetId }
                .take(limit)
        val currentAccess = vectors.accessObservation()
        if (
            currentAccess == null ||
            currentAccess.accessScope == "None" ||
            currentAccess.processAccessRevision != lease.accessRevision
        ) {
            return emptyList()
        }
        return selected.mapIndexed { rank, selectedHit ->
            VisualSimilarityHit(
                assetId = selectedHit.evidence.assetId,
                rank = rank + 1,
                rawScore = selectedHit.candidate.hit.score,
                similarityMicros = similarityMicros(selectedHit.candidate.hit.score, index.generation.dimension),
                sourceFingerprint = selectedHit.evidence.sourceFingerprint,
            )
        }
    }

    private suspend fun renewIfNeeded(lease: QuerySnapshotLeaseEntity, expiresAt: Long): Long {
        val now = clock()
        if (now < expiresAt - LeaseRenewalMarginMillis) return expiresAt
        val renewed = Math.addExact(now, LeaseDurationMillis)
        check(
            vectors.renewCurrentSnapshotLease(
                leaseToken = lease.leaseToken,
                nowMillis = now,
                expiresAtMillis = renewed,
            ),
        ) { "Visual query snapshot lease expired or lost access" }
        return renewed
    }

    private fun decodeVerified(artifact: VectorSegmentArtifactEntity) =
        safeArtifactFile(artifact.relativePath).let {
            VectorSegmentV1Reader.decode(
                files.readVerified(artifact.relativePath, artifact.byteLength, artifact.sha256),
            )
        }.also { decoded ->
            check(
                decoded.channel == VectorSegmentChannel.VISUAL &&
                    decoded.embeddingSpaceHash == artifact.embeddingSpaceHash &&
                    decoded.dimension == artifact.dimension &&
                    decoded.records.size == artifact.recordCount,
            )
        }

    private fun safeArtifactFile(relativePath: String): File {
        val root = files.root.toPath()
        check(!Files.isSymbolicLink(root))
        val candidate = root.resolve(relativePath).normalize()
        check(candidate.startsWith(root) && candidate != root && !Files.isSymbolicLink(candidate))
        var parent = candidate.parent
        while (parent != null && parent != root) {
            check(!Files.isSymbolicLink(parent))
            parent = parent.parent
        }
        check(parent == root)
        return candidate.toFile()
    }

    private fun PriorityQueue<NativeCandidate>.retain(candidate: NativeCandidate) {
        if (size < MaximumNativeCandidates) {
            add(candidate)
        } else if (CandidateOrder.compare(candidate, peek()) < 0) {
            remove()
            add(candidate)
        }
    }

    private fun String.hexToBytes(): ByteArray {
        check(Sha256.matches(this))
        return ByteArray(32) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun similarityMicros(score: Int, dimension: Int): Int {
        val maximumDot = dimension.toDouble() * QuantizedMaximum * QuantizedMaximum
        return (score.toDouble() * SimilarityScale / maximumDot).roundToInt()
            .coerceIn(-SimilarityScale, SimilarityScale)
    }

    private fun ready(
        sourceAssetId: Long,
        snapshotId: String,
        manifestRevision: String,
        accessRevision: Long,
        hits: List<VisualSimilarityHit>,
    ) = VisualSimilaritySearchResult(
        status = VisualSimilaritySearchStatus.READY,
        sourceAssetId = sourceAssetId,
        snapshotId = snapshotId,
        manifestRevision = manifestRevision,
        accessRevision = accessRevision,
        hits = hits,
    )

    private fun empty(
        status: VisualSimilaritySearchStatus,
        sourceAssetId: Long,
        snapshotId: String? = null,
        manifestRevision: String? = null,
        accessRevision: Long? = null,
    ) = VisualSimilaritySearchResult(
        status = status,
        sourceAssetId = sourceAssetId,
        snapshotId = snapshotId,
        manifestRevision = manifestRevision,
        accessRevision = accessRevision,
        hits = emptyList(),
    )

    private data class NativeCandidate(
        val segmentSha256: String,
        val manifestOrdinal: Int,
        val hit: NativeVectorSearchHit,
    )

    private data class RecordKey(val segmentSha256: String, val recordId: Long)

    private data class CandidateEvidence(
        val candidate: NativeCandidate,
        val evidence: VisualVectorEvidence,
    )

    private data class LeasedVisualIndex(
        val manifestRevision: String,
        val generation: VectorGenerationEntity,
        val artifacts: List<VectorSegmentArtifactEntity>,
    )

    companion object {
        const val DefaultLimit = 50
        const val MaximumResultLimit = 100
        private const val MaximumNativeCandidates = 512
        private const val LeaseDurationMillis = 5 * 60 * 1_000L
        private const val LeaseRenewalMarginMillis = 60_000L
        private const val QuantizedMaximum = 127.0
        private const val SimilarityScale = 1_000_000
        private val Sha256 = Regex("[0-9a-f]{64}")
        private val CandidateOrder =
            compareByDescending<NativeCandidate> { it.hit.score }
                .thenBy { it.hit.recordId }
                .thenByDescending { it.manifestOrdinal }
                .thenBy { it.hit.assetId }
                .thenBy { it.segmentSha256 }
    }
}
