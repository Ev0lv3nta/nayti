package app.nayti.ui.shell

import app.nayti.indexer.CatalogRuntimeState
import app.nayti.indexer.CatalogRuntimeStatus
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingState
import app.nayti.indexer.OcrIndexingStatus
import app.nayti.platform.media.MediaAccessScope
import app.nayti.storage.IndexOperationState

enum class ShellStatusTone {
    Neutral,
    Ready,
    Attention,
    Error,
}

enum class ShellStatusMessage {
    CheckingLibrary,
    NeedsAccess,
    NeedsModels,
    CheckingModels,
    InstallingModels,
    ModelsFailed,
    LibraryFailed,
    SearchReadyPreparing,
    Preparing,
    ReadyToPrepare,
    SearchAvailable,
    PreparedPartially,
    PausedByUser,
    PausedBySystem,
    PausedByConstraint,
    PausedThermal,
    PausedMemory,
    PausedStorage,
    PausedBatterySaver,
    PausedBatteryLow,
    PausedCharging,
    Completed,
    CompletedWithGaps,
    PreparationFailed,
}

data class ShellStatusUi(
    val message: ShellStatusMessage,
    val tone: ShellStatusTone,
    val actionable: Boolean,
)

/**
 * Produces the compact shell status from authoritative runtime state.
 *
 * This mapper intentionally does not calculate a combined percentage or infer completion from
 * channel counters. Exact progress remains channel-specific; completion is shown only for the
 * durable terminal operation states.
 */
object ShellStatusMapper {
    fun map(
        catalog: CatalogRuntimeState,
        modelPack: ModelPackRuntimeState,
        indexing: OcrIndexingState,
    ): ShellStatusUi {
        if (catalog.access.permission.scope == MediaAccessScope.None) {
            return ShellStatusUi(
                message = ShellStatusMessage.NeedsAccess,
                tone = ShellStatusTone.Attention,
                actionable = true,
            )
        }
        if (catalog.status == CatalogRuntimeStatus.Failed) {
            return ShellStatusUi(
                message = ShellStatusMessage.LibraryFailed,
                tone = ShellStatusTone.Error,
                actionable = true,
            )
        }

        when (modelPack.status) {
            ModelPackRuntimeStatus.Loading -> return ShellStatusUi(
                ShellStatusMessage.CheckingModels,
                ShellStatusTone.Neutral,
                actionable = false,
            )
            ModelPackRuntimeStatus.Missing -> return ShellStatusUi(
                ShellStatusMessage.NeedsModels,
                ShellStatusTone.Attention,
                actionable = true,
            )
            ModelPackRuntimeStatus.Installing -> return ShellStatusUi(
                ShellStatusMessage.InstallingModels,
                ShellStatusTone.Neutral,
                actionable = true,
            )
            ModelPackRuntimeStatus.Failed -> if (modelPack.installed == null) {
                return ShellStatusUi(
                    ShellStatusMessage.ModelsFailed,
                    ShellStatusTone.Error,
                    actionable = true,
                )
            }
            ModelPackRuntimeStatus.Ready -> Unit
        }

        if (catalog.status in setOf(CatalogRuntimeStatus.Idle, CatalogRuntimeStatus.Reconciling)) {
            return ShellStatusUi(
                ShellStatusMessage.CheckingLibrary,
                ShellStatusTone.Neutral,
                actionable = false,
            )
        }

        return mapPreparation(indexing)
    }

    fun mapPreparation(indexing: OcrIndexingState): ShellStatusUi {
        val activeConstraint = constraintMessageOrNull(indexing.errorCode)
        if (
            activeConstraint != null &&
            indexing.operationState !in TerminalOperationStates
        ) {
            return ShellStatusUi(
                activeConstraint,
                ShellStatusTone.Attention,
                actionable = true,
            )
        }
        return when (indexing.operationState) {
            IndexOperationState.COMPLETED -> ShellStatusUi(
                ShellStatusMessage.Completed,
                ShellStatusTone.Ready,
                actionable = true,
            )
            IndexOperationState.COMPLETED_WITH_GAPS -> ShellStatusUi(
                ShellStatusMessage.CompletedWithGaps,
                ShellStatusTone.Attention,
                actionable = true,
            )
            IndexOperationState.PAUSED_USER -> ShellStatusUi(
                ShellStatusMessage.PausedByUser,
                ShellStatusTone.Attention,
                actionable = true,
            )
            IndexOperationState.PAUSED_CONSTRAINT -> ShellStatusUi(
                constraintMessage(indexing.errorCode),
                ShellStatusTone.Attention,
                actionable = true,
            )
            IndexOperationState.WAITING_SYSTEM -> ShellStatusUi(
                ShellStatusMessage.PausedBySystem,
                ShellStatusTone.Attention,
                actionable = true,
            )
            IndexOperationState.REPAIR_REQUIRED -> ShellStatusUi(
                ShellStatusMessage.PreparationFailed,
                ShellStatusTone.Error,
                actionable = true,
            )
            IndexOperationState.CANCELLED -> ShellStatusUi(
                ShellStatusMessage.PreparedPartially,
                ShellStatusTone.Attention,
                actionable = true,
            )
            else -> mapNonTerminal(indexing)
        }
    }

    private fun mapNonTerminal(indexing: OcrIndexingState): ShellStatusUi {
        val hasPublishedWork =
            indexing.committed > 0 || indexing.capabilities.any { capability -> capability.committed > 0 }
        return when (indexing.status) {
            OcrIndexingStatus.Running -> ShellStatusUi(
                message = if (hasPublishedWork) {
                    ShellStatusMessage.SearchReadyPreparing
                } else {
                    ShellStatusMessage.Preparing
                },
                tone = if (hasPublishedWork) ShellStatusTone.Ready else ShellStatusTone.Neutral,
                actionable = true,
            )
            OcrIndexingStatus.Paused -> ShellStatusUi(
                ShellStatusMessage.PreparedPartially,
                ShellStatusTone.Attention,
                actionable = true,
            )
            OcrIndexingStatus.Waiting -> ShellStatusUi(
                if (indexing.operationState == IndexOperationState.PAUSED_CONSTRAINT) {
                    constraintMessage(indexing.errorCode)
                } else {
                    ShellStatusMessage.PausedBySystem
                },
                ShellStatusTone.Attention,
                actionable = true,
            )
            OcrIndexingStatus.Failed -> ShellStatusUi(
                ShellStatusMessage.PreparationFailed,
                ShellStatusTone.Error,
                actionable = true,
            )
            OcrIndexingStatus.Ready -> ShellStatusUi(
                if (hasPublishedWork) ShellStatusMessage.SearchAvailable else ShellStatusMessage.ReadyToPrepare,
                if (hasPublishedWork) ShellStatusTone.Ready else ShellStatusTone.Neutral,
                actionable = true,
            )
            OcrIndexingStatus.Idle -> ShellStatusUi(
                if (hasPublishedWork) ShellStatusMessage.SearchAvailable else ShellStatusMessage.ReadyToPrepare,
                if (hasPublishedWork) ShellStatusTone.Ready else ShellStatusTone.Neutral,
                actionable = true,
            )
        }
    }

    private fun constraintMessage(errorCode: String?): ShellStatusMessage =
        constraintMessageOrNull(errorCode) ?: ShellStatusMessage.PausedByConstraint

    private fun constraintMessageOrNull(errorCode: String?): ShellStatusMessage? =
        when (errorCode) {
            "THERMAL_SEVERE" -> ShellStatusMessage.PausedThermal
            "MEMORY_PRESSURE" -> ShellStatusMessage.PausedMemory
            "STORAGE_RESERVE" -> ShellStatusMessage.PausedStorage
            "BATTERY_SAVER" -> ShellStatusMessage.PausedBatterySaver
            "BATTERY_LOW" -> ShellStatusMessage.PausedBatteryLow
            "CHARGING_REQUIRED" -> ShellStatusMessage.PausedCharging
            else -> null
        }

    private val TerminalOperationStates =
        setOf(
            IndexOperationState.COMPLETED,
            IndexOperationState.COMPLETED_WITH_GAPS,
            IndexOperationState.CANCELLED,
        )
}
