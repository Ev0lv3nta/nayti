package app.nayti.ui.shell

import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.CatalogSummary
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.indexer.SearchCapability
import app.nayti.indexer.SearchCapabilityCoverage
import app.nayti.platform.media.AccessRevision
import app.nayti.platform.media.MediaAccessScope
import app.nayti.platform.media.MediaPermissionSnapshot
import app.nayti.storage.IndexOperationState
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellStatusMapperTest {
    @Test
    fun accessRequiredTakesPriority() {
        val result = ShellStatusMapper.map(
            catalog = catalog(scope = MediaAccessScope.None),
            modelPack = modelPack(ModelPackRuntimeStatus.Missing),
            indexing = indexing(OcrIndexingStatus.Failed),
        )

        assertEquals(ShellStatusMessage.NeedsAccess, result.message)
    }

    @Test
    fun runningWithPublishedWorkSaysSearchAlreadyWorks() {
        val result = ShellStatusMapper.map(
            catalog = catalog(),
            modelPack = modelPack(),
            indexing = indexing(
                status = OcrIndexingStatus.Running,
                committed = 12,
                operationState = IndexOperationState.RUNNING,
            ),
        )

        assertEquals(ShellStatusMessage.SearchReadyPreparing, result.message)
        assertEquals(ShellStatusTone.Ready, result.tone)
    }

    @Test
    fun runningWithoutPublishedWorkOnlySaysPreparing() {
        val result = ShellStatusMapper.map(
            catalog = catalog(),
            modelPack = modelPack(),
            indexing = indexing(
                status = OcrIndexingStatus.Running,
                operationState = IndexOperationState.RUNNING,
            ),
        )

        assertEquals(ShellStatusMessage.Preparing, result.message)
    }

    @Test
    fun completedIsReadOnlyFromDurableOperationState() {
        val readyWithoutTerminalState = ShellStatusMapper.map(
            catalog = catalog(),
            modelPack = modelPack(),
            indexing = indexing(status = OcrIndexingStatus.Ready, committed = 100),
        )
        val completed = ShellStatusMapper.map(
            catalog = catalog(),
            modelPack = modelPack(),
            indexing = indexing(
                status = OcrIndexingStatus.Ready,
                committed = 100,
                operationState = IndexOperationState.COMPLETED,
            ),
        )

        assertEquals(ShellStatusMessage.SearchAvailable, readyWithoutTerminalState.message)
        assertEquals(ShellStatusMessage.Completed, completed.message)
    }

    @Test
    fun completedWithGapsRemainsAttentionState() {
        val result = ShellStatusMapper.map(
            catalog = catalog(),
            modelPack = modelPack(),
            indexing = indexing(
                status = OcrIndexingStatus.Ready,
                operationState = IndexOperationState.COMPLETED_WITH_GAPS,
            ),
        )

        assertEquals(ShellStatusMessage.CompletedWithGaps, result.message)
        assertEquals(ShellStatusTone.Attention, result.tone)
    }

    @Test
    fun channelCountersNeverInferCompletion() {
        val result = ShellStatusMapper.map(
            catalog = catalog(),
            modelPack = modelPack(),
            indexing = indexing(
                status = OcrIndexingStatus.Idle,
                committed = 100,
                capabilities = listOf(
                    SearchCapabilityCoverage(SearchCapability.TEXT, 100, 100, 0, 0),
                    SearchCapabilityCoverage(SearchCapability.MEANING, 100, 100, 0, 0),
                    SearchCapabilityCoverage(SearchCapability.VISUAL, 100, 100, 0, 0),
                    SearchCapabilityCoverage(SearchCapability.DUPLICATES, 100, 100, 0, 0),
                ),
            ),
        )

        assertEquals(ShellStatusMessage.SearchAvailable, result.message)
    }

    private fun catalog(
        scope: MediaAccessScope = MediaAccessScope.Full,
        status: CatalogRuntimeStatus = CatalogRuntimeStatus.Ready,
    ) = CatalogRuntimeState(
        status = status,
        access = AccessRevision(1, MediaPermissionSnapshot(scope, true, true)),
        summary = CatalogSummary.Empty,
        recentItems = emptyList(),
        lastErrorCode = null,
    )

    private fun modelPack(status: ModelPackRuntimeStatus = ModelPackRuntimeStatus.Ready) =
        ModelPackRuntimeState(
            status = status,
            installed = if (status == ModelPackRuntimeStatus.Ready) installedPack else null,
            candidate = null,
            errorCode = null,
        )

    private fun indexing(
        status: OcrIndexingStatus,
        committed: Long = 0,
        operationState: String? = null,
        capabilities: List<SearchCapabilityCoverage> = emptyList(),
    ) = OcrIndexingState(
        status = status,
        accessible = 100,
        committed = committed,
        permanentGaps = 0,
        outstanding = 100 - committed,
        lastSlicePublished = 0,
        errorCode = null,
        operationState = operationState,
        capabilities = capabilities,
    )

    private companion object {
        val installedPack = app.nayti.storage.ModelPackEntity(
            packId = "test-pack",
            packVersion = "1",
            keyId = "test-key",
            manifestSha256 = "0".repeat(64),
            relativeDirectory = "test-pack/1",
            payloadBytes = 1,
            installedAtMillis = 1,
            status = app.nayti.storage.ModelPackStatus.INSTALLED_CANDIDATE,
        )
    }
}
