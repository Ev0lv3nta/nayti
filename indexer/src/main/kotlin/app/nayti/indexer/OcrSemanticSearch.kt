package app.nayti.indexer

import app.nayti.ml.runtime.semantic.User2Contract
import app.nayti.ml.runtime.semantic.User2EmbeddingSpaceIdentity
import app.nayti.ml.runtime.semantic.User2OrtRuntime
import app.nayti.search.engine.NativeVectorIndex
import app.nayti.search.engine.NativeVectorSearchHit
import app.nayti.search.engine.VectorSegmentChannel
import app.nayti.storage.IndexChannel
import app.nayti.storage.OcrSemanticDao
import app.nayti.storage.QuerySnapshotLeaseEntity
import app.nayti.storage.SemanticVectorEvidence
import app.nayti.storage.VectorGenerationState
import app.nayti.storage.VectorIndexDao
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class OcrSemanticSearchStatus {
    NOT_REQUESTED,
    READY,
    NO_ACTIVE_SNAPSHOT,
    NO_SEMANTIC_MANIFEST,
}

data class OcrSemanticHit(
    val assetId: Long,
    val rank: Int,
    val rawScore: Int,
    val similarityMicros: Int,
    val displaySnippet: String,
    val semanticChunkId: String,
    val matchedLineOrdinals: List<Int>,
    val publicationEpoch: Long,
)

data class OcrSemanticSearchResult(
    val status: OcrSemanticSearchStatus,
    val snapshotId: String?,
    val manifestRevision: String?,
    val lexicalPublicationEpoch: Long?,
    val accessRevision: Long?,
    val componentHash: String?,
    val hits: List<OcrSemanticHit>,
)

data class SemanticQueryContract(
    val packId: String,
    val packVersion: String,
    val packManifestSha256: String,
    val embeddingSpaceHash: String,
    val dimension: Int,
)

interface SemanticQuerySession : AutoCloseable {
    val embeddingSpaceHash: String
    val dimension: Int
    fun encodeQuery(text: String): ByteArray
}

fun interface SemanticQuerySessionFactory {
    suspend fun open(contract: SemanticQueryContract): SemanticQuerySession
}

class InstalledUser2QuerySessionFactory(
    private val resolver: InstalledOcrPackResolver,
) : SemanticQuerySessionFactory {
    override suspend fun open(contract: SemanticQueryContract): SemanticQuerySession {
        require(contract.dimension == User2Contract.EmbeddingDimension)
        val pack = resolver.resolve(contract.packId, contract.packVersion)
        check(pack.componentHash == contract.packManifestSha256)
        val actualEmbeddingSpace =
            withContext(Dispatchers.IO) {
                User2EmbeddingSpaceIdentity.calculate(pack.payloadDirectory.parent)
            }
        check(actualEmbeddingSpace == contract.embeddingSpaceHash)
        val runtime = withContext(Dispatchers.Default) { User2OrtRuntime.open(pack.payloadDirectory) }
        return User2QuerySession(runtime, actualEmbeddingSpace)
    }
}

private class User2QuerySession(
    private val runtime: User2OrtRuntime,
    override val embeddingSpaceHash: String,
) : SemanticQuerySession {
    override val dimension: Int = User2Contract.EmbeddingDimension

    override fun encodeQuery(text: String): ByteArray = runtime.encodeQuery(text).quantized

    override fun close() = runtime.close()
}

class OcrSemanticSearch(
    private val vectors: VectorIndexDao,
    private val semantic: OcrSemanticDao,
    vectorRoot: File,
    private val sessions: SemanticQuerySessionFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseTokens: () -> String = { "semantic-query-${UUID.randomUUID()}" },
) {
    private val root: Path = vectorRoot.toPath().toAbsolutePath().normalize()

    suspend fun search(
        query: String,
        limit: Int = DefaultLimit,
    ): OcrSemanticSearchResult {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty() && normalizedQuery.length <= MaximumQueryCharacters)
        require(limit in 1..MaximumResultLimit)
        val acquiredAt = clock()
        val lease =
            vectors.acquireCurrentSnapshotLease(
                leaseToken = leaseTokens(),
                nowMillis = acquiredAt,
                expiresAtMillis = Math.addExact(acquiredAt, LeaseDurationMillis),
            ) ?: return OcrSemanticSearchResult(
                status = OcrSemanticSearchStatus.NO_ACTIVE_SNAPSHOT,
                snapshotId = null,
                manifestRevision = null,
                lexicalPublicationEpoch = null,
                accessRevision = null,
                componentHash = null,
                hits = emptyList(),
            )
        return try {
            withContext(Dispatchers.Default) {
                searchLeased(normalizedQuery, limit, lease)
            }
        } finally {
            vectors.releaseQueryLease(lease.leaseToken)
        }
    }

    private suspend fun searchLeased(
        query: String,
        limit: Int,
        lease: QuerySnapshotLeaseEntity,
    ): OcrSemanticSearchResult {
        val snapshot = checkNotNull(vectors.snapshot(lease.snapshotId))
        check(snapshot.engineContractVersion == NativeVectorIndex.contractVersion())
        val manifestRevision = snapshot.semanticManifestRevision
            ?: return OcrSemanticSearchResult(
                status = OcrSemanticSearchStatus.NO_SEMANTIC_MANIFEST,
                snapshotId = snapshot.snapshotId,
                manifestRevision = null,
                lexicalPublicationEpoch = snapshot.lexicalPublicationEpoch,
                accessRevision = lease.accessRevision,
                componentHash = snapshot.packManifestSha256,
                hits = emptyList(),
            )
        val manifest = checkNotNull(vectors.manifest(manifestRevision))
        val generation = checkNotNull(vectors.generation(manifest.generationId))
        check(
            manifest.channel == IndexChannel.OCR_SEMANTIC &&
                generation.channel == IndexChannel.OCR_SEMANTIC &&
                generation.generationId == manifest.generationId &&
                generation.packId == snapshot.packId &&
                generation.packVersion == snapshot.packVersion &&
                generation.componentHash == snapshot.packManifestSha256 &&
                generation.state in setOf(VectorGenerationState.BUILDING, VectorGenerationState.SEALED),
        )

        val entries = vectors.manifestSegments(manifestRevision)
        check(entries.size == manifest.segmentCount && entries.map { it.ordinal } == entries.indices.toList())
        val artifacts = entries.map { entry ->
            checkNotNull(vectors.segment(entry.segmentSha256)).also { artifact ->
                check(
                    artifact.sha256 == entry.segmentSha256 &&
                        artifact.channel == IndexChannel.OCR_SEMANTIC &&
                        artifact.embeddingSpaceHash == generation.embeddingSpaceHash &&
                        artifact.dimension == generation.dimension,
                )
            }
        }
        check(artifacts.sumOf { it.recordCount.toLong() } == manifest.recordCount)

        val contract =
            SemanticQueryContract(
                packId = snapshot.packId,
                packVersion = snapshot.packVersion,
                packManifestSha256 = snapshot.packManifestSha256,
                embeddingSpaceHash = generation.embeddingSpaceHash,
                dimension = generation.dimension,
            )
        val candidates = PriorityQueue(MaximumNativeCandidates, CandidateOrder.reversed())
        var leaseExpiresAt = lease.expiresAtMillis
        sessions.open(contract).use { session ->
            check(
                session.embeddingSpaceHash == generation.embeddingSpaceHash &&
                    session.dimension == generation.dimension,
            )
            val queryVector = session.encodeQuery(query)
            check(queryVector.size == generation.dimension)
            artifacts.forEachIndexed { manifestOrdinal, artifact ->
                val now = clock()
                if (now >= leaseExpiresAt - LeaseRenewalMarginMillis) {
                    leaseExpiresAt = Math.addExact(now, LeaseDurationMillis)
                    check(
                        vectors.renewCurrentSnapshotLease(
                            leaseToken = lease.leaseToken,
                            nowMillis = now,
                            expiresAtMillis = leaseExpiresAt,
                        ),
                    ) { "Semantic query snapshot lease expired or lost access" }
                }
                val file = safeArtifactPath(artifact.relativePath)
                NativeVectorIndex.exactTopK(
                    path = file.toString(),
                    expectedLength = artifact.byteLength,
                    expectedSha256 = artifact.sha256.hexToBytes(),
                    query = queryVector,
                    k = minOf(MaximumNativeCandidates, artifact.recordCount),
                    channel = VectorSegmentChannel.OCR_SEMANTIC,
                    embeddingSpaceHash = generation.embeddingSpaceHash.hexToBytes(),
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
        }

        val orderedCandidates = candidates.toList().sortedWith(CandidateOrder)
        if (orderedCandidates.isEmpty()) {
            return ready(
                snapshot.snapshotId,
                manifestRevision,
                snapshot.lexicalPublicationEpoch,
                lease.accessRevision,
                snapshot.packManifestSha256,
                emptyList(),
            )
        }
        val evidence =
            vectors.currentSemanticEvidence(
                manifestRevision = manifestRevision,
                recordIds = orderedCandidates.map { it.hit.recordId }.distinct(),
                semanticPipelineVersion = generation.pipelineVersion,
                componentHash = generation.componentHash,
                maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
            )
        val evidenceByRecord = evidence.associateBy { row -> RecordKey(row.segmentSha256, row.recordId) }
        check(evidenceByRecord.size == evidence.size)
        val selected =
            orderedCandidates.mapNotNull { candidate ->
                val row = evidenceByRecord[RecordKey(candidate.segmentSha256, candidate.hit.recordId)]
                    ?: return@mapNotNull null
                check(row.assetId == candidate.hit.assetId && row.chunkOrdinal == candidate.hit.ordinal)
                CandidateEvidence(candidate, row)
            }.distinctBy { it.evidence.chunkId }
                .distinctBy { it.evidence.assetId }
                .take(limit)
        val currentAccess = vectors.accessObservation()
        if (
            currentAccess == null ||
            currentAccess.accessScope == "None" ||
            currentAccess.processAccessRevision != lease.accessRevision
        ) {
            return ready(
                snapshot.snapshotId,
                manifestRevision,
                snapshot.lexicalPublicationEpoch,
                lease.accessRevision,
                snapshot.packManifestSha256,
                emptyList(),
            )
        }
        val hits = selected.mapIndexed { index, selectedHit ->
            val row = selectedHit.evidence
            val lineOrdinals = semantic.chunkLines(row.chunkId).map { line -> line.lineOrdinal }
            check(
                lineOrdinals.isNotEmpty() &&
                    lineOrdinals.first() == row.firstLineOrdinal &&
                    lineOrdinals.last() == row.lastLineOrdinal,
            )
            OcrSemanticHit(
                assetId = row.assetId,
                rank = index + 1,
                rawScore = selectedHit.candidate.hit.score,
                similarityMicros = similarityMicros(selectedHit.candidate.hit.score, generation.dimension),
                displaySnippet = row.displayText.take(MaximumSnippetCharacters),
                semanticChunkId = row.chunkId,
                matchedLineOrdinals = lineOrdinals,
                publicationEpoch = row.publicationEpoch,
            )
        }
        return ready(
            snapshot.snapshotId,
            manifestRevision,
            snapshot.lexicalPublicationEpoch,
            lease.accessRevision,
            snapshot.packManifestSha256,
            hits,
        )
    }

    private fun PriorityQueue<NativeCandidate>.retain(candidate: NativeCandidate) {
        if (size < MaximumNativeCandidates) {
            add(candidate)
        } else if (CandidateOrder.compare(candidate, peek()) < 0) {
            remove()
            add(candidate)
        }
    }

    private fun safeArtifactPath(relativePath: String): Path {
        check(!Files.isSymbolicLink(root))
        val candidate = root.resolve(relativePath).normalize()
        check(candidate.startsWith(root) && candidate != root && !Files.isSymbolicLink(candidate))
        var parent = candidate.parent
        while (parent != null && parent != root) {
            check(!Files.isSymbolicLink(parent))
            parent = parent.parent
        }
        check(parent == root)
        return candidate
    }

    private fun ready(
        snapshotId: String,
        manifestRevision: String,
        lexicalPublicationEpoch: Long,
        accessRevision: Long,
        componentHash: String,
        hits: List<OcrSemanticHit>,
    ) = OcrSemanticSearchResult(
        status = OcrSemanticSearchStatus.READY,
        snapshotId = snapshotId,
        manifestRevision = manifestRevision,
        lexicalPublicationEpoch = lexicalPublicationEpoch,
        accessRevision = accessRevision,
        componentHash = componentHash,
        hits = hits,
    )

    private fun String.hexToBytes(): ByteArray {
        check(Sha256.matches(this))
        return ByteArray(32) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun similarityMicros(score: Int, dimension: Int): Int {
        val maximumDot = dimension.toDouble() * QuantizedMaximum * QuantizedMaximum
        return (score.toDouble() * SimilarityScale / maximumDot).roundToInt()
            .coerceIn(-SimilarityScale, SimilarityScale)
    }

    private data class NativeCandidate(
        val segmentSha256: String,
        val manifestOrdinal: Int,
        val hit: NativeVectorSearchHit,
    )

    private data class RecordKey(val segmentSha256: String, val recordId: Long)

    private data class CandidateEvidence(
        val candidate: NativeCandidate,
        val evidence: SemanticVectorEvidence,
    )

    companion object {
        const val DefaultLimit = 50
        const val MaximumResultLimit = 100
        private const val MaximumNativeCandidates = 512
        private const val MaximumQueryCharacters = 1_024
        private const val MaximumSnippetCharacters = 240
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
