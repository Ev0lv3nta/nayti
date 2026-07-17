package app.nayti.ml.runtime.ocr

import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class OcrPoint(
    val x: Float,
    val y: Float,
)

data class OcrQuadrilateral(
    val topLeft: OcrPoint,
    val topRight: OcrPoint,
    val bottomRight: OcrPoint,
    val bottomLeft: OcrPoint,
) {
    val points: List<OcrPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

data class DetectedTextRegion(
    val quadrilateral: OcrQuadrilateral,
    val confidence: Float,
) {
    init {
        require(confidence in 0.0f..1.0f)
    }
}

/** Bounded DB-style postprocessing for a single detector probability map. */
class OcrDetectorPostprocessor : AutoCloseable {
    private var stateScratch = ByteArray(0)
    private var queueScratch = IntArray(0)
    private var closed = false

    @Synchronized
    fun process(
        probabilities: FloatBuffer,
        outputWidth: Int,
        outputHeight: Int,
        resize: DetectorResizePlan,
    ): List<DetectedTextRegion> {
        require(outputWidth == resize.tensorWidth && outputHeight == resize.tensorHeight) {
            "Detector output shape does not match its resize evidence"
        }
        val pixelCount = Math.multiplyExact(outputWidth, outputHeight)
        require(probabilities.remaining() == pixelCount) { "Unexpected detector probability count" }
        val values = probabilities.duplicate()
        val valueOffset = values.position()
        check(!closed) { "Detector postprocessor is closed" }
        if (stateScratch.size < pixelCount) stateScratch = ByteArray(pixelCount)
        if (queueScratch.size < pixelCount) queueScratch = IntArray(pixelCount)
        val state = stateScratch
        Arrays.fill(state, 0, pixelCount, Inactive)
        for (index in 0 until pixelCount) {
            val probability = values.get(valueOffset + index)
            require(probability.isFinite() && probability in 0.0f..1.0f) {
                "Detector probability map contains an invalid value"
            }
            if (probability >= OcrDetectorContract.ProbabilityThreshold) state[index] = Active
        }

        val queue = queueScratch
        val candidates = ArrayList<DetectedTextRegion>()
        var tracedComponents = 0
        scan@ for (start in 0 until pixelCount) {
            if (state[start] != Active) continue
            if (tracedComponents >= OcrDetectorContract.MaximumCandidates) break@scan
            tracedComponents++
            val component =
                traceComponent(
                    start = start,
                    width = outputWidth,
                    height = outputHeight,
                    state = state,
                    queue = queue,
                )
            if (component.pixelCount < MinimumComponentPixels || component.boundary.size < 3) continue
            val hull = convexHull(component.boundary)
            if (hull.size < 3) continue
            val confidence = polygonScore(hull, values, valueOffset, outputWidth, outputHeight)
            if (confidence < OcrDetectorContract.BoxThreshold) continue
            val rectangle = minimumAreaRectangle(hull) ?: continue
            if (min(rectangle.width, rectangle.height) < MinimumBoxSide) continue
            val expanded = rectangle.expanded(OcrDetectorContract.UnclipRatio)
            val mapped = expanded.corners().map { point ->
                DoublePoint(
                    x = (point.x / resize.widthScale).coerceIn(0.0, resize.sourceWidth - 1.0),
                    y = (point.y / resize.heightScale).coerceIn(0.0, resize.sourceHeight - 1.0),
                )
            }
            candidates += DetectedTextRegion(mapped.toClockwiseQuadrilateral(), confidence)
        }
        return candidates.sortedWith(
            compareBy<DetectedTextRegion> { region -> region.quadrilateral.points.sumOf { it.y.toDouble() } / 4.0 }
                .thenBy { region -> region.quadrilateral.points.sumOf { it.x.toDouble() } / 4.0 },
        )
    }

    @Synchronized
    override fun close() {
        closed = true
        stateScratch = ByteArray(0)
        queueScratch = IntArray(0)
    }

    private fun traceComponent(
        start: Int,
        width: Int,
        height: Int,
        state: ByteArray,
        queue: IntArray,
    ): Component {
        var head = 0
        var tail = 0
        queue[tail++] = start
        state[start] = Visited
        val boundary = PackedPointBuffer()
        var pixels = 0
        while (head < tail) {
            val index = queue[head++]
            pixels++
            val x = index % width
            val y = index / width
            if (
                x == 0 || x == width - 1 || y == 0 || y == height - 1 ||
                state[index - 1] == Inactive || state[index + 1] == Inactive ||
                state[index - width] == Inactive || state[index + width] == Inactive
            ) {
                boundary.add(x, y)
            }
            for (neighborY in max(0, y - 1)..min(height - 1, y + 1)) {
                val row = neighborY * width
                for (neighborX in max(0, x - 1)..min(width - 1, x + 1)) {
                    val neighbor = row + neighborX
                    if (state[neighbor] == Active) {
                        state[neighbor] = Visited
                        queue[tail++] = neighbor
                    }
                }
            }
        }
        return Component(pixels, boundary.toArray())
    }

    private fun polygonScore(
        hull: List<DoublePoint>,
        values: FloatBuffer,
        valueOffset: Int,
        width: Int,
        height: Int,
    ): Float {
        val minimumX = max(0, floor(hull.minOf(DoublePoint::x)).toInt())
        val maximumX = min(width - 1, ceil(hull.maxOf(DoublePoint::x)).toInt())
        val minimumY = max(0, floor(hull.minOf(DoublePoint::y)).toInt())
        val maximumY = min(height - 1, ceil(hull.maxOf(DoublePoint::y)).toInt())
        var total = 0.0
        var count = 0
        for (y in minimumY..maximumY) {
            for (x in minimumX..maximumX) {
                if (isInsideConvexPolygon(x + 0.5, y + 0.5, hull)) {
                    total += values.get(valueOffset + y * width + x)
                    count++
                }
            }
        }
        return if (count == 0) 0.0f else (total / count).toFloat().coerceIn(0.0f, 1.0f)
    }

    private fun isInsideConvexPolygon(x: Double, y: Double, polygon: List<DoublePoint>): Boolean {
        var sign = 0
        for (index in polygon.indices) {
            val first = polygon[index]
            val second = polygon[(index + 1) % polygon.size]
            val cross = (second.x - first.x) * (y - first.y) - (second.y - first.y) * (x - first.x)
            if (kotlin.math.abs(cross) <= GeometryEpsilon) continue
            val current = if (cross > 0.0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    private companion object {
        const val Inactive: Byte = 0
        const val Active: Byte = 1
        const val Visited: Byte = 2
        const val MinimumComponentPixels = 3
        const val MinimumBoxSide = 3.0
        const val GeometryEpsilon = 1e-8
    }
}

private data class Component(
    val pixelCount: Int,
    val boundary: LongArray,
)

private class PackedPointBuffer {
    private var values = LongArray(64)
    private var size = 0

    fun add(x: Int, y: Int) {
        if (size == values.size) values = values.copyOf(Math.multiplyExact(values.size, 2))
        values[size++] = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    }

    fun toArray(): LongArray = values.copyOf(size)
}

private data class DoublePoint(
    val x: Double,
    val y: Double,
)

private fun convexHull(packedPoints: LongArray): List<DoublePoint> {
    Arrays.sort(packedPoints)
    val unique = ArrayList<DoublePoint>(packedPoints.size)
    var previous: Long? = null
    for (packed in packedPoints) {
        if (packed == previous) continue
        unique += DoublePoint((packed shr 32).toDouble(), packed.toInt().toDouble())
        previous = packed
    }
    if (unique.size <= 2) return unique
    val hull = ArrayList<DoublePoint>(unique.size * 2)
    for (point in unique) {
        while (hull.size >= 2 && cross(hull[hull.lastIndex - 1], hull.last(), point) <= 0.0) {
            hull.removeAt(hull.lastIndex)
        }
        hull += point
    }
    val lowerSize = hull.size
    for (index in unique.lastIndex - 1 downTo 0) {
        val point = unique[index]
        while (hull.size > lowerSize && cross(hull[hull.lastIndex - 1], hull.last(), point) <= 0.0) {
            hull.removeAt(hull.lastIndex)
        }
        hull += point
    }
    hull.removeAt(hull.lastIndex)
    return hull
}

private fun cross(origin: DoublePoint, first: DoublePoint, second: DoublePoint): Double =
    (first.x - origin.x) * (second.y - origin.y) -
        (first.y - origin.y) * (second.x - origin.x)

private data class OrientedRectangle(
    val axisX: Double,
    val axisY: Double,
    val perpendicularX: Double,
    val perpendicularY: Double,
    val minimumAlong: Double,
    val maximumAlong: Double,
    val minimumAcross: Double,
    val maximumAcross: Double,
) {
    val width: Double = maximumAlong - minimumAlong
    val height: Double = maximumAcross - minimumAcross

    fun expanded(ratio: Float): OrientedRectangle {
        val perimeter = 2.0 * (width + height)
        if (perimeter <= 0.0) return this
        val distance = width * height * ratio / perimeter
        return copy(
            minimumAlong = minimumAlong - distance,
            maximumAlong = maximumAlong + distance,
            minimumAcross = minimumAcross - distance,
            maximumAcross = maximumAcross + distance,
        )
    }

    fun corners(): List<DoublePoint> =
        listOf(
            point(minimumAlong, minimumAcross),
            point(maximumAlong, minimumAcross),
            point(maximumAlong, maximumAcross),
            point(minimumAlong, maximumAcross),
        )

    private fun point(along: Double, across: Double): DoublePoint =
        DoublePoint(
            x = axisX * along + perpendicularX * across,
            y = axisY * along + perpendicularY * across,
        )
}

private fun minimumAreaRectangle(hull: List<DoublePoint>): OrientedRectangle? {
    var best: OrientedRectangle? = null
    var bestArea = Double.POSITIVE_INFINITY
    for (index in hull.indices) {
        val first = hull[index]
        val second = hull[(index + 1) % hull.size]
        val deltaX = second.x - first.x
        val deltaY = second.y - first.y
        val length = hypot(deltaX, deltaY)
        if (length <= 0.0) continue
        val axisX = deltaX / length
        val axisY = deltaY / length
        val perpendicularX = -axisY
        val perpendicularY = axisX
        var minimumAlong = Double.POSITIVE_INFINITY
        var maximumAlong = Double.NEGATIVE_INFINITY
        var minimumAcross = Double.POSITIVE_INFINITY
        var maximumAcross = Double.NEGATIVE_INFINITY
        for (point in hull) {
            val along = point.x * axisX + point.y * axisY
            val across = point.x * perpendicularX + point.y * perpendicularY
            minimumAlong = min(minimumAlong, along)
            maximumAlong = max(maximumAlong, along)
            minimumAcross = min(minimumAcross, across)
            maximumAcross = max(maximumAcross, across)
        }
        val area = (maximumAlong - minimumAlong) * (maximumAcross - minimumAcross)
        if (area < bestArea) {
            bestArea = area
            best =
                OrientedRectangle(
                    axisX,
                    axisY,
                    perpendicularX,
                    perpendicularY,
                    minimumAlong,
                    maximumAlong,
                    minimumAcross,
                    maximumAcross,
                )
        }
    }
    return best
}

private fun List<DoublePoint>.toClockwiseQuadrilateral(): OcrQuadrilateral {
    require(size == 4)
    val centerX = sumOf(DoublePoint::x) / size
    val centerY = sumOf(DoublePoint::y) / size
    var ordered = sortedBy { point -> atan2(point.y - centerY, point.x - centerX) }
    if (signedArea(ordered) < 0.0) ordered = ordered.reversed()
    val first = ordered.indices.minBy { index -> ordered[index].x + ordered[index].y }
    val rotated = List(4) { offset -> ordered[(first + offset) % 4] }
    return OcrQuadrilateral(
        topLeft = rotated[0].toFloatPoint(),
        topRight = rotated[1].toFloatPoint(),
        bottomRight = rotated[2].toFloatPoint(),
        bottomLeft = rotated[3].toFloatPoint(),
    )
}

private fun signedArea(points: List<DoublePoint>): Double =
    points.indices.sumOf { index ->
        val current = points[index]
        val next = points[(index + 1) % points.size]
        current.x * next.y - current.y * next.x
    } / 2.0

private fun DoublePoint.toFloatPoint(): OcrPoint = OcrPoint(x.toFloat(), y.toFloat())
