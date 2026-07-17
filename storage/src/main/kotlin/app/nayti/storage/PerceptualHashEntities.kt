package app.nayti.storage

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "perceptual_hash_result",
    primaryKeys = ["assetId", "publicationEpoch"],
    indices = [
        Index(value = ["assetId", "sourceFingerprint"]),
        Index(value = ["pipelineVersion", "componentHash", "publicationEpoch"]),
    ],
)
data class PerceptualHashEntity(
    val assetId: Long,
    val sourceFingerprint: String,
    val accessRevision: Long,
    val pipelineVersion: String,
    val componentHash: String,
    val hashBits: Long,
    val publicationEpoch: Long,
    val createdAtMillis: Long,
)

data class CurrentPerceptualHash(
    val assetId: Long,
    val hashBits: Long,
    val publicationEpoch: Long,
)

data class PerceptualHashDraft(
    val assetId: Long,
    val sourceFingerprint: String,
    val accessRevision: Long,
    val pipelineVersion: String,
    val componentHash: String,
    val hashBits: Long,
)
