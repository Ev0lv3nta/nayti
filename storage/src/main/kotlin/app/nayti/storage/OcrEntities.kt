package app.nayti.storage

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "ocr_document",
    indices = [
        Index(value = ["publicationToken"], unique = true),
        Index(value = ["publicationEpoch"]),
        Index(value = ["sourceFingerprint"]),
    ],
)
data class OcrDocumentEntity(
    @PrimaryKey val assetId: Long,
    val sourceFingerprint: String,
    val accessRevision: Long,
    val pipelineVersion: String,
    val componentHash: String,
    val publicationToken: String,
    val publicationEpoch: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rawText: String,
    val displayText: String,
    val canonicalText: String,
    val stemText: String,
    val identifierText: String,
    val hasRecognizedText: Boolean,
    val regionCount: Int,
    val normalizerVersion: String,
    val stemmerVersion: String,
    val identifierVersion: String,
    val publishedAtMillis: Long,
)

@Entity(
    tableName = "ocr_region",
    primaryKeys = ["assetId", "ordinal"],
    indices = [Index(value = ["assetId"])],
)
data class OcrRegionEntity(
    val assetId: Long,
    val ordinal: Int,
    val rawText: String,
    val displayText: String,
    val canonicalText: String,
    val confidenceMicros: Int,
    val x0Micros: Int,
    val y0Micros: Int,
    val x1Micros: Int,
    val y1Micros: Int,
    val x2Micros: Int,
    val y2Micros: Int,
    val x3Micros: Int,
    val y3Micros: Int,
)

@Entity(tableName = "ocr_lexical_fts")
@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
    prefix = [2, 3, 4],
)
data class OcrLexicalFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val assetId: Long,
    val canonical: String,
    val stems: String,
    val identifiers: String,
)

@Entity(tableName = "ocr_trigram_fts")
@Fts5(tokenizer = FtsOptions.TOKENIZER_TRIGRAM)
data class OcrTrigramFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val assetId: Long,
    val canonical: String,
)

data class OcrDocumentDraft(
    val assetId: Long,
    val sourceFingerprint: String,
    val accessRevision: Long,
    val pipelineVersion: String,
    val componentHash: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rawText: String,
    val displayText: String,
    val canonicalText: String,
    val stemText: String,
    val identifierText: String,
    val normalizerVersion: String,
    val stemmerVersion: String,
    val identifierVersion: String,
)

data class OcrRegionDraft(
    val rawText: String,
    val displayText: String,
    val canonicalText: String,
    val confidenceMicros: Int,
    val x0Micros: Int,
    val y0Micros: Int,
    val x1Micros: Int,
    val y1Micros: Int,
    val x2Micros: Int,
    val y2Micros: Int,
    val x3Micros: Int,
    val y3Micros: Int,
)

data class OcrPayloadIdentity(
    val sha256: String,
    val byteLength: Long,
)

data class OcrLexicalCandidate(
    val assetId: Long,
    val score: Double,
)
