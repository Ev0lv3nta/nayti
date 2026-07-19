package app.nayti.indexer

import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexingScopeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexingDurationEstimatorTest {
    @Test
    fun completedPartialScopeScalesActiveDurationByEligibleShare() {
        assertEquals(
            3_600_000L,
            estimateAllMediaDuration(
                scope = partialScope(total = 1_200, eligible = 120),
                capabilities = completedCapabilities(120),
                activeDurationMillis = 360_000,
            ),
        )
    }

    @Test
    fun estimateIsHiddenUntilEveryCapabilityFinishes() {
        assertNull(
            estimateAllMediaDuration(
                scope = partialScope(total = 1_200, eligible = 120),
                capabilities = completedCapabilities(120).mapIndexed { index, coverage ->
                    if (index == 0) coverage.copy(outstanding = 1) else coverage
                },
                activeDurationMillis = 360_000,
            ),
        )
    }

    @Test
    fun fullLibraryDoesNotEstimateItself() {
        assertNull(
            estimateAllMediaDuration(
                scope =
                    partialScope(total = 1_200, eligible = 1_200).copy(
                        mode = IndexingScopeMode.ALL,
                        takenFromMillis = null,
                    ),
                capabilities = completedCapabilities(1_200),
                activeDurationMillis = 3_600_000,
            ),
        )
    }

    private fun partialScope(total: Long, eligible: Long) =
        IndexingScopeSummary(
            mode = IndexingScopeMode.SINCE_DATE,
            takenFromMillis = 1_700_000_000_000,
            revision = 2,
            totalAvailable = total,
            eligibleAssets = eligible,
            unknownDateAssets = 0,
        )

    private fun completedCapabilities(count: Long) =
        SearchCapability.entries.map { capability ->
            SearchCapabilityCoverage(
                capability = capability,
                accessible = count,
                committed = count,
                permanentGaps = 0,
                outstanding = 0,
            )
        }
}
