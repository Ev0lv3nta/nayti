package app.nayti.storage

/** Current OCR evidence for one native vector candidate in a leased semantic manifest. */
data class SemanticVectorEvidence(
    val segmentSha256: String,
    val recordId: Long,
    val assetId: Long,
    val chunkId: String,
    val chunkOrdinal: Int,
    val displayText: String,
    val meanConfidenceMicros: Int,
    val firstLineOrdinal: Int,
    val lastLineOrdinal: Int,
    val publicationEpoch: Long,
)
