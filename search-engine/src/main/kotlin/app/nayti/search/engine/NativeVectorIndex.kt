package app.nayti.search.engine

data class NativeVectorSearchHit(
    val recordId: Long,
    val assetId: Long,
    val ordinal: Int,
    val score: Int,
)

object NativeVectorIndex {
    private const val Sha256ByteCount = 32
    private const val PackedHitFieldCount = 4
    private const val MaximumTopK = 512

    init {
        System.loadLibrary("nayti_search")
    }

    external fun contractVersion(): Int

    external fun optimizedDotMatchesScalar(seed: Int, cases: Int): Boolean

    external fun mappedRecordCount(path: String, expectedLength: Long, expectedSha256: ByteArray): Int

    fun exactTopK(
        path: String,
        expectedLength: Long,
        expectedSha256: ByteArray,
        query: ByteArray,
        k: Int,
        channel: VectorSegmentChannel,
        embeddingSpaceHash: ByteArray,
        eligibleRecordIds: LongArray,
    ): List<NativeVectorSearchHit> {
        require(path.isNotBlank())
        require(expectedLength > 0)
        require(expectedSha256.size == Sha256ByteCount)
        require(query.size in 1..VectorSegmentV1Writer.MaximumDimension)
        require(k in 1..MaximumTopK)
        require(embeddingSpaceHash.size == Sha256ByteCount && embeddingSpaceHash.any { it != 0.toByte() })
        require(eligibleRecordIds.isNotEmpty() && eligibleRecordIds.size <= VectorSegmentV1Writer.MaximumRecordCount)
        require(k <= eligibleRecordIds.size)
        require(
            eligibleRecordIds.all { it > 0 } &&
                (1 until eligibleRecordIds.size).all { index ->
                    eligibleRecordIds[index - 1] < eligibleRecordIds[index]
                },
        )

        val packed = checkNotNull(
            exactTopKPacked(
                path = path,
                expectedLength = expectedLength,
                expectedSha256 = expectedSha256,
                query = query,
                k = k,
                channelCode = channel.code.toInt(),
                embeddingSpaceHash = embeddingSpaceHash,
                eligibleRecordIds = eligibleRecordIds,
            ),
        ) { "Native exact top-K scan rejected the segment or query contract" }
        check(packed.size % PackedHitFieldCount == 0)

        return packed.asList().chunked(PackedHitFieldCount).map { fields ->
            val recordId = fields[0]
            val assetId = fields[1]
            val ordinal = fields[2]
            val score = fields[3]
            check(recordId > 0 && assetId > 0)
            check(ordinal in 0..Int.MAX_VALUE.toLong())
            check(score in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            NativeVectorSearchHit(
                recordId = recordId,
                assetId = assetId,
                ordinal = ordinal.toInt(),
                score = score.toInt(),
            )
        }.also { hits ->
            check(hits.size <= k)
            check(
                hits.zipWithNext().all { (left, right) ->
                    left.score > right.score ||
                        (left.score == right.score && left.recordId < right.recordId)
                },
            )
        }
    }

    private external fun exactTopKPacked(
        path: String,
        expectedLength: Long,
        expectedSha256: ByteArray,
        query: ByteArray,
        k: Int,
        channelCode: Int,
        embeddingSpaceHash: ByteArray,
        eligibleRecordIds: LongArray,
    ): LongArray?
}
