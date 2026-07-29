package app.nayti.ui.preparation

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
import app.nayti.storage.IndexingScopeMode
import app.nayti.storage.IndexingScopeSummary
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.ui.shell.ShellStatusMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationUiMapperTest {
    @Test
    fun runtimeStateMatrixProducesConsistentStatusAndAction() {
        val scenarios =
            listOf(
                scenario(
                    "no access",
                    catalog = catalog(MediaAccessScope.None),
                    models = missingModels(),
                    indexing = indexing(OcrIndexingStatus.Idle),
                    message = ShellStatusMessage.NeedsAccess,
                    action = PreparationPrimaryAction.RequestAccess,
                ),
                scenario(
                    "no models",
                    models = missingModels(),
                    indexing = indexing(OcrIndexingStatus.Idle),
                    message = ShellStatusMessage.NeedsModels,
                    action = PreparationPrimaryAction.ImportModels,
                ),
                scenario(
                    "not started",
                    indexing = indexing(OcrIndexingStatus.Idle),
                    message = ShellStatusMessage.ReadyToPrepare,
                    action = PreparationPrimaryAction.Start,
                ),
                scenario(
                    "running before first publication",
                    indexing = indexing(OcrIndexingStatus.Running, operationState = IndexOperationState.RUNNING),
                    message = ShellStatusMessage.Preparing,
                    action = PreparationPrimaryAction.Pause,
                ),
                scenario(
                    "running with searchable work",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Running,
                            committed = 20,
                            operationState = IndexOperationState.RUNNING,
                        ),
                    message = ShellStatusMessage.SearchReadyPreparing,
                    action = PreparationPrimaryAction.Pause,
                ),
                scenario(
                    "paused by user",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Paused,
                            committed = 20,
                            operationState = IndexOperationState.PAUSED_USER,
                        ),
                    message = ShellStatusMessage.PausedByUser,
                    action = PreparationPrimaryAction.Continue,
                ),
                constraint("thermal", "THERMAL_SEVERE", ShellStatusMessage.PausedThermal),
                constraint("memory", "MEMORY_PRESSURE", ShellStatusMessage.PausedMemory),
                constraint("storage", "STORAGE_RESERVE", ShellStatusMessage.PausedStorage),
                constraint("battery saver", "BATTERY_SAVER", ShellStatusMessage.PausedBatterySaver),
                constraint("low battery", "BATTERY_LOW", ShellStatusMessage.PausedBatteryLow),
                constraint("charging", "CHARGING_REQUIRED", ShellStatusMessage.PausedCharging),
                scenario(
                    "waiting for Android",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Waiting,
                            committed = 20,
                            operationState = IndexOperationState.WAITING_SYSTEM,
                        ),
                    message = ShellStatusMessage.PausedBySystem,
                    action = PreparationPrimaryAction.Continue,
                ),
                scenario(
                    "completed with gaps",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Ready,
                            committed = 98,
                            permanentGaps = 2,
                            operationState = IndexOperationState.COMPLETED_WITH_GAPS,
                        ),
                    message = ShellStatusMessage.CompletedWithGaps,
                    action = PreparationPrimaryAction.RetryGaps,
                ),
                scenario(
                    "completed scoped period",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Ready,
                            committed = 100,
                            operationState = IndexOperationState.COMPLETED,
                            scoped = true,
                        ),
                    message = ShellStatusMessage.Completed,
                    action = null,
                ),
                scenario(
                    "completed whole library",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Ready,
                            committed = 100,
                            operationState = IndexOperationState.COMPLETED,
                        ),
                    message = ShellStatusMessage.Completed,
                    action = null,
                ),
                scenario(
                    "cancelled task keeps publications",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Ready,
                            committed = 20,
                            operationState = IndexOperationState.CANCELLED,
                        ),
                    message = ShellStatusMessage.PreparedPartially,
                    action = PreparationPrimaryAction.Continue,
                ),
                scenario(
                    "runtime failure",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Failed,
                            committed = 20,
                            errorCode = "RUNTIME_UNAVAILABLE",
                            operationState = IndexOperationState.REPAIR_REQUIRED,
                        ),
                    message = ShellStatusMessage.PreparationFailed,
                    action = PreparationPrimaryAction.CheckAndContinue,
                ),
                scenario(
                    "full counters without terminal state",
                    indexing =
                        indexing(
                            OcrIndexingStatus.Idle,
                            committed = 100,
                            capabilities = completeCapabilities(),
                        ),
                    message = ShellStatusMessage.SearchAvailable,
                    action = null,
                ),
            )

        assertEquals(19, scenarios.size)
        scenarios.forEach { scenario ->
            val result =
                PreparationUiMapper.map(
                    scenario.catalog,
                    scenario.models,
                    scenario.indexing,
                )
            assertEquals(scenario.name, scenario.message, result.status.message)
            assertEquals(scenario.name, scenario.action, result.primaryAction)
        }
    }

    @Test
    fun onlyRunningStateRequiresPauseBeforePeriodChange() {
        val running =
            PreparationUiMapper.map(
                catalog(),
                models(),
                indexing(OcrIndexingStatus.Running, operationState = IndexOperationState.RUNNING),
            )
        val paused =
            PreparationUiMapper.map(
                catalog(),
                models(),
                indexing(OcrIndexingStatus.Paused, operationState = IndexOperationState.PAUSED_USER),
            )

        assertTrue(running.periodChangeRequiresPause)
        assertFalse(paused.periodChangeRequiresPause)
    }

    @Test
    fun completionAndRetryAreNeverInferredFromCounters() {
        val countersOnly =
            PreparationUiMapper.map(
                catalog(),
                models(),
                indexing(
                    OcrIndexingStatus.Idle,
                    committed = 100,
                    permanentGaps = 3,
                    capabilities = completeCapabilities(),
                ),
            )

        assertEquals(ShellStatusMessage.SearchAvailable, countersOnly.status.message)
        assertNull(countersOnly.primaryAction)
        assertFalse(countersOnly.canRetryGaps)
    }

    @Test
    fun unfinishedChannelKeepsStartAvailableWhenLegacyCounterIsComplete() {
        val channels =
            completeCapabilities().map { coverage ->
                if (coverage.capability == SearchCapability.MEANING) {
                    coverage.copy(committed = 87, outstanding = 13)
                } else {
                    coverage
                }
            }
        val result =
            PreparationUiMapper.map(
                catalog(),
                models(),
                indexing(
                    OcrIndexingStatus.Idle,
                    committed = 100,
                    capabilities = channels,
                ),
            )

        assertEquals(ShellStatusMessage.SearchAvailable, result.status.message)
        assertEquals(PreparationPrimaryAction.Start, result.primaryAction)
    }

    private fun constraint(
        name: String,
        errorCode: String,
        message: ShellStatusMessage,
    ) = scenario(
        name = name,
        indexing =
            indexing(
                OcrIndexingStatus.Waiting,
                committed = 20,
                errorCode = errorCode,
                operationState = IndexOperationState.PAUSED_CONSTRAINT,
            ),
        message = message,
        action = PreparationPrimaryAction.CheckAndContinue,
    )

    private fun scenario(
        name: String,
        catalog: CatalogRuntimeState = catalog(),
        models: ModelPackRuntimeState = models(),
        indexing: OcrIndexingState,
        message: ShellStatusMessage,
        action: PreparationPrimaryAction?,
    ) = Scenario(name, catalog, models, indexing, message, action)

    private fun catalog(scope: MediaAccessScope = MediaAccessScope.Full) =
        CatalogRuntimeState(
            status = CatalogRuntimeStatus.Ready,
            access = AccessRevision(1, MediaPermissionSnapshot(scope, true, true)),
            summary = CatalogSummary.Empty.copy(available = 100),
            recentItems = emptyList(),
            lastErrorCode = null,
        )

    private fun models() =
        ModelPackRuntimeState(
            status = ModelPackRuntimeStatus.Ready,
            installed = InstalledPack,
            candidate = null,
            errorCode = null,
        )

    private fun missingModels() =
        ModelPackRuntimeState(
            status = ModelPackRuntimeStatus.Missing,
            installed = null,
            candidate = null,
            errorCode = null,
        )

    private fun indexing(
        status: OcrIndexingStatus,
        committed: Long = 0,
        permanentGaps: Long = 0,
        errorCode: String? = null,
        operationState: String? = null,
        scoped: Boolean = false,
        capabilities: List<SearchCapabilityCoverage> = emptyList(),
    ) = OcrIndexingState(
        status = status,
        accessible = 100,
        committed = committed,
        permanentGaps = permanentGaps,
        outstanding = (100 - committed - permanentGaps).coerceAtLeast(0),
        lastSlicePublished = 0,
        errorCode = errorCode,
        operationId = "operation",
        operationState = operationState,
        capabilities = capabilities,
        scope =
            IndexingScopeSummary(
                mode = if (scoped) IndexingScopeMode.SINCE_DATE else IndexingScopeMode.ALL,
                takenFromMillis = if (scoped) 1_700_000_000_000 else null,
                revision = 1,
                totalAvailable = 1_000,
                eligibleAssets = 100,
                unknownDateAssets = 0,
            ),
    )

    private fun completeCapabilities() =
        SearchCapability.entries.map { capability ->
            SearchCapabilityCoverage(capability, 100, 100, 0, 0)
        }

    private data class Scenario(
        val name: String,
        val catalog: CatalogRuntimeState,
        val models: ModelPackRuntimeState,
        val indexing: OcrIndexingState,
        val message: ShellStatusMessage,
        val action: PreparationPrimaryAction?,
    )

    private companion object {
        val InstalledPack =
            ModelPackEntity(
                packId = "test-pack",
                packVersion = "1",
                keyId = "test-key",
                manifestSha256 = "0".repeat(64),
                relativeDirectory = "test-pack/1",
                payloadBytes = 1,
                installedAtMillis = 1,
                status = ModelPackStatus.INSTALLED_CANDIDATE,
            )
    }
}
