package app.nayti.indexer

import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActiveSnapshotPointerEntity
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.VectorIndexDao

enum class ActivationBoundary {
    AFTER_CANDIDATE_REGISTERED,
    AFTER_CANDIDATE_READY,
    BEFORE_POINTER_COMMIT,
    AFTER_POINTER_COMMIT,
    AFTER_ROLLBACK_COMMIT,
}

fun interface ActivationCandidateVerifier {
    suspend fun verify(
        snapshot: ActivationSnapshotEntity,
        channels: List<ActivationSnapshotChannelEntity>,
    )
}

/** Coordinates validated shadow snapshots without ever mutating the currently query-active index. */
class AtomicSnapshotActivator(
    private val vectors: VectorIndexDao,
    private val verifier: ActivationCandidateVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val boundaryObserver: (ActivationBoundary) -> Unit = {},
) {
    suspend fun register(
        candidateId: String,
        snapshotId: String,
        pack: ModelPackEntity,
    ): ActivationCandidateEntity {
        val now = clock()
        val access = checkNotNull(vectors.accessObservation())
        check(access.accessScope != "None")
        val candidate =
            ActivationCandidateEntity(
                candidateId = candidateId,
                snapshotId = snapshotId,
                parentSnapshotId = vectors.activeSnapshotId(),
                packId = pack.packId,
                packVersion = pack.packVersion,
                packManifestSha256 = pack.manifestSha256,
                capturedAccessRevision = access.processAccessRevision,
                capturedCatalogWatermark = vectors.catalogWatermark()?.catalogRevision ?: 0,
                state = ActivationCandidateState.BUILDING_SHADOW,
                createdAtMillis = now,
                updatedAtMillis = now,
                failureCode = null,
            )
        vectors.registerActivationCandidate(candidate)
        boundaryObserver(ActivationBoundary.AFTER_CANDIDATE_REGISTERED)
        return candidate
    }

    suspend fun markReady(
        candidateId: String,
        snapshot: ActivationSnapshotEntity,
        channels: List<ActivationSnapshotChannelEntity>? = null,
    ): ActivationSnapshotEntity {
        val candidate = checkNotNull(vectors.activationCandidate(candidateId))
        val preparedChannels =
            channels ?: candidate.parentSnapshotId?.let { parentId ->
                val parent = checkNotNull(vectors.snapshot(parentId))
                check(parent.packManifestSha256 == snapshot.packManifestSha256) {
                    "A different pack requires explicit per-channel activation contracts"
                }
                vectors.snapshotChannels(parentId).map { component ->
                    component.copy(
                        snapshotId = snapshot.snapshotId,
                        inheritedFromSnapshotId = parentId,
                    )
                }
            }.orEmpty()
        verifier.verify(snapshot, preparedChannels)
        val ready = vectors.markActivationCandidateReady(candidateId, snapshot, preparedChannels, clock())
        boundaryObserver(ActivationBoundary.AFTER_CANDIDATE_READY)
        return ready
    }

    suspend fun activate(candidateId: String): ActiveSnapshotPointerEntity {
        boundaryObserver(ActivationBoundary.BEFORE_POINTER_COMMIT)
        val pointer = vectors.activateReadyCandidate(candidateId, clock())
        boundaryObserver(ActivationBoundary.AFTER_POINTER_COMMIT)
        return pointer
    }

    suspend fun rollback(): ActiveSnapshotPointerEntity? {
        val pointer = vectors.rollbackActiveCandidate(clock())
        if (pointer != null) boundaryObserver(ActivationBoundary.AFTER_ROLLBACK_COMMIT)
        return pointer
    }

    suspend fun reject(candidateId: String, failureCode: String): Boolean =
        vectors.rejectActivationCandidate(candidateId, failureCode, clock())
}
