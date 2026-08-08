package app.nayti.indexer

import app.nayti.storage.IndexOperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexingErrorCodeRestorationTest {
    @Test
    fun batterySaverReasonIsRestoredWhileConstraintRemainsActive() {
        assertEquals(
            "BATTERY_SAVER",
            resolveIndexingErrorCode(
                operationState = IndexOperationState.PAUSED_CONSTRAINT,
                explicitErrorCode = null,
                currentResourceDecision =
                    IndexResourceDecision(
                        canContinue = false,
                        constraintCode = "BATTERY_SAVER",
                    ),
            ),
        )
    }

    @Test
    fun batterySaverReasonSurvivesAPlannedTransitionProjection() {
        assertEquals(
            "BATTERY_SAVER",
            resolveIndexingErrorCode(
                operationState = IndexOperationState.PLANNED,
                explicitErrorCode = null,
                currentResourceDecision =
                    IndexResourceDecision(
                        canContinue = false,
                        constraintCode = "BATTERY_SAVER",
                    ),
            ),
        )
    }

    @Test
    fun batterySaverReasonIsAvailableBeforeTheFirstExecutionWindowExists() {
        assertEquals(
            "BATTERY_SAVER",
            resolveIndexingErrorCode(
                operationState = null,
                explicitErrorCode = null,
                currentResourceDecision =
                    IndexResourceDecision(
                        canContinue = false,
                        constraintCode = "BATTERY_SAVER",
                    ),
            ),
        )
    }

    @Test
    fun staleConstraintReasonIsNotRestoredAfterConstraintClears() {
        assertNull(
            resolveIndexingErrorCode(
                operationState = IndexOperationState.PAUSED_CONSTRAINT,
                explicitErrorCode = null,
                currentResourceDecision = IndexResourceDecision(canContinue = true),
            ),
        )
    }

    @Test
    fun explicitReasonWinsDuringTheStoppingTransition() {
        assertEquals(
            "BATTERY_SAVER",
            resolveIndexingErrorCode(
                operationState = IndexOperationState.PAUSED_CONSTRAINT,
                explicitErrorCode = "BATTERY_SAVER",
                currentResourceDecision = null,
            ),
        )
    }

    @Test
    fun userPauseIsNotReplacedByAnUnrelatedSystemConstraint() {
        assertNull(
            resolveIndexingErrorCode(
                operationState = IndexOperationState.PAUSED_USER,
                explicitErrorCode = null,
                currentResourceDecision =
                    IndexResourceDecision(
                        canContinue = false,
                        constraintCode = "BATTERY_SAVER",
                    ),
            ),
        )
    }
}
