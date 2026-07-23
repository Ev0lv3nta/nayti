package app.nayti.indexer

import kotlinx.coroutines.delay

/**
 * Drives low-frequency projections of durable indexing state while a long execution window runs.
 *
 * The indexing ledger remains the source of truth. A tick only asks the runtime to read that
 * ledger, so cancellation or a process death cannot invent or lose progress.
 */
internal class PreparationProgressPoller(
    private val intervalMillis: Long = DefaultIntervalMillis,
) {
    init {
        require(intervalMillis > 0)
    }

    suspend fun run(onTick: suspend () -> Unit): Nothing {
        while (true) {
            delay(intervalMillis)
            onTick()
        }
    }

    private companion object {
        const val DefaultIntervalMillis = 15_000L
    }
}
