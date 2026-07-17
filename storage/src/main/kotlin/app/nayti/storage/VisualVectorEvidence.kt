package app.nayti.storage

/** Current MediaStore evidence for one native candidate in a leased visual manifest. */
data class VisualVectorEvidence(
    val segmentSha256: String,
    val recordId: Long,
    val assetId: Long,
    val sourceFingerprint: String,
)
