package app.nayti.indexer

import app.nayti.storage.IndexOperationEntity
import app.nayti.storage.IndexOperationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundExecutionPolicyTest {
    @Test
    fun `continues only auto resumable operation states`() {
        listOf(
            IndexOperationState.PLANNED,
            IndexOperationState.RUNNING,
            IndexOperationState.PAUSED_CONSTRAINT,
            IndexOperationState.WAITING_SYSTEM,
        ).forEach { state ->
            assertTrue(AppForegroundExecutionPolicy.canResume(operation(state, autoResume = true)))
        }
    }

    @Test
    fun `does not bypass explicit pause stop or terminal states`() {
        assertFalse(AppForegroundExecutionPolicy.canResume(null))
        assertFalse(
            AppForegroundExecutionPolicy.canResume(
                operation(IndexOperationState.PAUSED_USER, autoResume = false),
            ),
        )
        assertFalse(
            AppForegroundExecutionPolicy.canResume(
                operation(IndexOperationState.WAITING_SYSTEM, autoResume = false),
            ),
        )
        listOf(
            IndexOperationState.CANCELLED,
            IndexOperationState.COMPLETED,
            IndexOperationState.COMPLETED_WITH_GAPS,
            IndexOperationState.REPAIR_REQUIRED,
        ).forEach { state ->
            assertFalse(AppForegroundExecutionPolicy.canResume(operation(state, autoResume = true)))
        }
    }

    private fun operation(state: String, autoResume: Boolean) =
        IndexOperationEntity(
            operationId = "operation-1",
            profileId = "balanced-v1",
            targetPackId = "offline-search",
            targetPackVersion = "1",
            denominatorCatalogRevision = 1,
            denominatorAssetCount = 1,
            state = state,
            autoResume = autoResume,
            createdAtMillis = 1,
            updatedAtMillis = 1,
            completedAtMillis = null,
        )
}
