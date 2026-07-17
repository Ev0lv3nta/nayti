package app.nayti.search.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

enum class VectorSegmentChannel(val code: Byte) {
    VISUAL(1),
    OCR_SEMANTIC(2),
}

data class VectorSegmentRecord(
    val recordId: Long,
    val assetId: Long,
    val ordinal: Int,
    val vector: ByteArray,
)

data class EncodedVectorSegment(
    val segmentId: UUID,
    val channel: VectorSegmentChannel,
    val dimension: Int,
    val embeddingSpaceHash: String,
    val bytes: ByteArray,
    val sha256: String,
)

object VectorSegmentV1Writer {
    const val FormatVersion = 1
    const val MaximumRecordCount = 256
    const val MaximumDimension = 4096
    private const val HeaderSize = 128
    private const val RecordEntrySize = 24
    private const val Alignment = 64

    fun encode(
        channel: VectorSegmentChannel,
        embeddingSpaceHash: String,
        records: List<VectorSegmentRecord>,
        segmentId: UUID = UUID.randomUUID(),
    ): EncodedVectorSegment {
        require(records.isNotEmpty() && records.size <= MaximumRecordCount)
        val dimension = records.first().vector.size
        require(dimension in 1..MaximumDimension)
        require(records.all { it.vector.size == dimension })
        require(records.all { it.recordId > 0 && it.assetId > 0 && it.ordinal >= 0 })
        require(records.map(VectorSegmentRecord::recordId).distinct().size == records.size)
        require(
            channel != VectorSegmentChannel.VISUAL ||
                records.all { it.recordId == it.assetId && it.ordinal == 0 },
        )
        require(
            channel != VectorSegmentChannel.OCR_SEMANTIC ||
                records.map { it.assetId to it.ordinal }.distinct().size == records.size,
        )
        val embeddingHashBytes = decodeSha256(embeddingSpaceHash)
        require(segmentId.mostSignificantBits != 0L || segmentId.leastSignificantBits != 0L)

        val tableLength = Math.multiplyExact(records.size, RecordEntrySize)
        val payloadOffset = align(Math.addExact(HeaderSize, tableLength), Alignment)
        val payloadLength = Math.multiplyExact(records.size, dimension)
        val fileLength = Math.addExact(payloadOffset, payloadLength)
        val buffer = ByteBuffer.allocate(fileLength).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("NAYTIVEC".encodeToByteArray())
        buffer.putShort(FormatVersion.toShort())
        buffer.putShort(HeaderSize.toShort())
        buffer.put(channel.code)
        buffer.put(1) // QINT8
        buffer.put(1) // cosine on L2-normalized vectors
        buffer.put(0) // flags
        buffer.putInt(dimension)
        buffer.putInt(records.size)
        buffer.putShort(RecordEntrySize.toShort())
        buffer.putShort(0)
        buffer.putLong(HeaderSize.toLong())
        buffer.putLong(payloadOffset.toLong())
        buffer.putLong(payloadLength.toLong())
        buffer.putLong(fileLength.toLong())
        buffer.put(embeddingHashBytes)
        putUuid(buffer, segmentId)
        buffer.position(HeaderSize)

        records.forEach { record ->
            buffer.putLong(record.recordId)
            buffer.putLong(record.assetId)
            buffer.putInt(record.ordinal)
            buffer.putInt(0)
        }
        buffer.position(payloadOffset)
        records.forEach { buffer.put(it.vector) }

        val bytes = buffer.array()
        return EncodedVectorSegment(
            segmentId = segmentId,
            channel = channel,
            dimension = dimension,
            embeddingSpaceHash = embeddingSpaceHash,
            bytes = bytes,
            sha256 = sha256Hex(bytes),
        )
    }

    private fun align(value: Int, alignment: Int): Int =
        Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment)

    private fun putUuid(buffer: ByteBuffer, value: UUID) {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()
        buffer.put(bytes)
    }

    private fun decodeSha256(value: String): ByteArray {
        require(Sha256.matches(value))
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private val Sha256 = Regex("[0-9a-f]{64}")
}
