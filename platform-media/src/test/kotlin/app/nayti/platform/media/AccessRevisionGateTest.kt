package app.nayti.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRevisionGateTest {
    @Test
    fun revisionChangesBeforeReconciliationCanBegin() {
        var observed = permission(MediaAccessScope.Full)
        val gate = AccessRevisionGate(observed) { observed }
        val queryPin = gate.pin()

        observed = permission(MediaAccessScope.Selected)
        val narrowed = gate.refresh()

        assertEquals(2, narrowed.value)
        assertEquals(MediaAccessScope.Selected, narrowed.permission.scope)
        assertFalse(gate.isCurrent(queryPin))
        assertTrue(gate.isCurrent(narrowed))
        assertEquals(narrowed, gate.refresh())
    }

    @Test
    fun explicitSelectionResultInvalidatesPinsWhenPermissionFlagsStaySelected() {
        val observed = permission(MediaAccessScope.Selected)
        val gate = AccessRevisionGate(observed) { observed }
        val previousSelection = gate.pin()

        val replacementSelection = gate.invalidate()

        assertEquals(2, replacementSelection.value)
        assertEquals(previousSelection.permission, replacementSelection.permission)
        assertFalse(gate.isCurrent(previousSelection))
        assertTrue(gate.isCurrent(replacementSelection))
    }

    private fun permission(scope: MediaAccessScope) =
        MediaPermissionSnapshot(
            scope = scope,
            readImagesGranted = scope == MediaAccessScope.Full,
            selectedImagesGranted = scope == MediaAccessScope.Selected,
        )
}
