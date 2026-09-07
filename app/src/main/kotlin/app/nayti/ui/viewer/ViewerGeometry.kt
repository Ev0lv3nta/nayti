package app.nayti.ui.viewer

import kotlin.math.min

internal data class ViewerPan(val x: Float, val y: Float)

/** Bounds for a centred ContentScale.Fit image, including its letterboxing. */
internal fun boundedViewerPan(
    x: Float, y: Float, scale: Float,
    viewportWidth: Int, viewportHeight: Int, imageWidth: Int, imageHeight: Int,
): ViewerPan {
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return ViewerPan(0f, 0f)
    }
    val fit = min(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
    val limitX = ((imageWidth * fit * scale - viewportWidth) / 2f).coerceAtLeast(0f)
    val limitY = ((imageHeight * fit * scale - viewportHeight) / 2f).coerceAtLeast(0f)
    return ViewerPan(
        if (limitX == 0f) 0f else x.coerceIn(-limitX, limitX),
        if (limitY == 0f) 0f else y.coerceIn(-limitY, limitY),
    )
}
