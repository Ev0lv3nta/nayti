package app.nayti.indexer

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

enum class NeuralExecutionPriority {
    INTERACTIVE_QUERY,
    BACKGROUND_INDEXING,
}

/** Process-scoped single owner with FIFO query priority at inference boundaries. */
class NeuralExecutionLane {
    private val lock = Any()
    private val queryWaiters = ArrayDeque<Waiter>()
    private val indexingWaiters = ArrayDeque<Waiter>()
    private var owned = false

    suspend fun acquire(
        priority: NeuralExecutionPriority = NeuralExecutionPriority.BACKGROUND_INDEXING,
    ): NeuralExecutionPermit {
        val waiter =
            synchronized(lock) {
                if (!owned) {
                    owned = true
                    null
                } else {
                    Waiter().also { queued -> queue(priority).addLast(queued) }
                }
            }
        if (waiter != null) {
            try {
                waiter.signal.await()
            } catch (failure: Throwable) {
                val releaseGranted =
                    synchronized(lock) {
                        when (waiter.state) {
                            WaiterState.WAITING -> {
                                waiter.state = WaiterState.CANCELLED
                                false
                            }
                            WaiterState.GRANTED -> true
                            WaiterState.CANCELLED -> false
                        }
                    }
                if (releaseGranted) release()
                throw failure
            }
        }
        return NeuralExecutionPermit(::release)
    }

    suspend fun <T> withPermit(
        priority: NeuralExecutionPriority = NeuralExecutionPriority.BACKGROUND_INDEXING,
        block: suspend () -> T,
    ): T = acquire(priority).use { block() }

    private fun queue(priority: NeuralExecutionPriority): ArrayDeque<Waiter> =
        if (priority == NeuralExecutionPriority.INTERACTIVE_QUERY) queryWaiters else indexingWaiters

    private fun release() {
        var granted: Waiter? = null
        synchronized(lock) {
            while (granted == null) {
                val next = pollWaiting(queryWaiters) ?: pollWaiting(indexingWaiters)
                if (next == null) {
                    check(owned)
                    owned = false
                    return
                }
                next.state = WaiterState.GRANTED
                granted = next
            }
        }
        checkNotNull(granted).signal.complete(Unit)
    }

    private fun pollWaiting(queue: ArrayDeque<Waiter>): Waiter? {
        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            if (candidate.state == WaiterState.WAITING) return candidate
        }
        return null
    }

    private class Waiter(
        val signal: CompletableDeferred<Unit> = CompletableDeferred(),
        var state: WaiterState = WaiterState.WAITING,
    )

    private enum class WaiterState {
        WAITING,
        GRANTED,
        CANCELLED,
    }
}

class NeuralExecutionPermit internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) release()
    }
}
