package app.nayti.search.engine.fusion

enum class MultimodalPrimaryChannel {
    TEXT,
    VISUAL,
}

data class MultimodalTextCandidate(
    val assetId: Long,
    val rank: Int,
    val tier: Int,
)

data class MultimodalVisualCandidate(
    val assetId: Long,
    val rank: Int,
)

data class FusedMultimodalCandidate(
    val assetId: Long,
    val tier: Int,
    val primaryChannel: MultimodalPrimaryChannel,
    val textRank: Int?,
    val visualRank: Int?,
    val reciprocalRankScore: Long,
)

/** Preserves evidence tiers, then combines bounded rank lists without mixing raw model scores. */
object StrictMultimodalFusion {
    fun rank(
        intent: MultimodalQueryIntent,
        text: List<MultimodalTextCandidate>,
        visual: List<MultimodalVisualCandidate>,
        limit: Int,
    ): List<FusedMultimodalCandidate> {
        require(limit in 1..MaximumResults)
        require(text.size <= MaximumCandidates && visual.size <= MaximumCandidates)
        require(text.map { it.assetId }.distinct().size == text.size)
        require(visual.map { it.assetId }.distinct().size == visual.size)
        require(text.all { it.assetId > 0 && it.rank in 1..MaximumCandidates && it.tier in 0..VisualOnlyTier })
        require(visual.all { it.assetId > 0 && it.rank in 1..MaximumCandidates })
        val weights = weights(intent)
        val textByAsset = text.associateBy(MultimodalTextCandidate::assetId)
        val visualByAsset = visual.associateBy(MultimodalVisualCandidate::assetId)
        return (textByAsset.keys + visualByAsset.keys).mapNotNull { assetId ->
            val textCandidate = textByAsset[assetId]
            val visualCandidate = visualByAsset[assetId]
            val textContribution = reciprocalRank(weights.text, textCandidate?.rank)
            val visualContribution = reciprocalRank(weights.visual, visualCandidate?.rank)
            if (textContribution == 0L && visualContribution == 0L) return@mapNotNull null
            FusedMultimodalCandidate(
                assetId = assetId,
                tier = tier(intent, textCandidate, visualCandidate),
                primaryChannel =
                    if (visualContribution > textContribution) {
                        MultimodalPrimaryChannel.VISUAL
                    } else {
                        MultimodalPrimaryChannel.TEXT
                    },
                textRank = textCandidate?.rank,
                visualRank = visualCandidate?.rank,
                reciprocalRankScore = textContribution + visualContribution,
            )
        }.sortedWith(
            compareBy(FusedMultimodalCandidate::tier)
                .thenByDescending(FusedMultimodalCandidate::reciprocalRankScore)
                .thenBy { it.textRank ?: Int.MAX_VALUE }
                .thenBy { it.visualRank ?: Int.MAX_VALUE }
                .thenBy(FusedMultimodalCandidate::assetId),
        ).take(limit)
    }

    private fun tier(
        intent: MultimodalQueryIntent,
        text: MultimodalTextCandidate?,
        visual: MultimodalVisualCandidate?,
    ): Int =
        when (intent) {
            MultimodalQueryIntent.QUOTED_EXACT,
            MultimodalQueryIntent.IDENTIFIER,
            MultimodalQueryIntent.PERSON_NAME,
            -> checkNotNull(text).tier
            MultimodalQueryIntent.TEXT_CONCEPT -> text?.tier ?: VisualOnlyTier
            MultimodalQueryIntent.VISUAL_SCENE -> if (visual != null) 0 else 1
            MultimodalQueryIntent.BROAD_HYBRID -> text?.tier?.coerceAtMost(EnrichedTier) ?: EnrichedTier
        }

    private fun reciprocalRank(weightMicros: Int, rank: Int?): Long {
        if (weightMicros == 0 || rank == null) return 0
        return weightMicros.toLong() * ScorePrecision / (RrfConstant + rank)
    }

    private fun weights(intent: MultimodalQueryIntent): Weights =
        when (intent) {
            MultimodalQueryIntent.QUOTED_EXACT,
            MultimodalQueryIntent.IDENTIFIER,
            MultimodalQueryIntent.PERSON_NAME,
            -> Weights(text = 1_000_000, visual = 0)
            MultimodalQueryIntent.TEXT_CONCEPT -> Weights(text = 1_000_000, visual = 150_000)
            MultimodalQueryIntent.VISUAL_SCENE -> Weights(text = 200_000, visual = 1_000_000)
            MultimodalQueryIntent.BROAD_HYBRID -> Weights(text = 850_000, visual = 1_000_000)
        }

    private data class Weights(val text: Int, val visual: Int)

    private const val RrfConstant = 60
    private const val ScorePrecision = 1_000_000L
    private const val EnrichedTier = 3
    private const val VisualOnlyTier = 4
    private const val MaximumCandidates = 300
    private const val MaximumResults = 100
}
