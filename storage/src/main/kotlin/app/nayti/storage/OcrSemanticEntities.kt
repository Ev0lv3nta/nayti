package app.nayti.storage

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "ocr_semantic_chunk_set",
    indices = [
        Index(value = ["ocrPublicationToken", "chunkingVersion"], unique = true),
        Index(value = ["assetId", "sourceFingerprint"]),
    ],
)
data class OcrSemanticChunkSetEntity(
    @PrimaryKey val chunkSetId: String,
    val assetId: Long,
    val sourceFingerprint: String,
    val ocrPublicationToken: String,
    val chunkingVersion: String,
    val chunkCount: Int,
    val payloadSha256: String,
    val payloadByteLength: Long,
    val createdAtMillis: Long,
)

/** Immutable semantic input derived from one exact OCR publication. */
@Entity(
    tableName = "ocr_semantic_chunk",
    indices = [
        Index(value = ["chunkSetId", "ordinal"], unique = true),
        Index(value = ["assetId", "sourceFingerprint"]),
        Index(value = ["ocrPublicationToken"]),
    ],
)
data class OcrSemanticChunkEntity(
    @PrimaryKey val chunkId: String,
    val chunkSetId: String,
    val assetId: Long,
    val sourceFingerprint: String,
    val ocrPublicationToken: String,
    val ordinal: Int,
    val kind: String,
    val displayText: String,
    val contentTokenCount: Int,
    val firstLineOrdinal: Int,
    val lastLineOrdinal: Int,
    val meanConfidenceMicros: Int,
    val reliableAlphabeticWordCount: Int,
    val chunkingVersion: String,
    val createdAtMillis: Long,
)

/** Ordered line provenance kept separately so chunk text never has to encode structural metadata. */
@Entity(
    tableName = "ocr_semantic_chunk_line",
    primaryKeys = ["chunkId", "position"],
    indices = [
        Index(value = ["chunkId", "lineOrdinal"], unique = true),
        Index(value = ["assetId", "lineOrdinal"]),
    ],
)
data class OcrSemanticChunkLineEntity(
    val chunkId: String,
    val position: Int,
    val assetId: Long,
    val lineOrdinal: Int,
)
