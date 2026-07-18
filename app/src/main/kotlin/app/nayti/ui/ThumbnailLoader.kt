package app.nayti.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import app.nayti.platform.media.MediaKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Singleton
class ThumbnailLoader internal constructor(
    private val decodeThumbnail: suspend (MediaKey) -> Bitmap?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        decodeThumbnail = { key ->
            context.contentResolver.loadThumbnail(
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.getContentUri(key.volumeName),
                    key.mediaStoreId,
                ),
                Size(ThumbnailEdgePixels, ThumbnailEdgePixels),
                null,
            )
        },
    )

    private val decodePermits = Semaphore(DecodeConcurrency)
    private val cache =
        object : LruCache<CacheKey, Bitmap>(CacheKilobytes) {
            override fun sizeOf(key: CacheKey, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    @Volatile private var currentAccessRevision: Long? = null
    @Volatile private var currentCatalogRevision: Long? = null

    @Synchronized
    fun onCatalogState(accessRevision: Long, catalogRevision: Long) {
        require(accessRevision > 0 && catalogRevision >= 0)
        if (currentAccessRevision == accessRevision && currentCatalogRevision == catalogRevision) return
        currentAccessRevision = accessRevision
        currentCatalogRevision = catalogRevision
        cache.evictAll()
    }

    suspend fun load(key: MediaKey, accessRevision: Long): Bitmap? {
        val catalogRevision = currentCatalogRevision ?: return null
        val cacheKey = CacheKey(key.volumeName, key.mediaStoreId, accessRevision, catalogRevision)
        cache.get(cacheKey)?.let { return it }
        return decodePermits.withPermit {
            cache.get(cacheKey)?.let { return@withPermit it }
            val decoded =
                withContext(Dispatchers.IO) {
                    try {
                        decodeThumbnail(MediaKey(cacheKey.volumeName, cacheKey.mediaStoreId))
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@withPermit null
            if (currentAccessRevision != accessRevision || currentCatalogRevision != catalogRevision) {
                decoded.recycle()
                return@withPermit null
            }
            cache.put(cacheKey, decoded)
            decoded
        }
    }

    private data class CacheKey(
        val volumeName: String,
        val mediaStoreId: Long,
        val accessRevision: Long,
        val catalogRevision: Long,
    )

    private companion object {
        const val DecodeConcurrency = 2
        const val ThumbnailEdgePixels = 512
        const val CacheKilobytes = 32 * 1024
    }
}
