package app.nayti.search.engine.similarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PerceptualHashRankerTest {
    @Test
    fun ranksByDistanceThenAssetAndExcludesSource() {
        val records =
            listOf(
                record(10, 0b0000),
                record(5, 0b0011),
                record(4, 0b0001),
                record(3, 0b0001),
                record(2, 0b1111),
            )

        val matches = PerceptualHashRanker.rank(10, 0, records, maximumDistance = 2, limit = 3)

        assertEquals(listOf(3L, 4L, 5L), matches.map(PerceptualHashMatch::assetId))
        assertEquals(listOf(1, 1, 2), matches.map(PerceptualHashMatch::distance))
    }

    @Test
    fun exactModeReturnsOnlyBitIdenticalHashes() {
        val matches =
            PerceptualHashRanker.rank(
                sourceAssetId = 1,
                sourceHash = 42,
                records = listOf(record(1, 42), record(2, 42), record(3, 43)),
                maximumDistance = PerceptualHashRanker.ExactDuplicateDistance,
            )

        assertEquals(listOf(2L), matches.map(PerceptualHashMatch::assetId))
    }

    @Test
    fun rejectsAmbiguousDuplicateCurrentRows() {
        assertThrows(IllegalArgumentException::class.java) {
            PerceptualHashRanker.rank(
                sourceAssetId = 1,
                sourceHash = 0,
                records = listOf(record(2, 0), record(2, 1)),
            )
        }
    }

    private fun record(assetId: Long, hashBits: Long) =
        PerceptualHashRecord(assetId, hashBits, publicationEpoch = assetId + 100)
}
