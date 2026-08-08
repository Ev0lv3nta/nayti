package app.nayti.ui.preparation

import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.platform.media.MediaAccessScope
import app.nayti.storage.IndexOperationState
import app.nayti.ui.shell.ShellStatusMapper
import app.nayti.ui.shell.ShellStatusUi

enum class PreparationPrimaryAction {
    RequestAccess,
    ImportModels,
    Start,
    Continue,
    Pause,
    RetryGaps,
    CheckAndContinue,
}

enum class PreparationIssue {
    Thermal,
    Memory,
    Storage,
    BatterySaver,
    BatteryLow,
    Charging,
    Runtime,
}

enum class PreparationQualitativeStatus {
    SearchWorksWhilePreparing,
    Preparing,
    Incomplete,
    Ready,
    CompletedWithGaps,
    Paused,
}

data class PreparationChannelUi(
    val capability: app.nayti.indexer.SearchCapability,
    val ready: Long,
    val total: Long,
    val gaps: Long,
    val outstanding: Long,
    val hasRuntimeCoverage: Boolean,
)

data class PreparationUiState(
    val status: ShellStatusUi,
    val qualitativeStatus: PreparationQualitativeStatus,
    val channels: List<PreparationChannelUi>,
    val primaryAction: PreparationPrimaryAction?,
    val issue: PreparationIssue?,
    val canCancel: Boolean,
    val canRetryGaps: Boolean,
    val periodChangeRequiresPause: Boolean,
    val isRunning: Boolean,
)

object PreparationUiMapper {
    fun map(
        catalog: CatalogRuntimeState,
        modelPack: ModelPackRuntimeState,
        indexing: OcrIndexingState,
    ): PreparationUiState {
        val accessMissing = catalog.access.permission.scope == MediaAccessScope.None
        val modelsMissing = modelPack.installed == null
        val isRunning = indexing.status == OcrIndexingStatus.Running
        val hasOutstandingWork =
            indexing.outstanding > 0 ||
                indexing.capabilities.any { coverage -> coverage.outstanding > 0 }
        val canRetryGaps =
            indexing.operationState == IndexOperationState.COMPLETED_WITH_GAPS &&
                indexing.permanentGaps > 0 &&
                !isRunning
        val canStart =
            !accessMissing &&
                !modelsMissing &&
                modelPack.status != ModelPackRuntimeStatus.Installing &&
                hasOutstandingWork &&
                !isRunning
        val issue = issue(indexing.errorCode)
        val primaryAction =
            when {
                accessMissing -> PreparationPrimaryAction.RequestAccess
                modelsMissing -> PreparationPrimaryAction.ImportModels
                isRunning -> PreparationPrimaryAction.Pause
                canRetryGaps -> PreparationPrimaryAction.RetryGaps
                canStart && issue != null -> PreparationPrimaryAction.CheckAndContinue
                canStart &&
                    indexing.operationState in ContinueOperationStates ->
                    PreparationPrimaryAction.Continue
                canStart -> PreparationPrimaryAction.Start
                else -> null
            }
        val canCancel =
            indexing.operationId != null &&
                indexing.operationState !in TerminalOperationStates

        return PreparationUiState(
            status = ShellStatusMapper.map(catalog, modelPack, indexing),
            qualitativeStatus = qualitativeStatus(indexing, issue),
            channels = channels(indexing),
            primaryAction = primaryAction,
            issue = issue,
            canCancel = canCancel,
            canRetryGaps = canRetryGaps,
            periodChangeRequiresPause = isRunning,
            isRunning = isRunning,
        )
    }

    private fun channels(indexing: OcrIndexingState): List<PreparationChannelUi> {
        val runtimeCoverage = indexing.capabilities.associateBy { coverage -> coverage.capability }
        return app.nayti.indexer.SearchCapability.entries.map { capability ->
            val coverage = runtimeCoverage[capability]
            PreparationChannelUi(
                capability = capability,
                ready = coverage?.committed ?: 0,
                total = coverage?.accessible ?: 0,
                gaps = coverage?.permanentGaps ?: 0,
                outstanding = coverage?.outstanding ?: 0,
                hasRuntimeCoverage = coverage != null,
            )
        }
    }

    private fun qualitativeStatus(
        indexing: OcrIndexingState,
        issue: PreparationIssue?,
    ): PreparationQualitativeStatus {
        val anyReady =
            indexing.committed > 0 ||
                indexing.capabilities.any { coverage -> coverage.committed > 0 }
        val hasGaps =
            indexing.permanentGaps > 0 ||
                indexing.capabilities.any { coverage -> coverage.permanentGaps > 0 }
        val hasOutstanding =
            indexing.outstanding > 0 ||
                indexing.capabilities.any { coverage -> coverage.outstanding > 0 }
        return when {
            issue != null -> PreparationQualitativeStatus.Paused
            indexing.status == OcrIndexingStatus.Running && anyReady ->
                PreparationQualitativeStatus.SearchWorksWhilePreparing
            indexing.status == OcrIndexingStatus.Running ->
                PreparationQualitativeStatus.Preparing
            indexing.status in setOf(
                OcrIndexingStatus.Paused,
                OcrIndexingStatus.Waiting,
                OcrIndexingStatus.Failed,
            ) -> PreparationQualitativeStatus.Paused
            indexing.operationState == IndexOperationState.COMPLETED_WITH_GAPS || hasGaps ->
                PreparationQualitativeStatus.CompletedWithGaps
            !hasOutstanding && anyReady -> PreparationQualitativeStatus.Ready
            else -> PreparationQualitativeStatus.Incomplete
        }
    }

    private fun issue(errorCode: String?): PreparationIssue? =
        when (errorCode) {
            null -> null
            "THERMAL_SEVERE" -> PreparationIssue.Thermal
            "MEMORY_PRESSURE" -> PreparationIssue.Memory
            "STORAGE_RESERVE" -> PreparationIssue.Storage
            "BATTERY_SAVER" -> PreparationIssue.BatterySaver
            "BATTERY_LOW" -> PreparationIssue.BatteryLow
            "CHARGING_REQUIRED" -> PreparationIssue.Charging
            else -> PreparationIssue.Runtime
        }

    private val TerminalOperationStates =
        setOf(
            IndexOperationState.COMPLETED,
            IndexOperationState.COMPLETED_WITH_GAPS,
            IndexOperationState.CANCELLED,
        )

    private val ContinueOperationStates =
        setOf(
            IndexOperationState.PAUSED_USER,
            IndexOperationState.WAITING_SYSTEM,
            IndexOperationState.PAUSED_CONSTRAINT,
            IndexOperationState.CANCELLED,
        )
}
