package app.nayti.platform.media

/** Stable MediaStore identity. Filesystem paths are deliberately excluded. */
data class MediaKey(
    val volumeName: String,
    val mediaStoreId: Long,
) {
    init {
        require(volumeName.isNotBlank()) { "volumeName must not be blank" }
        require(mediaStoreId >= 0) { "mediaStoreId must be non-negative" }
    }
}
