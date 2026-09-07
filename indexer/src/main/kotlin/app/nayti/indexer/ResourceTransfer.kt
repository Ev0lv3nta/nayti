package app.nayti.indexer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Closes a created resource if prompt cancellation prevents delivery to its caller. */
internal suspend fun <T : AutoCloseable> transferResource(
    dispatcher: CoroutineDispatcher,
    create: suspend () -> T,
): T {
    var undelivered: T? = null
    try {
        val result = withContext(dispatcher) { create().also { undelivered = it } }
        undelivered = null
        return result
    } finally {
        undelivered?.let { resource ->
            withContext(NonCancellable + dispatcher) { resource.close() }
        }
    }
}
