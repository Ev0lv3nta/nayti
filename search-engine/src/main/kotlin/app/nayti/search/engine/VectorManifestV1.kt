package app.nayti.search.engine

import java.security.MessageDigest

data class VectorManifestSegment(
    val relativePath: String,
    val byteLength: Long,
    val sha256: String,
    val recordCount: Int,
)

data class EncodedVectorManifest(
    val bytes: ByteArray,
    val sha256: String,
    val recordCount: Long,
)

object VectorManifestV1 {
    const val FormatVersion = 1

    fun encode(
        revision: String,
        generationId: String,
        parentRevision: String?,
        channel: VectorSegmentChannel,
        embeddingSpaceHash: String,
        dimension: Int,
        segments: List<VectorManifestSegment>,
    ): EncodedVectorManifest {
        require(token(revision) && token(generationId))
        require(parentRevision == null || token(parentRevision))
        require(Sha256.matches(embeddingSpaceHash))
        require(dimension in 1..VectorSegmentV1Writer.MaximumDimension)
        require(segments.isNotEmpty() && segments.size <= MaximumSegments)
        require(segments.map(VectorManifestSegment::sha256).distinct().size == segments.size)
        require(segments.all { segment ->
            relativePath(segment.relativePath) &&
                segment.byteLength > 0 &&
                Sha256.matches(segment.sha256) &&
                segment.recordCount in 1..VectorSegmentV1Writer.MaximumRecordCount
        })
        val recordCount = segments.sumOf { it.recordCount.toLong() }
        val text = buildString {
            append("NAYTIMAN\t1\n")
            append("revision\t").append(revision).append('\n')
            append("generation\t").append(generationId).append('\n')
            append("parent\t").append(parentRevision ?: "-").append('\n')
            append("channel\t").append(channel.name).append('\n')
            append("embedding_space_sha256\t").append(embeddingSpaceHash).append('\n')
            append("dimension\t").append(dimension).append('\n')
            append("segment_count\t").append(segments.size).append('\n')
            append("record_count\t").append(recordCount).append('\n')
            segments.forEachIndexed { ordinal, segment ->
                append("segment\t")
                    .append(ordinal).append('\t')
                    .append(segment.relativePath).append('\t')
                    .append(segment.byteLength).append('\t')
                    .append(segment.sha256).append('\t')
                    .append(segment.recordCount).append('\n')
            }
        }
        val bytes = text.encodeToByteArray()
        return EncodedVectorManifest(bytes, sha256Hex(bytes), recordCount)
    }

    private fun token(value: String): Boolean = value.length in 1..96 && Token.matches(value)

    private fun relativePath(value: String): Boolean =
        value.length in 1..240 && !value.startsWith('/') && !value.contains("..") && Path.matches(value)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private val Token = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    private val Path = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")
    private val Sha256 = Regex("[0-9a-f]{64}")
    private const val MaximumSegments = 65_536
}
