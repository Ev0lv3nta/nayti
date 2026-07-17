package app.nayti.platform.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore

interface MediaStoreGateway {
    fun mountedVolumes(): List<MediaVolumeSnapshot>

    fun inventory(
        volume: MediaVolumeSnapshot,
        modifiedAfterGeneration: Long? = null,
        cancellationSignal: CancellationSignal? = null,
    ): MediaInventory

    fun contentUri(key: MediaKey): Uri
}

class AndroidMediaStoreGateway(
    context: Context,
) : MediaStoreGateway {
    private val resolver: ContentResolver = context.contentResolver
    private val applicationContext = context.applicationContext

    override fun mountedVolumes(): List<MediaVolumeSnapshot> =
        MediaStore.getExternalVolumeNames(applicationContext)
            .sorted()
            .map { volumeName ->
                MediaVolumeSnapshot(
                    volumeName = volumeName,
                    version = MediaStore.getVersion(applicationContext, volumeName),
                    generation = MediaStore.getGeneration(applicationContext, volumeName),
                )
            }

    override fun inventory(
        volume: MediaVolumeSnapshot,
        modifiedAfterGeneration: Long?,
        cancellationSignal: CancellationSignal?,
    ): MediaInventory {
        require(modifiedAfterGeneration == null || modifiedAfterGeneration >= 0)
        val uri = MediaStore.Images.Media.getContentUri(volume.volumeName)
        val selection =
            modifiedAfterGeneration?.let {
                "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            }
        val selectionArgs = modifiedAfterGeneration?.let { arrayOf(it.toString()) }
        val cursor =
            resolver.query(
                uri,
                Projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns._ID} ASC",
                cancellationSignal,
            ) ?: error("MediaStore returned a null cursor for ${volume.volumeName}")
        return cursor.use {
            MediaInventory(
                volume = volume,
                isFullInventory = modifiedAfterGeneration == null,
                observations = buildList {
                    while (it.moveToNext()) add(it.toObservation(volume.volumeName))
                },
            )
        }
    }

    override fun contentUri(key: MediaKey): Uri =
        ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(key.volumeName),
            key.mediaStoreId,
        )

    private fun Cursor.toObservation(volumeName: String): MediaObservation =
        MediaObservation(
            key = MediaKey(volumeName, requiredLong(MediaStore.MediaColumns._ID)),
            mimeType = requiredString(MediaStore.MediaColumns.MIME_TYPE),
            sizeBytes = requiredLong(MediaStore.MediaColumns.SIZE),
            width = requiredInt(MediaStore.MediaColumns.WIDTH),
            height = requiredInt(MediaStore.MediaColumns.HEIGHT),
            orientationDegrees = requiredInt(MediaStore.Images.ImageColumns.ORIENTATION),
            generationAdded = requiredLong(MediaStore.MediaColumns.GENERATION_ADDED),
            generationModified = requiredLong(MediaStore.MediaColumns.GENERATION_MODIFIED),
            dateTakenMillis = nullableLong(MediaStore.Images.ImageColumns.DATE_TAKEN),
            dateModifiedSeconds = nullableLong(MediaStore.MediaColumns.DATE_MODIFIED),
            displayName = nullableString(MediaStore.MediaColumns.DISPLAY_NAME),
            bucketId = nullableLong(MediaStore.Images.ImageColumns.BUCKET_ID),
            bucketDisplayName = nullableString(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME),
            relativePath = nullableString(MediaStore.MediaColumns.RELATIVE_PATH),
            isPending = requiredInt(MediaStore.MediaColumns.IS_PENDING) != 0,
            isTrashed = requiredInt(MediaStore.MediaColumns.IS_TRASHED) != 0,
        )

    private fun Cursor.requiredLong(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.requiredInt(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun Cursor.requiredString(column: String): String =
        checkNotNull(getString(getColumnIndexOrThrow(column))) { "$column must not be null" }

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private companion object {
        val Projection =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.Images.ImageColumns.ORIENTATION,
                MediaStore.MediaColumns.GENERATION_ADDED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.BUCKET_ID,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.IS_TRASHED,
            )
    }
}
