package app.nayti.search.engine.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictMultimodalFusionTest {
    @Test
    fun textConceptNeverLetsVisualOnlyDisplaceTextEvidence() {
        val ranked =
            StrictMultimodalFusion.rank(
                intent = MultimodalQueryIntent.TEXT_CONCEPT,
                text = listOf(MultimodalTextCandidate(assetId = 10, rank = 100, tier = 3)),
                visual = listOf(MultimodalVisualCandidate(assetId = 20, rank = 1)),
                limit = 10,
            )

        assertEquals(listOf(10L, 20L), ranked.map { it.assetId })
        assertEquals(listOf(3, 4), ranked.map { it.tier })
    }

    @Test
    fun visualSceneStartsWithVisualAndUsesTextAsConfirmation() {
        val ranked =
            StrictMultimodalFusion.rank(
                intent = MultimodalQueryIntent.VISUAL_SCENE,
                text =
                    listOf(
                        MultimodalTextCandidate(assetId = 1, rank = 1, tier = 1),
                        MultimodalTextCandidate(assetId = 2, rank = 2, tier = 3),
                    ),
                visual =
                    listOf(
                        MultimodalVisualCandidate(assetId = 3, rank = 1),
                        MultimodalVisualCandidate(assetId = 2, rank = 2),
                    ),
                limit = 10,
            )

        assertEquals(listOf(2L, 3L, 1L), ranked.map { it.assetId })
        assertEquals(listOf(0, 0, 1), ranked.map { it.tier })
        assertEquals(MultimodalPrimaryChannel.VISUAL, ranked.first().primaryChannel)
    }

    @Test
    fun broadHybridPreservesExactTiersAndFusesEnrichedCandidates() {
        val ranked =
            StrictMultimodalFusion.rank(
                intent = MultimodalQueryIntent.BROAD_HYBRID,
                text =
                    listOf(
                        MultimodalTextCandidate(assetId = 1, rank = 100, tier = 0),
                        MultimodalTextCandidate(assetId = 2, rank = 1, tier = 3),
                    ),
                visual =
                    listOf(
                        MultimodalVisualCandidate(assetId = 3, rank = 1),
                        MultimodalVisualCandidate(assetId = 2, rank = 2),
                    ),
                limit = 10,
            )

        assertEquals(1L, ranked.first().assetId)
        assertEquals(0, ranked.first().tier)
        assertEquals(listOf(2L, 3L), ranked.drop(1).map { it.assetId })
        assertTrue(ranked[1].reciprocalRankScore > ranked[2].reciprocalRankScore)
    }

    @Test
    fun identifierIntentDropsVisualCandidatesEvenIfPassedByMistake() {
        val ranked =
            StrictMultimodalFusion.rank(
                intent = MultimodalQueryIntent.IDENTIFIER,
                text = listOf(MultimodalTextCandidate(assetId = 1, rank = 1, tier = 0)),
                visual = listOf(MultimodalVisualCandidate(assetId = 2, rank = 1)),
                limit = 10,
            )

        assertEquals(listOf(1L), ranked.map { it.assetId })
    }
}
