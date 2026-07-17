package app.nayti.platform.media

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class MediaObservation(
    val key: MediaKey,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
    val generationAdded: Long,
    val generationModified: Long,
    val dateTakenMillis: Long?,
    val dateModifiedSeconds: Long?,
    val displayName: String?,
    val bucketId: Long?,
    val bucketDisplayName: String?,
    val relativePath: String?,
    val isPending: Boolean,
    val isTrashed: Boolean,
) {
    init {
        require(mimeType.isNotBlank())
        require(sizeBytes >= 0)
        require(width >= 0)
        require(height >= 0)
        require(orientationDegrees in setOf(0, 90, 180, 270))
        require(generationAdded >= 0)
        require(generationModified >= 0)
    }

    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SourceFingerprint.compute(this)
    }
}

object SourceFingerprint {
    private const val Version = "nayti-source-v1"

    fun compute(observation: MediaObservation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            Version,
            observation.generationAdded.toString(),
            observation.generationModified.toString(),
            observation.sizeBytes.toString(),
            observation.mimeType,
            observation.width.toString(),
            observation.height.toString(),
            observation.orientationDegrees.toString(),
            observation.dateTakenMillis?.toString().orEmpty(),
            observation.dateModifiedSeconds?.toString().orEmpty(),
        ).forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

data class MediaVolumeSnapshot(
    val volumeName: String,
    val version: String,
    val generation: Long,
) {
    init {
        require(volumeName.isNotBlank())
        require(version.isNotBlank())
        require(generation >= 0)
    }
}

data class MediaInventory(
    val volume: MediaVolumeSnapshot,
    val isFullInventory: Boolean,
    val observations: List<MediaObservation>,
)
