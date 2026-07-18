package app.nayti.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupPolicyTest {
    @Test
    fun setupOrdersPackAccessCatalogAndExplicitPreparation() {
        val empty = snapshot()
        assertEquals(SetupNextAction.IMPORT_MODEL_PACK, SetupPolicy.next(empty))
        assertEquals(
            SetupNextAction.WAIT_FOR_MODEL_PACK,
            SetupPolicy.next(empty.copy(modelPackBusy = true)),
        )
        assertEquals(
            SetupNextAction.REQUEST_PHOTO_ACCESS,
            SetupPolicy.next(empty.copy(modelPackReady = true)),
        )
        assertEquals(
            SetupNextAction.WAIT_FOR_CATALOG,
            SetupPolicy.next(
                empty.copy(
                    modelPackReady = true,
                    photoAccessGranted = true,
                    catalogReconciling = true,
                ),
            ),
        )
        assertEquals(
            SetupNextAction.START_PREPARATION,
            SetupPolicy.next(
                empty.copy(
                    modelPackReady = true,
                    photoAccessGranted = true,
                    availablePhotos = 20,
                ),
            ),
        )
    }

    @Test
    fun setupNeverForcesPreparationForAnEmptyLibrary() {
        assertEquals(
            SetupNextAction.ENTER_APP,
            SetupPolicy.next(
                snapshot().copy(
                    modelPackReady = true,
                    photoAccessGranted = true,
                    availablePhotos = 0,
                ),
            ),
        )
    }

    @Test
    fun setupEntersOnlyAfterCurrentCoverageHasNoOutstandingWork() {
        val indexed =
            snapshot().copy(
                modelPackReady = true,
                photoAccessGranted = true,
                availablePhotos = 20,
                indexingAccessible = 20,
            )
        assertEquals(
            SetupNextAction.START_PREPARATION,
            SetupPolicy.next(indexed.copy(indexingOutstanding = 1)),
        )
        assertEquals(SetupNextAction.ENTER_APP, SetupPolicy.next(indexed))
    }

    private fun snapshot() =
        SetupSnapshot(
            modelPackReady = false,
            modelPackBusy = false,
            photoAccessGranted = false,
            catalogReconciling = false,
            availablePhotos = 0,
            indexingRunning = false,
            indexingAccessible = 0,
            indexingOutstanding = 0,
        )
}
