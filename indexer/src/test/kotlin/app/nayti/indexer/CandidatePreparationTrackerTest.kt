package app.nayti.indexer

import app.nayti.storage.ActivationCandidateEntity
import app.nayti.storage.ActivationCandidateState
import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexingScopeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidatePreparationTrackerTest {
    @Test
    fun durableCandidateProgressIsPublishedWhileStopped() {
        val state = candidatePreparationState(
            candidate = candidate(ActivationCandidateState.BUILDING_SHADOW),
            running = false,
            failureCode = null,
            capabilities =
                listOf(
                    coverage(SearchCapability.TEXT, committed = 240, outstanding = 1_171),
                    coverage(SearchCapability.VISUAL, committed = 0, outstanding = 1_411),
                ),
            scope = Scope,
        )

        assertEquals(OcrIndexingStatus.Waiting, state.status)
        assertEquals(240L, state.capabilities.single { it.capability == SearchCapability.TEXT }.committed)
        assertEquals(1_411L, state.outstanding)
        assertNull(state.operationId)
        assertNull(state.errorCode)
    }

    @Test
    fun runningCandidateControlsTheVisiblePreparationState() {
        val state = candidatePreparationState(
            candidate = candidate(ActivationCandidateState.BUILDING_SHADOW),
            running = true,
            failureCode = null,
            capabilities = listOf(coverage(SearchCapability.VISUAL, committed = 12, outstanding = 88)),
            scope = Scope,
        )

        assertEquals(OcrIndexingStatus.Running, state.status)
        assertEquals(OcrExecutionHost.UserForegroundService, state.hostType)
    }

    @Test
    fun rejectedCandidateSurfacesItsDurableFailure() {
        val state = candidatePreparationState(
            candidate = candidate(ActivationCandidateState.REJECTED, failureCode = "ACTIVATION_FAILED"),
            running = false,
            failureCode = null,
            capabilities = listOf(coverage(SearchCapability.VISUAL, committed = 12, outstanding = 88)),
            scope = Scope,
        )

        assertEquals(OcrIndexingStatus.Failed, state.status)
        assertEquals("ACTIVATION_FAILED", state.errorCode)
    }

    private fun candidate(state: String, failureCode: String? = null) =
        ActivationCandidateEntity(
            candidateId = "candidate",
            snapshotId = "snapshot",
            parentSnapshotId = "parent",
            packId = "nayti-models",
            packVersion = "1.0.0",
            packManifestSha256 = "a".repeat(64),
            capturedAccessRevision = 1,
            capturedCatalogWatermark = 1,
            state = state,
            createdAtMillis = 1,
            updatedAtMillis = 1,
            failureCode = failureCode,
        )

    private fun coverage(
        capability: SearchCapability,
        committed: Long,
        outstanding: Long,
    ) = SearchCapabilityCoverage(
        capability = capability,
        accessible = committed + outstanding,
        committed = committed,
        permanentGaps = 0,
        outstanding = outstanding,
    )

    private companion object {
        val Scope =
            IndexingScopeSummary(
                mode = IndexingScopeMode.SINCE_DATE,
                takenFromMillis = 1,
                revision = 2,
                totalAvailable = 13_941,
                eligibleAssets = 1_411,
                unknownDateAssets = 0,
            )
    }
}
