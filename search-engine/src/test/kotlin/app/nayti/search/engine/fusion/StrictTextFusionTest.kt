package app.nayti.search.engine.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictTextFusionTest {
    @Test
    fun semanticCannotDisplaceAnyHigherEvidenceTier() {
        val ranked =
            StrictTextFusion.rank(
                intent = TextFusionIntent.TEXT_CONCEPT,
                lexical =
                    listOf(
                        TextLexicalCandidate(30, 1, TextLexicalEvidence.FUZZY_TEXT),
                        TextLexicalCandidate(20, 100, TextLexicalEvidence.LITERAL_TEXT),
                    ),
                semantic = listOf(TextSemanticCandidate(10, 1)),
                limit = 10,
            )

        assertEquals(listOf(20L, 30L, 10L), ranked.map { it.assetId })
        assertEquals(listOf(1, 2, 3), ranked.map { it.tier })
    }

    @Test
    fun semanticRankBreaksTiesInsideLiteralTier() {
        val ranked =
            StrictTextFusion.rank(
                intent = TextFusionIntent.TEXT_CONCEPT,
                lexical =
                    listOf(
                        TextLexicalCandidate(1, 1, TextLexicalEvidence.LITERAL_TEXT),
                        TextLexicalCandidate(2, 1, TextLexicalEvidence.LITERAL_TEXT),
                    ),
                semantic = listOf(TextSemanticCandidate(2, 3)),
                limit = 10,
            )

        assertEquals(listOf(2L, 1L), ranked.map { it.assetId })
        assertTrue(ranked.first().reciprocalRankScore > ranked.last().reciprocalRankScore)
    }

    @Test
    fun exactQuotedIntentDropsFuzzyAndSemanticCandidates() {
        val ranked =
            StrictTextFusion.rank(
                intent = TextFusionIntent.QUOTED_EXACT,
                lexical =
                    listOf(
                        TextLexicalCandidate(1, 1, TextLexicalEvidence.QUOTED_PHRASE),
                        TextLexicalCandidate(2, 2, TextLexicalEvidence.FUZZY_TEXT),
                    ),
                semantic = listOf(TextSemanticCandidate(3, 1)),
                limit = 10,
            )

        assertEquals(listOf(1L), ranked.map { it.assetId })
        assertEquals(TextFusionReason.QUOTED_PHRASE, ranked.single().reason)
    }

    @Test
    fun explicitlyEnabledSemanticChannelProvidesFallbackForExactIntent() {
        val ranked =
            StrictTextFusion.rank(
                intent = TextFusionIntent.QUOTED_EXACT,
                lexical = listOf(TextLexicalCandidate(1, 1, TextLexicalEvidence.QUOTED_PHRASE)),
                semantic = listOf(TextSemanticCandidate(2, 1)),
                limit = 10,
                allowSemanticFallback = true,
            )

        assertEquals(listOf(1L, 2L), ranked.map { it.assetId })
        assertEquals(listOf(0, 3), ranked.map { it.tier })
        assertEquals(TextFusionReason.SEMANTIC_TEXT, ranked.last().reason)
    }

    @Test
    fun equalSignalsUseStableAssetIdentity() {
        val ranked =
            StrictTextFusion.rank(
                intent = TextFusionIntent.TEXT_CONCEPT,
                lexical = emptyList(),
                semantic = listOf(TextSemanticCandidate(9, 1), TextSemanticCandidate(4, 1)),
                limit = 10,
            )

        assertEquals(listOf(4L, 9L), ranked.map { it.assetId })
    }
}
