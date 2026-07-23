package app.nayti.indexer

import app.nayti.storage.ActiveSnapshotPointerEntity
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.VectorIndexDao
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Owns explicit foreground preparation of an installed update pack. */
class ModelPackActivationRuntime(
    private val vectors: VectorIndexDao,
    private val contracts: CandidateChannelContractResolver,
    private val coordinator: CandidateActivationCoordinator,
    private val preparation: CandidatePreparationTracker,
    private val continueExecution: AtomicBoolean,
    executionGate: IndexExecutionGate,
    private val rollbackAction: suspend () -> ActiveSnapshotPointerEntity? = { null },
) {
    private val executionMutex = executionGate.mutex
    private val running = AtomicBoolean(false)

    val state = preparation.state

    fun isRunning(): Boolean = running.get()

    fun requestStop() {
        continueExecution.set(false)
    }

    suspend fun rollback(): ActiveSnapshotPointerEntity? = executionMutex.withLock {
        requestStop()
        rollbackAction()
    }

    suspend fun refresh(pack: ModelPackEntity?) {
        if (pack == null) {
            preparation.publish(null)
            return
        }
        val identity = identity(pack) ?: return preparation.publish(null)
        preparation.publish(
            pack = pack,
            candidateId = "candidate-$identity",
            targetChannels = contracts.resolve(pack, "candidate-snapshot-$identity"),
            running = running.get(),
        )
    }

    suspend fun runForeground(pack: ModelPackEntity): ActiveSnapshotPointerEntity? = executionMutex.withLock {
        val parentId = vectors.activeSnapshotId() ?: return@withLock null
        val parent = checkNotNull(vectors.snapshot(parentId))
        if (parent.packId == pack.packId && parent.packVersion == pack.packVersion) {
            return@withLock vectors.activePointer()
        }
        val identity = "${pack.manifestSha256.take(20)}-${sha256(parentId).take(16)}"
        val snapshotId = "candidate-snapshot-$identity"
        val candidateId = "candidate-$identity"
        var targetChannels = emptyList<ActivationSnapshotChannelEntity>()
        var terminalFailureCode: String? = null
        continueExecution.set(true)
        running.set(true)
        try {
            targetChannels = contracts.resolve(pack, snapshotId)
            preparation.publish(pack, candidateId, targetChannels, running = true)
            coordinator.prepareAndActivate(
                CandidateActivationRequest(
                    candidateId = candidateId,
                    snapshotId = snapshotId,
                    pack = pack,
                    targetChannels = targetChannels,
                ),
            )
        } catch (_: CandidatePreparationDeferredException) {
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            terminalFailureCode = failure::class.java.simpleName.uppercase()
            throw failure
        } catch (failure: LinkageError) {
            terminalFailureCode = "RUNTIME_UNAVAILABLE"
            throw failure
        } finally {
            continueExecution.set(false)
            running.set(false)
            preparation.publish(
                pack,
                candidateId,
                targetChannels,
                running = false,
                failureCode = terminalFailureCode,
            )
        }
    }

    private suspend fun identity(pack: ModelPackEntity): String? {
        val parentId = vectors.activeSnapshotId() ?: return null
        val parent = vectors.snapshot(parentId) ?: return null
        if (parent.packId == pack.packId && parent.packVersion == pack.packVersion) return null
        return "${pack.manifestSha256.take(20)}-${sha256(parentId).take(16)}"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
