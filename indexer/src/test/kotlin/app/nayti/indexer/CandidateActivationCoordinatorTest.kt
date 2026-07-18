package app.nayti.indexer

import app.nayti.storage.ActivationCandidateChannelEntity
import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.ActiveSnapshotPointerEntity
import app.nayti.storage.IndexChannel
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateActivationCoordinatorTest {
    @Test
    fun newCandidateBuildsVerifiesMarksReadyAndActivatesInOrder() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)

        val pointer = coordinator.prepareAndActivate(request())

        assertEquals("candidate-snapshot", pointer.snapshotId)
        assertEquals(listOf("register", "plan", "build", "canary", "ready", "activate"), gateway.events)
        assertEquals(ActivationCandidateState.ACTIVE, gateway.stored?.state)
    }

    @Test
    fun readyCandidateResumesAtAtomicActivationWithoutRebuilding() = runBlocking {
        val gateway = FakeGateway().apply {
            stored = candidateEntity(ActivationCandidateState.READY_TO_ACTIVATE)
        }

        coordinator(gateway).prepareAndActivate(request())

        assertEquals(listOf("activate"), gateway.events)
    }

    @Test
    fun activeCandidateReturnsCommittedPointerAfterProcessRestart() = runBlocking {
        val gateway = FakeGateway().apply {
            stored = candidateEntity(ActivationCandidateState.ACTIVE)
        }

        val pointer = coordinator(gateway).prepareAndActivate(request())

        assertEquals("candidate-snapshot", pointer.snapshotId)
        assertEquals(listOf("pointer"), gateway.events)
    }

    @Test
    fun canaryFailureRejectsCandidateBeforePointerCommit() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway, canaryFailure = true)

        val failure = runCatching { coordinator.prepareAndActivate(request()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("register", "plan", "build", "canary", "reject"), gateway.events)
        assertEquals(ActivationCandidateState.REJECTED, gateway.stored?.state)
        assertFalse(gateway.events.contains("activate"))
    }

    @Test
    fun cancellationLeavesDurableCandidateResumable() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = CandidateActivationCoordinator(
            activation = gateway,
            builder = CandidateShadowBuilder { _, _ ->
                gateway.events += "build"
                throw CancellationException("host stopped")
            },
            canary = CandidateCanaryVerifier { gateway.events += "canary" },
        )

        val failure = runCatching { coordinator.prepareAndActivate(request()) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(ActivationCandidateState.BUILDING_SHADOW, gateway.stored?.state)
        assertFalse(gateway.events.contains("reject"))
    }

    @Test
    fun boundedHostStopLeavesDurableCandidateResumable() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = CandidateActivationCoordinator(
            activation = gateway,
            builder = CandidateShadowBuilder { _, _ ->
                gateway.events += "build"
                throw CandidatePreparationDeferredException("stopped")
            },
            canary = CandidateCanaryVerifier { gateway.events += "canary" },
        )

        val failure = runCatching { coordinator.prepareAndActivate(request()) }.exceptionOrNull()

        assertTrue(failure is CandidatePreparationDeferredException)
        assertEquals(ActivationCandidateState.BUILDING_SHADOW, gateway.stored?.state)
        assertFalse(gateway.events.contains("reject"))
    }

    private fun coordinator(gateway: FakeGateway, canaryFailure: Boolean = false) =
        CandidateActivationCoordinator(
            activation = gateway,
            builder = CandidateShadowBuilder { candidate, _ ->
                gateway.events += "build"
                prepared(candidate)
            },
            canary = CandidateCanaryVerifier {
                gateway.events += "canary"
                if (canaryFailure) error("canary failed")
            },
        )

    private class FakeGateway : CandidateActivationGateway {
        val events = mutableListOf<String>()
        var stored: ActivationCandidateEntity? = null

        override suspend fun candidate(candidateId: String): ActivationCandidateEntity? = stored

        override suspend fun plan(candidateId: String): List<ActivationCandidateChannelEntity> {
            events += "plan"
            return listOf(
                ActivationCandidateChannelEntity(
                    candidateId,
                    IndexChannel.VISUAL,
                    "visual-v1",
                    Hash,
                    Hash,
                    "REBUILD_SHADOW",
                    "COMPONENT_CHANGED",
                ),
            )
        }

        override suspend fun activePointer(): ActiveSnapshotPointerEntity? {
            events += "pointer"
            return ActiveSnapshotPointerEntity(snapshotId = "candidate-snapshot")
        }

        override suspend fun reconcileCatalogWatermark(
            candidateId: String,
            expectedWatermark: Long,
            nextWatermark: Long,
        ): ActivationCandidateEntity = error("not used")

        override suspend fun register(
            candidateId: String,
            snapshotId: String,
            pack: ModelPackEntity,
            targetChannels: List<ActivationSnapshotChannelEntity>?,
        ): ActivationCandidateEntity {
            events += "register"
            return candidateEntity(ActivationCandidateState.BUILDING_SHADOW).also { stored = it }
        }

        override suspend fun markReady(
            candidateId: String,
            snapshot: ActivationSnapshotEntity,
            channels: List<ActivationSnapshotChannelEntity>?,
        ): ActivationSnapshotEntity {
            events += "ready"
            stored = checkNotNull(stored).copy(state = ActivationCandidateState.READY_TO_ACTIVATE)
            return snapshot
        }

        override suspend fun activate(candidateId: String): ActiveSnapshotPointerEntity {
            events += "activate"
            stored = checkNotNull(stored).copy(state = ActivationCandidateState.ACTIVE)
            return ActiveSnapshotPointerEntity(snapshotId = "candidate-snapshot")
        }

        override suspend fun reject(candidateId: String, failureCode: String): Boolean {
            events += "reject"
            stored = checkNotNull(stored).copy(state = ActivationCandidateState.REJECTED, failureCode = failureCode)
            return true
        }
    }

    private companion object {
        const val Hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun pack() = ModelPackEntity(
            packId = "nayti-models",
            packVersion = "1.0.0",
            keyId = "b".repeat(32),
            manifestSha256 = Hash,
            relativeDirectory = "nayti-models/1.0.0-aaaaaaaaaaaaaaaa",
            payloadBytes = 1,
            installedAtMillis = 1,
            status = ModelPackStatus.INSTALLED_CANDIDATE,
        )

        fun request() = CandidateActivationRequest(
            candidateId = "candidate",
            snapshotId = "candidate-snapshot",
            pack = pack(),
            targetChannels = prepared(candidateEntity(ActivationCandidateState.BUILDING_SHADOW)).channels,
        )

        fun candidateEntity(state: String) = ActivationCandidateEntity(
            candidateId = "candidate",
            snapshotId = "candidate-snapshot",
            parentSnapshotId = "parent-snapshot",
            packId = pack().packId,
            packVersion = pack().packVersion,
            packManifestSha256 = pack().manifestSha256,
            capturedAccessRevision = 1,
            capturedCatalogWatermark = 2,
            state = state,
            createdAtMillis = 3,
            updatedAtMillis = 3,
            failureCode = null,
        )

        fun prepared(candidate: ActivationCandidateEntity): PreparedCandidateSnapshot {
            val channel = ActivationSnapshotChannelEntity(
                snapshotId = candidate.snapshotId,
                channel = IndexChannel.VISUAL,
                pipelineVersion = "visual-v1",
                componentHash = Hash,
                embeddingSpaceHash = Hash,
                generationId = "visual-generation",
                manifestRevision = "visual-manifest",
                inheritedFromSnapshotId = null,
            )
            return PreparedCandidateSnapshot(
                snapshot = ActivationSnapshotEntity(
                    snapshotId = candidate.snapshotId,
                    parentSnapshotId = candidate.parentSnapshotId,
                    packId = candidate.packId,
                    packVersion = candidate.packVersion,
                    packManifestSha256 = candidate.packManifestSha256,
                    engineContractVersion = 1,
                    rankingConfigVersion = "ranking-v1",
                    lexicalPublicationEpoch = 1,
                    pHashPublicationEpoch = 1,
                    semanticManifestRevision = null,
                    visualManifestRevision = channel.manifestRevision,
                    catalogWatermark = candidate.capturedCatalogWatermark,
                    createdAtMillis = 4,
                    capturedAccessRevision = candidate.capturedAccessRevision,
                ),
                channels = listOf(channel),
            )
        }
    }
}
