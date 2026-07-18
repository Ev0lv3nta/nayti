package app.nayti.indexer

import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationCandidateChannelAction
import app.nayti.storage.ActivationCandidateChannelEntity
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActiveSnapshotPointerEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.VectorIndexDao

enum class ActivationBoundary {
    AFTER_CANDIDATE_REGISTERED,
    AFTER_CANDIDATE_RECONCILED,
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
        targetChannels: List<ActivationSnapshotChannelEntity>? = null,
    ): ActivationCandidateEntity {
        val now = clock()
        val access = checkNotNull(vectors.accessObservation())
        check(access.accessScope != "None")
        val parentSnapshotId = vectors.activeSnapshotId()
        val parentChannels = parentSnapshotId?.let { vectors.snapshotChannels(it) }.orEmpty()
        val desiredChannels =
            targetChannels ?: run {
                val parent = checkNotNull(parentSnapshotId?.let { vectors.snapshot(it) })
                check(parent.packManifestSha256 == pack.manifestSha256) {
                    "A different pack requires explicit per-channel target contracts"
                }
                parentChannels.map { component ->
                    component.copy(snapshotId = snapshotId, inheritedFromSnapshotId = parentSnapshotId)
                }
            }
        require(desiredChannels.all { it.snapshotId == snapshotId })
        val plan =
            ActivationDependencyPlanner.plan(parentChannels, desiredChannels).map { component ->
                val target = desiredChannels.single { it.channel == component.channel }
                ActivationCandidateChannelEntity(
                    candidateId = candidateId,
                    channel = component.channel,
                    pipelineVersion = target.pipelineVersion,
                    componentHash = target.componentHash,
                    embeddingSpaceHash = target.embeddingSpaceHash,
                    action = component.action.name,
                    reason = component.reason.name,
                )
            }
        check(
            plan.none {
                it.action == ActivationCandidateChannelAction.REBUILD_SHADOW &&
                    it.channel !in shadowBuildChannels
            },
        ) { "This build cannot prepare the changed channel" }
        val candidate =
            ActivationCandidateEntity(
                candidateId = candidateId,
                snapshotId = snapshotId,
                parentSnapshotId = parentSnapshotId,
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
        vectors.registerActivationCandidate(candidate, plan)
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
                val plan = vectors.activationCandidateChannels(candidate.candidateId)
                check(plan.all { it.action == ActivationCandidateChannelAction.INHERIT }) {
                    "Rebuilt channels require explicit prepared activation contracts"
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

    suspend fun reconcileCatalogWatermark(
        candidateId: String,
        expectedWatermark: Long,
        nextWatermark: Long,
    ): ActivationCandidateEntity {
        val reconciled =
            vectors.reconcileActivationCandidateWatermark(
                candidateId = candidateId,
                expectedWatermark = expectedWatermark,
                nextWatermark = nextWatermark,
                nowMillis = clock(),
            )
        boundaryObserver(ActivationBoundary.AFTER_CANDIDATE_RECONCILED)
        return reconciled
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

    private companion object {
        val shadowBuildChannels = setOf(IndexChannel.OCR, IndexChannel.OCR_SEMANTIC, IndexChannel.VISUAL)
    }
}
