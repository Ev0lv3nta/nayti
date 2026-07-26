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
    Pause,
    RetryGaps,
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

data class PreparationUiState(
    val status: ShellStatusUi,
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
        val primaryAction =
            when {
                accessMissing -> PreparationPrimaryAction.RequestAccess
                modelsMissing -> PreparationPrimaryAction.ImportModels
                isRunning -> PreparationPrimaryAction.Pause
                canRetryGaps -> PreparationPrimaryAction.RetryGaps
                canStart -> PreparationPrimaryAction.Start
                else -> null
            }
        val canCancel =
            indexing.operationId != null &&
                indexing.operationState !in TerminalOperationStates

        return PreparationUiState(
            status = ShellStatusMapper.map(catalog, modelPack, indexing),
            primaryAction = primaryAction,
            issue = issue(indexing.errorCode),
            canCancel = canCancel,
            canRetryGaps = canRetryGaps,
            periodChangeRequiresPause = isRunning,
            isRunning = isRunning,
        )
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
}
