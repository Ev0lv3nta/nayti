package app.nayti.search.engine.similarity

data class PerceptualHashRecord(
    val assetId: Long,
    val hashBits: Long,
    val publicationEpoch: Long,
)

data class PerceptualHashMatch(
    val assetId: Long,
    val distance: Int,
    val publicationEpoch: Long,
)

/** Exact bounded scan for duplicates and near-duplicates over the current pHash catalog. */
object PerceptualHashRanker {
    const val ExactDuplicateDistance = 0
    const val DefaultLimit = 100
    const val MaximumLimit = 500

    fun rank(
        sourceAssetId: Long,
        sourceHash: Long,
        records: List<PerceptualHashRecord>,
        maximumDistance: Int = PerceptualHashV1.DefaultNearDuplicateDistance,
        limit: Int = DefaultLimit,
    ): List<PerceptualHashMatch> {
        require(sourceAssetId > 0)
        require(maximumDistance in 0..63)
        require(limit in 1..MaximumLimit)
        require(records.all { it.assetId > 0 && it.publicationEpoch > 0 })
        require(records.map(PerceptualHashRecord::assetId).distinct().size == records.size)

        return records.asSequence()
            .filter { it.assetId != sourceAssetId }
            .map { record ->
                PerceptualHashMatch(
                    assetId = record.assetId,
                    distance = PerceptualHashV1.hammingDistance(sourceHash, record.hashBits),
                    publicationEpoch = record.publicationEpoch,
                )
            }.filter { it.distance <= maximumDistance }
            .sortedWith(compareBy(PerceptualHashMatch::distance, PerceptualHashMatch::assetId))
            .take(limit)
            .toList()
    }
}
