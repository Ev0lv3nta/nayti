package app.nayti.ml.runtime.ocr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** A single-lane direct tensor buffer. Reuse prevents one large native allocation per photo. */
class OcrTensorBufferPool(
    private val maximumFloats: Int = DefaultMaximumFloats,
) : AutoCloseable {
    private var storage: ByteBuffer? = null
    private var acquired = false
    private var closed = false

    @Synchronized
    fun acquire(shape: LongArray): OcrFloatTensorLease {
        check(!closed) { "OCR tensor buffer pool is closed" }
        check(!acquired) { "OCR tensor buffer is already in use" }
        val elements = shape.elementCount()
        require(elements in 1..maximumFloats) { "OCR tensor exceeds the configured memory budget" }
        val requiredBytes = Math.multiplyExact(elements, Float.SIZE_BYTES)
        val current = storage
        val buffer =
            if (current == null || current.capacity() < requiredBytes) {
                ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder()).also { storage = it }
            } else {
                current
            }
        acquired = true
        return OcrFloatTensorLease(
            owner = this,
            shape = shape.clone(),
            elements = elements,
            buffer = buffer,
        )
    }

    @Synchronized
    internal fun release() {
        check(acquired) { "OCR tensor buffer is not acquired" }
        acquired = false
    }

    @Synchronized
    override fun close() {
        check(!acquired) { "Cannot close OCR tensor buffer pool while a tensor is in use" }
        closed = true
        storage = null
    }

    companion object {
        const val DefaultMaximumFloats = 3 * 1_920 * 1_920
    }
}

class OcrFloatTensorLease internal constructor(
    private val owner: OcrTensorBufferPool,
    shape: LongArray,
    val elements: Int,
    private val buffer: ByteBuffer,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    val shape: LongArray = shape.clone()

    fun writableBuffer(): FloatBuffer {
        check(!released.get()) { "OCR tensor lease is already closed" }
        return buffer.duplicate()
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                position(0)
                limit(elements)
            }
    }

    fun readableBuffer(): FloatBuffer = writableBuffer().asReadOnlyBuffer()

    override fun close() {
        if (released.compareAndSet(false, true)) owner.release()
    }
}

private fun LongArray.elementCount(): Int {
    require(isNotEmpty()) { "Tensor shape must not be empty" }
    var result = 1L
    forEach { dimension ->
        require(dimension > 0) { "Tensor dimensions must be positive" }
        result = Math.multiplyExact(result, dimension)
        require(result <= Int.MAX_VALUE) { "Tensor has too many elements" }
    }
    return result.toInt()
}
