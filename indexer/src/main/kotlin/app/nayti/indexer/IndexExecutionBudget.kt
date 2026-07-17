package app.nayti.indexer

fun interface IndexExecutionClock {
    fun elapsedRealtimeMillis(): Long
}

internal class IndexExecutionBudget(
    clock: IndexExecutionClock,
    private val maximumDurationMillis: Long,
) {
    private val startedAtMillis = clock.elapsedRealtimeMillis()
    private val nowMillis = clock::elapsedRealtimeMillis

    init {
        require(maximumDurationMillis > 0)
    }

    fun hasTimeRemaining(): Boolean =
        nowMillis() - startedAtMillis < maximumDurationMillis
}
