package app.nayti.indexer

import app.nayti.storage.ActivationCandidateChannelEntity
import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.ActiveSnapshotPointerEntity
import app.nayti.storage.ModelPackEntity
import kotlinx.coroutines.CancellationException

data class CandidateActivationRequest(
    val candidateId: String,
    val snapshotId: String,
    val pack: ModelPackEntity,
    val targetChannels: List<ActivationSnapshotChannelEntity>,
)

data class PreparedCandidateSnapshot(
    val snapshot: ActivationSnapshotEntity,
    val channels: List<ActivationSnapshotChannelEntity>,
)

fun interface CandidateShadowBuilder {
    suspend fun prepare(
        candidate: ActivationCandidateEntity,
        plan: List<ActivationCandidateChannelEntity>,
    ): PreparedCandidateSnapshot
}

fun interface CandidateCanaryVerifier {
    suspend fun verify(candidate: PreparedCandidateSnapshot)
}

class CandidatePreparationDeferredException(message: String) : Exception(message)

/** A recoverable install-to-activation state machine; active search changes only in the final transaction. */
class CandidateActivationCoordinator(
    private val activation: CandidateActivationGateway,
    private val builder: CandidateShadowBuilder,
    private val canary: CandidateCanaryVerifier,
) {
    suspend fun prepareAndActivate(request: CandidateActivationRequest): ActiveSnapshotPointerEntity {
        val candidate = activation.candidate(request.candidateId) ?: activation.register(
            candidateId = request.candidateId,
            snapshotId = request.snapshotId,
            pack = request.pack,
            targetChannels = request.targetChannels,
        )
        validateIdentity(candidate, request)
        return try {
            when (candidate.state) {
                ActivationCandidateState.BUILDING_SHADOW -> prepareAndCommit(candidate)
                ActivationCandidateState.READY_TO_ACTIVATE -> activation.activate(candidate.candidateId)
                ActivationCandidateState.ACTIVE -> {
                    checkNotNull(activation.activePointer()).also { pointer ->
                        check(pointer.snapshotId == candidate.snapshotId)
                    }
                }
                else -> error("Candidate cannot resume from ${candidate.state}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (deferred: CandidatePreparationDeferredException) {
            throw deferred
        } catch (failure: Exception) {
            activation.reject(candidate.candidateId, FailureCode)
            throw failure
        }
    }

    private suspend fun prepareAndCommit(candidate: ActivationCandidateEntity): ActiveSnapshotPointerEntity {
        val prepared = builder.prepare(candidate, activation.plan(candidate.candidateId))
        check(prepared.snapshot.snapshotId == candidate.snapshotId)
        check(prepared.snapshot.parentSnapshotId == candidate.parentSnapshotId)
        check(prepared.channels.all { component -> component.snapshotId == candidate.snapshotId })
        canary.verify(prepared)
        activation.markReady(candidate.candidateId, prepared.snapshot, prepared.channels)
        return activation.activate(candidate.candidateId)
    }

    private fun validateIdentity(
        candidate: ActivationCandidateEntity,
        request: CandidateActivationRequest,
    ) {
        check(candidate.snapshotId == request.snapshotId)
        check(candidate.packId == request.pack.packId)
        check(candidate.packVersion == request.pack.packVersion)
        check(candidate.packManifestSha256 == request.pack.manifestSha256)
    }

    private companion object {
        const val FailureCode = "CANDIDATE_PREPARATION_FAILED"
    }
}
