package app.nayti.indexer

import android.system.Os
import android.system.OsConstants
import app.nayti.search.engine.NativeVectorIndex
import app.nayti.search.engine.VectorManifestSegment
import app.nayti.search.engine.VectorManifestV1
import app.nayti.search.engine.VectorSegmentChannel
import app.nayti.search.engine.VectorSegmentRecord
import app.nayti.search.engine.VectorSegmentV1Writer
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.VectorIndexDao
import app.nayti.storage.VectorManifestEntity
import app.nayti.storage.VectorManifestSegmentEntity
import app.nayti.storage.VectorPublicationEntity
import app.nayti.storage.VectorPublicationState
import app.nayti.storage.VectorSegmentArtifactEntity
import app.nayti.storage.VectorSegmentRecordEntity
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class PublishedVectorRecord(
    val recordId: Long,
    val assetId: Long,
    val chunkOrdinal: Int,
    val sourceFingerprint: String,
    val vector: ByteArray,
)

data class VectorPublicationRequest(
    val publicationToken: String,
    val generationId: String,
    val manifestRevision: String,
    val snapshotId: String,
    val leaseTokens: List<String>,
    val records: List<PublishedVectorRecord>,
    val rankingConfigVersion: String,
    val lexicalPublicationEpoch: Long,
    val pHashPublicationEpoch: Long,
    val catalogWatermark: Long,
    val segmentId: UUID = UUID.randomUUID(),
)

enum class VectorPublicationBoundary {
    AFTER_SEGMENT_FSYNC,
    AFTER_SEGMENT_RENAME,
    AFTER_DB_STAGE,
    AFTER_MANIFEST_FSYNC,
    AFTER_MANIFEST_RENAME,
    BEFORE_DB_COMMIT,
    AFTER_DB_COMMIT,
}

class VectorPublicationStore(
    rootDirectory: File,
    private val dao: VectorIndexDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val boundaryObserver: (VectorPublicationBoundary) -> Unit = {},
) {
    private val root = rootDirectory.canonicalFile
    private val stagingDirectory = root.resolve("staging")
    private val segmentDirectory = root.resolve("segments")
    private val manifestDirectory = root.resolve("manifests")

    init {
        listOf(root, stagingDirectory, segmentDirectory, manifestDirectory).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory)
        }
    }

    suspend fun publish(request: VectorPublicationRequest): ActivationSnapshotEntity {
        require(request.records.isNotEmpty())
        require(request.leaseTokens.size == request.records.map(PublishedVectorRecord::assetId).distinct().size)
        val generation = checkNotNull(dao.generation(request.generationId))
        val channel = generation.channel.toSegmentChannel()
        val encodedSegment = VectorSegmentV1Writer.encode(
            channel = channel,
            embeddingSpaceHash = generation.embeddingSpaceHash,
            records = request.records.map { record ->
                VectorSegmentRecord(
                    recordId = record.recordId,
                    assetId = record.assetId,
                    ordinal = record.chunkOrdinal,
                    vector = record.vector,
                )
            },
            segmentId = request.segmentId,
        )
        check(encodedSegment.dimension == generation.dimension)

        val segmentRelativePath = "segments/${encodedSegment.sha256}.naytivec"
        val segmentFile = root.resolveChecked(segmentRelativePath)
        val segmentTemp = uniqueTemp(request.publicationToken, "segment")
        writeAndSync(segmentTemp, encodedSegment.bytes)
        boundaryObserver(VectorPublicationBoundary.AFTER_SEGMENT_FSYNC)
        seal(segmentTemp, segmentFile, encodedSegment.bytes.size.toLong(), encodedSegment.sha256)
        boundaryObserver(VectorPublicationBoundary.AFTER_SEGMENT_RENAME)
        check(
            NativeVectorIndex.mappedRecordCount(
                path = segmentFile.absolutePath,
                expectedLength = encodedSegment.bytes.size.toLong(),
                expectedSha256 = decodeSha256(encodedSegment.sha256),
            ) == request.records.size,
        )

        val stagedAt = nowMillis()
        val publication = VectorPublicationEntity(
            publicationToken = request.publicationToken,
            state = VectorPublicationState.STAGED,
            channel = generation.channel,
            generationId = generation.generationId,
            segmentSha256 = encodedSegment.sha256,
            manifestRevision = request.manifestRevision,
            snapshotId = request.snapshotId,
            createdAtMillis = stagedAt,
            updatedAtMillis = stagedAt,
        )
        dao.stageVectorPublication(
            publication = publication,
            leaseTokens = request.leaseTokens,
            artifactPath = segmentRelativePath,
            artifactLength = encodedSegment.bytes.size.toLong(),
            artifactSha256 = encodedSegment.sha256,
            nowMillis = stagedAt,
        )
        boundaryObserver(VectorPublicationBoundary.AFTER_DB_STAGE)

        val parentSnapshotId = dao.activeSnapshotId()
        val parentSnapshot = parentSnapshotId?.let { checkNotNull(dao.snapshot(it)) }
        val parentManifestRevision = when (generation.channel) {
            IndexChannel.VISUAL -> parentSnapshot?.visualManifestRevision
            IndexChannel.OCR_SEMANTIC -> parentSnapshot?.semanticManifestRevision
            else -> error("Unsupported vector channel ${generation.channel}")
        }
        val previousEntries = parentManifestRevision?.let { revision ->
            val parentManifest = checkNotNull(dao.manifest(revision))
            check(parentManifest.generationId == generation.generationId)
            dao.manifestSegments(revision)
        }.orEmpty()
        val existingDescriptors = previousEntries.map { entry ->
            val artifact = checkNotNull(dao.segment(entry.segmentSha256))
            VectorManifestSegment(
                relativePath = artifact.relativePath,
                byteLength = artifact.byteLength,
                sha256 = artifact.sha256,
                recordCount = artifact.recordCount,
            )
        }
        val newDescriptor = VectorManifestSegment(
            relativePath = segmentRelativePath,
            byteLength = encodedSegment.bytes.size.toLong(),
            sha256 = encodedSegment.sha256,
            recordCount = request.records.size,
        )
        val encodedManifest = VectorManifestV1.encode(
            revision = request.manifestRevision,
            generationId = generation.generationId,
            parentRevision = parentManifestRevision,
            channel = channel,
            embeddingSpaceHash = generation.embeddingSpaceHash,
            dimension = generation.dimension,
            segments = existingDescriptors + newDescriptor,
        )
        val manifestRelativePath = "manifests/${encodedManifest.sha256}.naytimanifest"
        val manifestFile = root.resolveChecked(manifestRelativePath)
        val manifestTemp = uniqueTemp(request.publicationToken, "manifest")
        writeAndSync(manifestTemp, encodedManifest.bytes)
        boundaryObserver(VectorPublicationBoundary.AFTER_MANIFEST_FSYNC)
        seal(manifestTemp, manifestFile, encodedManifest.bytes.size.toLong(), encodedManifest.sha256)
        boundaryObserver(VectorPublicationBoundary.AFTER_MANIFEST_RENAME)

        val segment = VectorSegmentArtifactEntity(
            sha256 = encodedSegment.sha256,
            segmentId = encodedSegment.segmentId.toString(),
            relativePath = segmentRelativePath,
            byteLength = encodedSegment.bytes.size.toLong(),
            formatVersion = VectorSegmentV1Writer.FormatVersion,
            channel = generation.channel,
            embeddingSpaceHash = generation.embeddingSpaceHash,
            dimension = generation.dimension,
            recordCount = request.records.size,
            createdAtMillis = stagedAt,
        )
        val recordEntities = request.records.mapIndexed { ordinal, record ->
            VectorSegmentRecordEntity(
                segmentSha256 = encodedSegment.sha256,
                ordinal = ordinal,
                recordId = record.recordId,
                assetId = record.assetId,
                sourceFingerprint = record.sourceFingerprint,
                chunkOrdinal = record.chunkOrdinal,
            )
        }
        val manifest = VectorManifestEntity(
            revision = request.manifestRevision,
            generationId = generation.generationId,
            parentRevision = parentManifestRevision,
            channel = generation.channel,
            relativePath = manifestRelativePath,
            byteLength = encodedManifest.bytes.size.toLong(),
            sha256 = encodedManifest.sha256,
            segmentCount = previousEntries.size + 1,
            recordCount = encodedManifest.recordCount,
            createdAtMillis = stagedAt,
        )
        val manifestEntries = buildList {
            previousEntries.forEachIndexed { ordinal, entry ->
                add(VectorManifestSegmentEntity(request.manifestRevision, ordinal, entry.segmentSha256))
            }
            add(
                VectorManifestSegmentEntity(
                    manifestRevision = request.manifestRevision,
                    ordinal = previousEntries.size,
                    segmentSha256 = encodedSegment.sha256,
                ),
            )
        }
        val pack = checkNotNull(dao.modelPack(generation.packId, generation.packVersion))
        val snapshot = ActivationSnapshotEntity(
            snapshotId = request.snapshotId,
            parentSnapshotId = parentSnapshotId,
            packId = generation.packId,
            packVersion = generation.packVersion,
            packManifestSha256 = pack.manifestSha256,
            engineContractVersion = NativeVectorIndex.contractVersion(),
            rankingConfigVersion = request.rankingConfigVersion,
            lexicalPublicationEpoch = request.lexicalPublicationEpoch,
            pHashPublicationEpoch = request.pHashPublicationEpoch,
            semanticManifestRevision = if (generation.channel == IndexChannel.OCR_SEMANTIC) request.manifestRevision else parentSnapshot?.semanticManifestRevision,
            visualManifestRevision = if (generation.channel == IndexChannel.VISUAL) request.manifestRevision else parentSnapshot?.visualManifestRevision,
            catalogWatermark = request.catalogWatermark,
            createdAtMillis = stagedAt,
        )
        boundaryObserver(VectorPublicationBoundary.BEFORE_DB_COMMIT)
        val committed = dao.commitVectorPublication(
            publicationToken = request.publicationToken,
            segment = segment,
            records = recordEntities,
            manifest = manifest,
            manifestEntries = manifestEntries,
            snapshot = snapshot,
            nowMillis = nowMillis(),
        )
        boundaryObserver(VectorPublicationBoundary.AFTER_DB_COMMIT)
        return committed
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            channel.force(true)
        }
    }

    private fun seal(temp: File, target: File, expectedLength: Long, expectedSha256: String) {
        if (target.exists()) {
            check(target.isFile && target.length() == expectedLength)
            check(sha256Hex(target) == expectedSha256)
            check(temp.delete())
        } else {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        syncDirectory(stagingDirectory)
        syncDirectory(target.parentFile!!)
        check(target.setReadOnly())
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun uniqueTemp(token: String, role: String): File {
        require(Token.matches(token))
        return stagingDirectory.resolve("$token-${UUID.randomUUID()}.$role.tmp")
    }

    private fun File.resolveChecked(relativePath: String): File {
        val resolved = resolve(relativePath).canonicalFile
        check(resolved.toPath().startsWith(toPath()))
        return resolved
    }

    private fun sha256Hex(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun decodeSha256(value: String): ByteArray =
        ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun String.toSegmentChannel(): VectorSegmentChannel = when (this) {
        IndexChannel.VISUAL -> VectorSegmentChannel.VISUAL
        IndexChannel.OCR_SEMANTIC -> VectorSegmentChannel.OCR_SEMANTIC
        else -> error("Unsupported vector channel $this")
    }

    private companion object {
        val Token = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    }
}
