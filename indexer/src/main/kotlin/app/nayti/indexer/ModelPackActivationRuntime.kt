package app.nayti.indexer

import app.nayti.storage.ActiveSnapshotPointerEntity
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.VectorIndexDao
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.withLock

/** Owns explicit foreground preparation of an installed update pack. */
class ModelPackActivationRuntime(
    private val vectors: VectorIndexDao,
    private val contracts: CandidateChannelContractResolver,
    private val coordinator: CandidateActivationCoordinator,
    private val continueExecution: AtomicBoolean,
    executionGate: IndexExecutionGate,
    private val rollbackAction: suspend () -> ActiveSnapshotPointerEntity? = { null },
) {
    private val executionMutex = executionGate.mutex
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun requestStop() {
        continueExecution.set(false)
    }

    suspend fun rollback(): ActiveSnapshotPointerEntity? = executionMutex.withLock {
        requestStop()
        rollbackAction()
    }

    suspend fun runForeground(pack: ModelPackEntity): ActiveSnapshotPointerEntity? = executionMutex.withLock {
        val parentId = vectors.activeSnapshotId() ?: return@withLock null
        val parent = checkNotNull(vectors.snapshot(parentId))
        if (parent.packId == pack.packId && parent.packVersion == pack.packVersion) {
            return@withLock vectors.activePointer()
        }
        val identity = "${pack.manifestSha256.take(20)}-${sha256(parentId).take(16)}"
        val snapshotId = "candidate-snapshot-$identity"
        continueExecution.set(true)
        running.set(true)
        try {
            coordinator.prepareAndActivate(
                CandidateActivationRequest(
                    candidateId = "candidate-$identity",
                    snapshotId = snapshotId,
                    pack = pack,
                    targetChannels = contracts.resolve(pack, snapshotId),
                ),
            )
        } catch (_: CandidatePreparationDeferredException) {
            null
        } finally {
            continueExecution.set(false)
            running.set(false)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
