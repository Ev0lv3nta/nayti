package app.nayti.search.engine.fusion

enum class TextFusionIntent {
    QUOTED_EXACT,
    IDENTIFIER,
    PERSON_NAME,
    TEXT_CONCEPT,
}

enum class TextLexicalEvidence {
    EXACT_IDENTIFIER,
    QUOTED_PHRASE,
    PERSON_NAME,
    LITERAL_TEXT,
    FUZZY_TEXT,
}

enum class TextFusionReason {
    EXACT_IDENTIFIER,
    QUOTED_PHRASE,
    PERSON_NAME,
    LITERAL_TEXT,
    FUZZY_TEXT,
    SEMANTIC_TEXT,
}

data class TextLexicalCandidate(
    val assetId: Long,
    val rank: Int,
    val evidence: TextLexicalEvidence,
)

data class TextSemanticCandidate(
    val assetId: Long,
    val rank: Int,
)

data class FusedTextCandidate(
    val assetId: Long,
    val tier: Int,
    val reason: TextFusionReason,
    val lexicalRank: Int?,
    val semanticRank: Int?,
    val reciprocalRankScore: Long,
)

/** Strict evidence tiers first, weighted reciprocal-rank fusion only within a tier. */
object StrictTextFusion {
    fun rank(
        intent: TextFusionIntent,
        lexical: List<TextLexicalCandidate>,
        semantic: List<TextSemanticCandidate>,
        limit: Int,
    ): List<FusedTextCandidate> {
        require(limit in 1..MaximumResults)
        require(lexical.size <= MaximumCandidates && semantic.size <= MaximumCandidates)
        require(lexical.map { it.assetId }.distinct().size == lexical.size)
        require(semantic.map { it.assetId }.distinct().size == semantic.size)
        require(lexical.all { it.assetId > 0 && it.rank in 1..MaximumCandidates })
        require(semantic.all { it.assetId > 0 && it.rank in 1..MaximumCandidates })
        val weights = weights(intent)
        val lexicalByAsset = lexical.associateBy(TextLexicalCandidate::assetId)
        val semanticByAsset = semantic.associateBy(TextSemanticCandidate::assetId)
        return (lexicalByAsset.keys + semanticByAsset.keys).mapNotNull { assetId ->
            val lexicalCandidate = lexicalByAsset[assetId]
            val semanticCandidate = semanticByAsset[assetId]
            val lexicalWeight = lexicalCandidate?.let { candidate ->
                if (candidate.evidence == TextLexicalEvidence.FUZZY_TEXT) weights.fuzzy else weights.lexical
            } ?: 0
            val semanticWeight = if (semanticCandidate == null) 0 else weights.semantic
            if (lexicalWeight == 0 && semanticWeight == 0) return@mapNotNull null
            val tier = lexicalCandidate?.evidence?.tier ?: SemanticTier
            val reason = lexicalCandidate?.evidence?.reason ?: TextFusionReason.SEMANTIC_TEXT
            FusedTextCandidate(
                assetId = assetId,
                tier = tier,
                reason = reason,
                lexicalRank = lexicalCandidate?.rank,
                semanticRank = semanticCandidate?.rank,
                reciprocalRankScore =
                    reciprocalRank(lexicalWeight, lexicalCandidate?.rank) +
                        reciprocalRank(semanticWeight, semanticCandidate?.rank),
            )
        }.sortedWith(
            compareBy(FusedTextCandidate::tier)
                .thenByDescending(FusedTextCandidate::reciprocalRankScore)
                .thenBy { it.lexicalRank ?: Int.MAX_VALUE }
                .thenBy { it.semanticRank ?: Int.MAX_VALUE }
                .thenBy(FusedTextCandidate::assetId),
        ).take(limit)
    }

    private fun reciprocalRank(weightMicros: Int, rank: Int?): Long {
        if (weightMicros == 0 || rank == null) return 0
        return weightMicros.toLong() * ScorePrecision / (RrfConstant + rank)
    }

    private fun weights(intent: TextFusionIntent): Weights =
        when (intent) {
            TextFusionIntent.QUOTED_EXACT -> Weights(lexical = 1_000_000, fuzzy = 0, semantic = 0)
            TextFusionIntent.IDENTIFIER -> Weights(lexical = 1_000_000, fuzzy = 100_000, semantic = 0)
            TextFusionIntent.PERSON_NAME -> Weights(lexical = 1_000_000, fuzzy = 250_000, semantic = 0)
            TextFusionIntent.TEXT_CONCEPT -> Weights(lexical = 1_000_000, fuzzy = 250_000, semantic = 800_000)
        }

    private val TextLexicalEvidence.tier: Int
        get() =
            when (this) {
                TextLexicalEvidence.EXACT_IDENTIFIER,
                TextLexicalEvidence.QUOTED_PHRASE,
                -> 0
                TextLexicalEvidence.PERSON_NAME,
                TextLexicalEvidence.LITERAL_TEXT,
                -> 1
                TextLexicalEvidence.FUZZY_TEXT -> 2
            }

    private val TextLexicalEvidence.reason: TextFusionReason
        get() = TextFusionReason.valueOf(name)

    private data class Weights(val lexical: Int, val fuzzy: Int, val semantic: Int)

    private const val SemanticTier = 3
    private const val RrfConstant = 60
    private const val ScorePrecision = 1_000_000L
    private const val MaximumCandidates = 300
    private const val MaximumResults = 100
}
