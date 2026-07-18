package app.nayti.storage

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "vector_generation",
    primaryKeys = ["generationId"],
    indices = [
        Index(value = ["channel", "state"]),
        Index(value = ["packId", "packVersion"]),
    ],
)
data class VectorGenerationEntity(
    val generationId: String,
    val channel: String,
    val packId: String,
    val packVersion: String,
    val pipelineVersion: String,
    val componentHash: String,
    val embeddingSpaceHash: String,
    val dimension: Int,
    val state: String,
    val createdAtMillis: Long,
    val sealedAtMillis: Long?,
)

@Entity(
    tableName = "vector_segment_artifact",
    primaryKeys = ["sha256"],
    indices = [
        Index(value = ["segmentId"], unique = true),
        Index(value = ["relativePath"], unique = true),
        Index(value = ["channel", "embeddingSpaceHash"]),
    ],
)
data class VectorSegmentArtifactEntity(
    val sha256: String,
    val segmentId: String,
    val relativePath: String,
    val byteLength: Long,
    val formatVersion: Int,
    val channel: String,
    val embeddingSpaceHash: String,
    val dimension: Int,
    val recordCount: Int,
    val createdAtMillis: Long,
    val compactionLevel: Int = 0,
)

@Entity(
    tableName = "vector_segment_record",
    primaryKeys = ["segmentSha256", "ordinal"],
    indices = [
        Index(value = ["segmentSha256", "recordId"], unique = true),
        Index(value = ["assetId"]),
        Index(value = ["semanticChunkId"]),
    ],
)
data class VectorSegmentRecordEntity(
    val segmentSha256: String,
    val ordinal: Int,
    val recordId: Long,
    val assetId: Long,
    val sourceFingerprint: String,
    val chunkOrdinal: Int,
    val semanticChunkId: String? = null,
)

@Entity(
    tableName = "vector_manifest",
    primaryKeys = ["revision"],
    indices = [
        Index(value = ["relativePath"], unique = true),
        Index(value = ["sha256"], unique = true),
        Index(value = ["generationId"]),
    ],
)
data class VectorManifestEntity(
    val revision: String,
    val generationId: String,
    val parentRevision: String?,
    val channel: String,
    val relativePath: String,
    val byteLength: Long,
    val sha256: String,
    val segmentCount: Int,
    val recordCount: Long,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "vector_manifest_segment",
    primaryKeys = ["manifestRevision", "ordinal"],
    indices = [
        Index(value = ["manifestRevision", "segmentSha256"], unique = true),
        Index(value = ["segmentSha256"]),
    ],
)
data class VectorManifestSegmentEntity(
    val manifestRevision: String,
    val ordinal: Int,
    val segmentSha256: String,
)

@Entity(
    tableName = "activation_snapshot",
    primaryKeys = ["snapshotId"],
    indices = [
        Index(value = ["parentSnapshotId"]),
        Index(value = ["semanticManifestRevision"]),
        Index(value = ["visualManifestRevision"]),
        Index(value = ["packId", "packVersion"]),
    ],
)
data class ActivationSnapshotEntity(
    val snapshotId: String,
    val parentSnapshotId: String?,
    val packId: String,
    val packVersion: String,
    val packManifestSha256: String,
    val engineContractVersion: Int,
    val rankingConfigVersion: String,
    val lexicalPublicationEpoch: Long,
    val pHashPublicationEpoch: Long,
    val semanticManifestRevision: String?,
    val visualManifestRevision: String?,
    val catalogWatermark: Long,
    val createdAtMillis: Long,
    val formatVersion: Int = ActivationSnapshotFormat.Current,
    val capturedAccessRevision: Long = 0,
)

@Entity(tableName = "active_snapshot_pointer", primaryKeys = ["singletonId"])
data class ActiveSnapshotPointerEntity(
    val singletonId: Int = 1,
    val snapshotId: String?,
    val rollbackSnapshotId: String? = null,
    val activationSequence: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Entity(
    tableName = "activation_candidate",
    primaryKeys = ["candidateId"],
    indices = [
        Index(value = ["snapshotId"], unique = true),
        Index(value = ["state", "updatedAtMillis"]),
        Index(value = ["packId", "packVersion"]),
        Index(value = ["parentSnapshotId"]),
    ],
)
data class ActivationCandidateEntity(
    val candidateId: String,
    val snapshotId: String,
    val parentSnapshotId: String?,
    val packId: String,
    val packVersion: String,
    val packManifestSha256: String,
    val capturedAccessRevision: Long,
    val capturedCatalogWatermark: Long,
    val state: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val failureCode: String?,
)

@Entity(
    tableName = "query_snapshot_lease",
    primaryKeys = ["leaseToken"],
    indices = [Index(value = ["snapshotId", "expiresAtMillis"])],
)
data class QuerySnapshotLeaseEntity(
    val leaseToken: String,
    val snapshotId: String,
    val accessRevision: Long,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
)

@Entity(
    tableName = "vector_publication",
    primaryKeys = ["publicationToken"],
    indices = [
        Index(value = ["state", "updatedAtMillis"]),
        Index(value = ["generationId"]),
        Index(value = ["manifestRevision"], unique = true),
        Index(value = ["snapshotId"], unique = true),
    ],
)
data class VectorPublicationEntity(
    val publicationToken: String,
    val state: String,
    val channel: String,
    val generationId: String,
    val segmentSha256: String,
    val manifestRevision: String,
    val snapshotId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "artifact_delete_intent",
    primaryKeys = ["relativePath"],
    indices = [Index(value = ["ownerSnapshotId", "state"])],
)
data class ArtifactDeleteIntentEntity(
    val relativePath: String,
    val ownerSnapshotId: String,
    val expectedSha256: String,
    val state: String,
    val createdAtMillis: Long,
)

object VectorGenerationState {
    const val BUILDING = "BUILDING"
    const val SEALED = "SEALED"
    const val RETIRED = "RETIRED"
}

object VectorPublicationState {
    const val STAGED = "STAGED"
    const val DONE = "DONE"
    const val ABANDONED = "ABANDONED"
}

object ArtifactDeleteState {
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
}

object ActivationSnapshotFormat {
    const val Current = 1
}

object ActivationCandidateState {
    const val BUILDING_SHADOW = "BUILDING_SHADOW"
    const val READY_TO_ACTIVATE = "READY_TO_ACTIVATE"
    const val ACTIVE = "ACTIVE"
    const val ROLLED_BACK = "ROLLED_BACK"
    const val REJECTED = "REJECTED"
}
