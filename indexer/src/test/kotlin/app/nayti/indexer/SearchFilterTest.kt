package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchFilterTest {
    @Test
    fun indexingCutoffIntersectsUserDateFilterWithoutWeakeningOtherFacets() {
        val filter =
            SearchFilter(
                takenFromMillis = 1_000,
                takenBeforeMillis = 9_000,
                bucketId = 7,
                mimeType = "image/jpeg",
            )

        assertEquals(
            filter.copy(takenFromMillis = 5_000),
            filter.constrainedFrom(5_000),
        )
        assertEquals(filter, filter.constrainedFrom(null))
        assertEquals(filter, filter.constrainedFrom(500))
    }

    @Test
    fun disjointUserRangeIsRejectedInsteadOfLeakingOutsideScope() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchFilter(takenBeforeMillis = 5_000).constrainedFrom(5_000)
        }
    }
}
