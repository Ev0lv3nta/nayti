package app.nayti.indexer

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/** Process-scoped ownership gate that prevents concurrent heavyweight model sessions. */
class NeuralExecutionLane {
    private val mutex = Mutex()

    suspend fun acquire(): NeuralExecutionPermit {
        mutex.lock()
        return NeuralExecutionPermit(mutex)
    }

    suspend fun <T> withPermit(block: suspend () -> T): T = acquire().use { block() }
}

class NeuralExecutionPermit internal constructor(
    private val mutex: Mutex,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) mutex.unlock()
    }
}
