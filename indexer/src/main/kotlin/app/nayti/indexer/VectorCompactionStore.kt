package app.nayti.indexer

import app.nayti.search.engine.NativeVectorIndex
import app.nayti.search.engine.VectorManifestSegment
import app.nayti.search.engine.VectorManifestV1
import app.nayti.search.engine.VectorSegmentRecord
import app.nayti.search.engine.VectorSegmentV1Reader
import app.nayti.search.engine.VectorSegmentV1Writer
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.VectorIndexDao
import app.nayti.storage.VectorManifestEntity
import app.nayti.storage.VectorManifestSegmentEntity
import app.nayti.storage.VectorSegmentArtifactEntity
import app.nayti.storage.VectorSegmentRecordEntity
import java.io.File
import java.util.UUID

data class VectorCompactionRequest(
    val compactionToken: String,
    val generationId: String,
    val firstSegmentOrdinal: Int,
    val segmentCount: Int,
    val manifestRevision: String,
    val snapshotId: String,
    val rankingConfigVersion: String,
    val lexicalPublicationEpoch: Long,
    val pHashPublicationEpoch: Long,
    val catalogWatermark: Long,
    val segmentId: UUID = UUID.randomUUID(),
)

enum class VectorCompactionBoundary {
    AFTER_SEGMENT_FSYNC,
    AFTER_SEGMENT_RENAME,
    AFTER_MANIFEST_FSYNC,
    AFTER_MANIFEST_RENAME,
    BEFORE_DB_COMMIT,
    AFTER_DB_COMMIT,
}

class VectorCompactionStore(
    rootDirectory: File,
    private val dao: VectorIndexDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val boundaryObserver: (VectorCompactionBoundary) -> Unit = {},
    fileFaultInjector: (VectorArtifactRole, VectorFileOperation) -> Unit = { _, _ -> },
) {
    private val files = ImmutableVectorFiles(rootDirectory, fileFaultInjector)

    suspend fun compact(request: VectorCompactionRequest): ActivationSnapshotEntity {
        require(request.firstSegmentOrdinal >= 0)
        require(request.segmentCount >= 2)
        val generation = checkNotNull(dao.generation(request.generationId))
        val activeId = checkNotNull(dao.activeSnapshotId())
        val active = checkNotNull(dao.snapshot(activeId))
        val activeRevision = when (generation.channel) {
            IndexChannel.VISUAL -> active.visualManifestRevision
            IndexChannel.OCR_SEMANTIC -> active.semanticManifestRevision
            else -> error("Unsupported vector channel ${generation.channel}")
        }
        val parentManifest = checkNotNull(activeRevision?.let { dao.manifest(it) })
        check(parentManifest.generationId == generation.generationId)
        val parentEntries = dao.manifestSegments(parentManifest.revision)
        val endExclusive = Math.addExact(request.firstSegmentOrdinal, request.segmentCount)
        require(endExclusive <= parentEntries.size)
        val selectedEntries = parentEntries.subList(request.firstSegmentOrdinal, endExclusive)
        val selectedArtifacts = selectedEntries.map { entry -> checkNotNull(dao.segment(entry.segmentSha256)) }
        check(selectedArtifacts.map { it.compactionLevel }.distinct().size == 1)
        val selectedRecordCount = selectedArtifacts.sumOf { artifact -> artifact.recordCount }
        require(selectedRecordCount in 1..VectorSegmentV1Writer.MaximumRecordCount)

        val compactedRecords = mutableListOf<PublishedVectorRecord>()
        selectedArtifacts.forEach { artifact ->
            check(artifact.channel == generation.channel)
            check(artifact.embeddingSpaceHash == generation.embeddingSpaceHash)
            check(artifact.dimension == generation.dimension)
            val bytes = files.readVerified(artifact.relativePath, artifact.byteLength, artifact.sha256)
            check(
                NativeVectorIndex.mappedRecordCount(
                    path = files.root.resolve(artifact.relativePath).absolutePath,
                    expectedLength = artifact.byteLength,
                    expectedSha256 = decodeSha256(artifact.sha256),
                ) == artifact.recordCount,
            )
            val decoded = VectorSegmentV1Reader.decode(bytes)
            check(decoded.channel == generation.channel.toSegmentChannel())
            check(decoded.embeddingSpaceHash == generation.embeddingSpaceHash)
            check(decoded.dimension == generation.dimension)
            val metadata = dao.segmentRecords(artifact.sha256)
            check(metadata.size == decoded.records.size)
            decoded.records.forEachIndexed { ordinal, record ->
                val stored = metadata[ordinal]
                check(stored.ordinal == ordinal)
                check(stored.recordId == record.recordId)
                check(stored.assetId == record.assetId)
                check(stored.chunkOrdinal == record.ordinal)
                compactedRecords += PublishedVectorRecord(
                    recordId = record.recordId,
                    assetId = record.assetId,
                    chunkOrdinal = record.ordinal,
                    sourceFingerprint = stored.sourceFingerprint,
                    vector = record.vector,
                    semanticChunkId = stored.semanticChunkId,
                )
            }
        }
        check(compactedRecords.size == selectedRecordCount)
        val encodedSegment = VectorSegmentV1Writer.encode(
            channel = generation.channel.toSegmentChannel(),
            embeddingSpaceHash = generation.embeddingSpaceHash,
            records = compactedRecords.map { record ->
                VectorSegmentRecord(record.recordId, record.assetId, record.chunkOrdinal, record.vector)
            },
            segmentId = request.segmentId,
        )
        val sealedSegment = files.sealSegment(
            token = request.compactionToken,
            bytes = encodedSegment.bytes,
            sha256 = encodedSegment.sha256,
            afterFsync = { boundaryObserver(VectorCompactionBoundary.AFTER_SEGMENT_FSYNC) },
            afterRename = { boundaryObserver(VectorCompactionBoundary.AFTER_SEGMENT_RENAME) },
        )

        val resultingArtifacts = buildList {
            parentEntries.take(request.firstSegmentOrdinal).forEach { add(checkNotNull(dao.segment(it.segmentSha256))) }
            add(
                VectorSegmentArtifactEntity(
                    sha256 = encodedSegment.sha256,
                    segmentId = encodedSegment.segmentId.toString(),
                    relativePath = sealedSegment.relativePath,
                    byteLength = encodedSegment.bytes.size.toLong(),
                    formatVersion = VectorSegmentV1Writer.FormatVersion,
                    channel = generation.channel,
                    embeddingSpaceHash = generation.embeddingSpaceHash,
                    dimension = generation.dimension,
                    recordCount = compactedRecords.size,
                    createdAtMillis = nowMillis(),
                    compactionLevel = Math.incrementExact(selectedArtifacts.first().compactionLevel),
                ),
            )
            parentEntries.drop(endExclusive).forEach { add(checkNotNull(dao.segment(it.segmentSha256))) }
        }
        val encodedManifest = VectorManifestV1.encode(
            revision = request.manifestRevision,
            generationId = generation.generationId,
            parentRevision = parentManifest.revision,
            channel = generation.channel.toSegmentChannel(),
            embeddingSpaceHash = generation.embeddingSpaceHash,
            dimension = generation.dimension,
            segments = resultingArtifacts.map { artifact ->
                VectorManifestSegment(
                    relativePath = artifact.relativePath,
                    byteLength = artifact.byteLength,
                    sha256 = artifact.sha256,
                    recordCount = artifact.recordCount,
                )
            },
        )
        check(encodedManifest.recordCount == parentManifest.recordCount)
        val sealedManifest = files.sealManifest(
            token = request.compactionToken,
            bytes = encodedManifest.bytes,
            sha256 = encodedManifest.sha256,
            afterFsync = { boundaryObserver(VectorCompactionBoundary.AFTER_MANIFEST_FSYNC) },
            afterRename = { boundaryObserver(VectorCompactionBoundary.AFTER_MANIFEST_RENAME) },
        )

        val createdAt = nowMillis()
        val compactedArtifact = resultingArtifacts[request.firstSegmentOrdinal]
        val recordEntities = compactedRecords.mapIndexed { ordinal, record ->
            VectorSegmentRecordEntity(
                segmentSha256 = compactedArtifact.sha256,
                ordinal = ordinal,
                recordId = record.recordId,
                assetId = record.assetId,
                sourceFingerprint = record.sourceFingerprint,
                chunkOrdinal = record.chunkOrdinal,
                semanticChunkId = record.semanticChunkId,
            )
        }
        val manifest = VectorManifestEntity(
            revision = request.manifestRevision,
            generationId = generation.generationId,
            parentRevision = parentManifest.revision,
            channel = generation.channel,
            relativePath = sealedManifest.relativePath,
            byteLength = encodedManifest.bytes.size.toLong(),
            sha256 = encodedManifest.sha256,
            segmentCount = resultingArtifacts.size,
            recordCount = encodedManifest.recordCount,
            createdAtMillis = createdAt,
        )
        val entries = resultingArtifacts.mapIndexed { ordinal, artifact ->
            VectorManifestSegmentEntity(request.manifestRevision, ordinal, artifact.sha256)
        }
        val snapshot = active.copy(
            snapshotId = request.snapshotId,
            parentSnapshotId = active.snapshotId,
            rankingConfigVersion = request.rankingConfigVersion,
            lexicalPublicationEpoch = request.lexicalPublicationEpoch,
            pHashPublicationEpoch = request.pHashPublicationEpoch,
            semanticManifestRevision = if (generation.channel == IndexChannel.OCR_SEMANTIC) request.manifestRevision else active.semanticManifestRevision,
            visualManifestRevision = if (generation.channel == IndexChannel.VISUAL) request.manifestRevision else active.visualManifestRevision,
            catalogWatermark = request.catalogWatermark,
            createdAtMillis = createdAt,
        )
        boundaryObserver(VectorCompactionBoundary.BEFORE_DB_COMMIT)
        val committed = dao.commitVectorCompaction(
            generationId = generation.generationId,
            segment = compactedArtifact,
            records = recordEntities,
            manifest = manifest,
            manifestEntries = entries,
            candidateSnapshot = snapshot,
            nowMillis = nowMillis(),
        )
        boundaryObserver(VectorCompactionBoundary.AFTER_DB_COMMIT)
        return committed
    }

    private fun decodeSha256(value: String): ByteArray =
        ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun String.toSegmentChannel() = when (this) {
        IndexChannel.VISUAL -> app.nayti.search.engine.VectorSegmentChannel.VISUAL
        IndexChannel.OCR_SEMANTIC -> app.nayti.search.engine.VectorSegmentChannel.OCR_SEMANTIC
        else -> error("Unsupported vector channel $this")
    }
}
