package app.nayti.search.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class DecodedVectorSegment(
    val segmentId: UUID,
    val channel: VectorSegmentChannel,
    val dimension: Int,
    val embeddingSpaceHash: String,
    val records: List<VectorSegmentRecord>,
)

object VectorSegmentV1Reader {
    private const val HeaderSize = 128
    private const val RecordEntrySize = 24

    fun decode(bytes: ByteArray): DecodedVectorSegment {
        require(bytes.size >= HeaderSize)
        require(bytes.copyOfRange(0, 8).contentEquals("NAYTIVEC".encodeToByteArray()))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getShort(8).toInt() == VectorSegmentV1Writer.FormatVersion)
        require(buffer.getShort(10).toInt() == HeaderSize)
        val channel = when (buffer.get(12).toInt()) {
            1 -> VectorSegmentChannel.VISUAL
            2 -> VectorSegmentChannel.OCR_SEMANTIC
            else -> throw IllegalArgumentException("Unsupported vector channel")
        }
        require(buffer.get(13).toInt() == 1 && buffer.get(14).toInt() == 1 && buffer.get(15).toInt() == 0)
        val dimension = buffer.getInt(16)
        val recordCount = buffer.getInt(20)
        require(dimension in 1..VectorSegmentV1Writer.MaximumDimension)
        require(recordCount in 1..VectorSegmentV1Writer.MaximumRecordCount)
        require(buffer.getShort(24).toInt() == RecordEntrySize && buffer.getShort(26).toInt() == 0)
        require(buffer.getLong(28) == HeaderSize.toLong())
        val payloadOffset = buffer.getLong(36)
        val payloadLength = buffer.getLong(44)
        val fileLength = buffer.getLong(52)
        val tableEnd = Math.addExact(HeaderSize.toLong(), Math.multiplyExact(recordCount.toLong(), RecordEntrySize.toLong()))
        require(payloadOffset >= tableEnd && payloadOffset % 64L == 0L)
        require(payloadLength == Math.multiplyExact(recordCount.toLong(), dimension.toLong()))
        require(fileLength == bytes.size.toLong() && Math.addExact(payloadOffset, payloadLength) == fileLength)
        require(bytes.copyOfRange(tableEnd.toInt(), payloadOffset.toInt()).all { it == 0.toByte() })
        require(bytes.copyOfRange(108, 128).all { it == 0.toByte() })
        val embeddingHash = bytes.copyOfRange(60, 92)
        require(embeddingHash.any { it != 0.toByte() })
        val segmentIdBytes = bytes.copyOfRange(92, 108)
        require(segmentIdBytes.any { it != 0.toByte() })
        val uuidBuffer = ByteBuffer.wrap(segmentIdBytes).order(ByteOrder.BIG_ENDIAN)
        val segmentId = UUID(uuidBuffer.long, uuidBuffer.long)

        val metadata = buildList {
            repeat(recordCount) { index ->
                val offset = HeaderSize + index * RecordEntrySize
                val recordId = buffer.getLong(offset)
                val assetId = buffer.getLong(offset + 8)
                val ordinal = buffer.getInt(offset + 16)
                require(recordId > 0 && assetId > 0 && ordinal >= 0 && buffer.getInt(offset + 20) == 0)
                require(channel != VectorSegmentChannel.VISUAL || (recordId == assetId && ordinal == 0))
                add(Triple(recordId, assetId, ordinal))
            }
        }
        require(metadata.map { it.first }.distinct().size == metadata.size)
        require(
            channel != VectorSegmentChannel.OCR_SEMANTIC ||
                metadata.map { it.second to it.third }.distinct().size == metadata.size,
        )
        val records = metadata.mapIndexed { index, (recordId, assetId, ordinal) ->
            val start = payloadOffset.toInt() + index * dimension
            VectorSegmentRecord(recordId, assetId, ordinal, bytes.copyOfRange(start, start + dimension))
        }
        return DecodedVectorSegment(
            segmentId = segmentId,
            channel = channel,
            dimension = dimension,
            embeddingSpaceHash = embeddingHash.toHex(),
            records = records,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
