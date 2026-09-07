package app.nayti.indexer

import kotlin.math.roundToInt

/**
 * Diagnostic dot product scaled by the int8 coordinate bound, not cosine or probability.
 * The existing ranking uses raw dot within a channel and ranks for cross-channel RRF.
 * This quantity is dimension-dependent and must not share thresholds across embedding spaces.
 */
internal object QuantizedDotScore {
    fun scaledMicros(rawDot: Int, dimension: Int): Int {
        require(dimension > 0)
        val maximumDot = dimension.toDouble() * 127 * 127
        return (rawDot.toDouble() * 1_000_000 / maximumDot).roundToInt().coerceIn(-1_000_000, 1_000_000)
    }
}
