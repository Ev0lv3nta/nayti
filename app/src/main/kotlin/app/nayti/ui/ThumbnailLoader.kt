package app.nayti.ui

import android.content.ContentResolver
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
class ThumbnailLoader @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val decodePermits = Semaphore(DecodeConcurrency)
    private val cache =
        object : LruCache<CacheKey, Bitmap>(CacheKilobytes) {
            override fun sizeOf(key: CacheKey, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    @Volatile private var currentAccessRevision: Long? = null

    @Synchronized
    fun onAccessRevision(revision: Long) {
        if (currentAccessRevision == revision) return
        currentAccessRevision = revision
        cache.evictAll()
    }

    suspend fun load(key: MediaKey, accessRevision: Long): Bitmap? {
        val cacheKey = CacheKey(key.volumeName, key.mediaStoreId, accessRevision)
        cache.get(cacheKey)?.let { return it }
        return decodePermits.withPermit {
            cache.get(cacheKey)?.let { return@withPermit it }
            val decoded = withContext(Dispatchers.IO) { decode(cacheKey) } ?: return@withPermit null
            if (currentAccessRevision == accessRevision) {
                cache.put(cacheKey, decoded)
            }
            decoded
        }
    }

    private fun decode(key: CacheKey): Bitmap? =
        try {
            resolver.loadThumbnail(
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.getContentUri(key.volumeName),
                    key.mediaStoreId,
                ),
                Size(ThumbnailEdgePixels, ThumbnailEdgePixels),
                null,
            )
        } catch (_: Exception) {
            null
        }

    private data class CacheKey(
        val volumeName: String,
        val mediaStoreId: Long,
        val accessRevision: Long,
    )

    private companion object {
        const val DecodeConcurrency = 2
        const val ThumbnailEdgePixels = 512
        const val CacheKilobytes = 32 * 1024
    }
}
