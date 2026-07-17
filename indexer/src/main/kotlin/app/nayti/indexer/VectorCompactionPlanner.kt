package app.nayti.indexer

import app.nayti.search.engine.VectorSegmentV1Writer

data class CompactionSegment(
    val recordCount: Int,
    val level: Int,
)

data class VectorCompactionPlan(
    val firstSegmentOrdinal: Int,
    val segmentCount: Int,
)

/** Selects a bounded, adjacent run without repeatedly rewriting segments from different levels. */
object VectorCompactionPlanner {
    private const val BaseLevelFanout = 8
    private const val HigherLevelFanout = 4
    private const val NearlyFullRecordCount = 192

    fun plan(segments: List<CompactionSegment>): VectorCompactionPlan? {
        require(segments.all { it.recordCount > 0 && it.level >= 0 })
        var runStart = 0
        while (runStart < segments.size) {
            val level = segments[runStart].level
            var runEnd = runStart + 1
            while (runEnd < segments.size && segments[runEnd].level == level) runEnd += 1
            val runSize = runEnd - runStart
            if (level == 0 && runSize >= BaseLevelFanout) {
                val count = BaseLevelFanout
                if (segments.recordCount(runStart, count) <= VectorSegmentV1Writer.MaximumRecordCount) {
                    return VectorCompactionPlan(runStart, count)
                }
            } else if (level > 0 && runSize >= 2) {
                for (count in minOf(HigherLevelFanout, runSize) downTo 2) {
                    val records = segments.recordCount(runStart, count)
                    if (
                        records <= VectorSegmentV1Writer.MaximumRecordCount &&
                        (count == HigherLevelFanout || records >= NearlyFullRecordCount)
                    ) {
                        return VectorCompactionPlan(runStart, count)
                    }
                }
            }
            runStart = runEnd
        }
        return null
    }

    private fun List<CompactionSegment>.recordCount(first: Int, count: Int): Int =
        subList(first, first + count).sumOf(CompactionSegment::recordCount)
}
