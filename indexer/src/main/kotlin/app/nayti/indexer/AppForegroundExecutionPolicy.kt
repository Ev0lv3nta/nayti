package app.nayti.indexer

import app.nayti.storage.IndexOperationEntity
import app.nayti.storage.IndexOperationState

object AppForegroundExecutionPolicy {
    fun canResume(operation: IndexOperationEntity?): Boolean =
        operation?.autoResume == true && operation.state in AutoResumableStates

    private val AutoResumableStates =
        setOf(
            IndexOperationState.PLANNED,
            IndexOperationState.RUNNING,
            IndexOperationState.PAUSED_CONSTRAINT,
            IndexOperationState.WAITING_SYSTEM,
        )
}
