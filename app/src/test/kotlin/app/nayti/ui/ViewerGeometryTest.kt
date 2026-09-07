package app.nayti.ui

import app.nayti.ui.viewer.ViewerPan
import app.nayti.ui.viewer.boundedViewerPan
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerGeometryTest {
    @Test
    fun letterboxedPhotoCannotMoveAlongAnAxisSmallerThanViewport() {
        assertEquals(ViewerPan(150f, 0f), boundedViewerPan(999f, 999f, 2f, 300, 600, 1200, 600))
    }

    @Test
    fun zoomOutAndRotationReclampBothDirections() {
        assertEquals(ViewerPan(0f, 0f), boundedViewerPan(-900f, 900f, 1f, 300, 600, 600, 1200))
        assertEquals(ViewerPan(-150f, 300f), boundedViewerPan(-900f, 900f, 2f, 300, 600, 600, 1200))
        assertEquals(ViewerPan(0f, 150f), boundedViewerPan(-900f, 900f, 2f, 600, 300, 600, 1200))
    }
}
