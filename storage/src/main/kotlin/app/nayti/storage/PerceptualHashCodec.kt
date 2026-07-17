package app.nayti.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class PerceptualHashIdentity(
    val sha256: String,
    val byteLength: Long,
)

object PerceptualHashCodec {
    private const val FormatVersion = 1

    fun identity(draft: PerceptualHashDraft): PerceptualHashIdentity {
        require(draft.assetId > 0 && draft.accessRevision > 0)
        require(draft.sourceFingerprint.isNotBlank() && draft.sourceFingerprint.length <= 128)
        require(draft.pipelineVersion.isNotBlank() && draft.pipelineVersion.length <= 128)
        require(Sha256.matches(draft.componentHash))
        val source = draft.sourceFingerprint.encodeToByteArray()
        val pipeline = draft.pipelineVersion.encodeToByteArray()
        val payload =
            ByteBuffer.allocate(
                4 + 8 + 8 + 4 + source.size + 4 + pipeline.size + 32 + 8,
            ).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(FormatVersion)
                .putLong(draft.assetId)
                .putLong(draft.accessRevision)
                .putInt(source.size)
                .put(source)
                .putInt(pipeline.size)
                .put(pipeline)
                .put(draft.componentHash.hexToBytes())
                .putLong(draft.hashBits)
                .array()
        return PerceptualHashIdentity(
            sha256 = MessageDigest.getInstance("SHA-256").digest(payload).toHex(),
            byteLength = payload.size.toLong(),
        )
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(32) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val Sha256 = Regex("[0-9a-f]{64}")
}
