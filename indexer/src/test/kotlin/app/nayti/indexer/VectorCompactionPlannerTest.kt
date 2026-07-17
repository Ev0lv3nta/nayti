package app.nayti.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorCompactionPlannerTest {
    @Test
    fun eightBaseSegmentsBecomeOneLevelledSegment() {
        val plan = VectorCompactionPlanner.plan(List(8) { CompactionSegment(32, 0) })

        assertEquals(VectorCompactionPlan(0, 8), plan)
    }

    @Test
    fun plannerDoesNotRewriteAnExistingMergedSegmentWithNewBaseTail() {
        val plan =
            VectorCompactionPlanner.plan(
                listOf(CompactionSegment(8, 1)) + List(7) { CompactionSegment(1, 0) },
            )

        assertNull(plan)
    }

    @Test
    fun fourSegmentsAtTheSameHigherLevelAreMerged() {
        val plan = VectorCompactionPlanner.plan(List(4) { CompactionSegment(8, 1) })

        assertEquals(VectorCompactionPlan(0, 4), plan)
    }

    @Test
    fun nearCapacityPairMayAdvanceWithoutWaitingForFour() {
        val plan =
            VectorCompactionPlanner.plan(
                listOf(CompactionSegment(128, 3), CompactionSegment(128, 3)),
            )

        assertEquals(VectorCompactionPlan(0, 2), plan)
    }

    @Test
    fun oversizedRunUsesLargestPrefixThatFits() {
        val plan = VectorCompactionPlanner.plan(List(4) { CompactionSegment(80, 2) })

        assertEquals(VectorCompactionPlan(0, 3), plan)
    }
}
