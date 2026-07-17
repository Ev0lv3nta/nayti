package app.nayti.platform.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreGatewayInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val createdUris = mutableListOf<android.net.Uri>()

    @Before
    fun grantMediaRead() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MediaPermissions.ReadImages
            } else {
                MediaPermissions.ReadExternalStorage
            }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity(permission)
    }

    @After
    fun cleanUp() {
        createdUris.forEach { resolver.delete(it, null, null) }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .dropShellPermissionIdentity()
    }

    @Test
    fun inventoryIdentityFingerprintObserverAndBoundedDecodeUseRealMediaStore() {
        val dirty = CountDownLatch(1)
        MediaStoreChangeObserver(context) { dirty.countDown() }.use {
            val uri = insertJpeg(width = 96, height = 48)
            assertTrue(dirty.await(5, TimeUnit.SECONDS))

            val gateway = AndroidMediaStoreGateway(context)
            val volume =
                gateway.mountedVolumes().single { snapshot ->
                    snapshot.volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY
                }
            val id = uri.lastPathSegment?.toLong() ?: error("MediaStore URI has no ID")
            val observation =
                gateway.inventory(volume).observations.single { candidate ->
                    candidate.key == MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, id)
                }

            assertEquals("image/jpeg", observation.mimeType)
            assertEquals(96, observation.width)
            assertEquals(48, observation.height)
            assertFalse(observation.isPending)
            assertFalse(observation.isTrashed)
            assertEquals(64, observation.fingerprint.length)
            assertEquals(uri, gateway.contentUri(observation.key))

            val probe = BoundedMediaDecoder(resolver, gateway).probe(observation.key, maxEdge = 64)
            assertEquals(96, probe.sourceWidth)
            assertEquals(48, probe.sourceHeight)
            assertTrue(probe.decodedWidth <= 64)
            assertTrue(probe.decodedHeight <= 64)
            assertTrue(probe.allocationBytes in 1..(64 * 64 * 4))

            val decoded = BoundedMediaDecoder(resolver, gateway).decode(observation.key, maxEdge = 64)
            assertEquals(probe.sourceWidth, decoded.sourceWidth)
            assertEquals(probe.decodedWidth, decoded.decodedWidth)
            assertFalse(decoded.bitmap.isRecycled)
            decoded.close()
            decoded.close()
            assertThrows(IllegalStateException::class.java) { decoded.bitmap }
        }
    }

    @Test
    fun corruptMediaIsSeparatedFromAccessLoss() {
        val uri = insertBytes("not an image".encodeToByteArray())
        val id = uri.lastPathSegment?.toLong() ?: error("MediaStore URI has no ID")
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, id)
        val decoder = BoundedMediaDecoder(resolver, AndroidMediaStoreGateway(context))

        assertThrows(MediaDecodeContentException::class.java) {
            decoder.decode(key, maxEdge = 64)
        }

        resolver.delete(uri, null, null)
        createdUris.remove(uri)
        assertThrows(MediaDecodeAccessException::class.java) {
            decoder.decode(key, maxEdge = 64)
        }
    }

    private fun insertJpeg(width: Int, height: Int): android.net.Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "nayti-test-${UUID.randomUUID()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NaytiTests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values))
        createdUris += uri
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
        } finally {
            bitmap.recycle()
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        assertEquals(1, resolver.update(uri, values, null, null))
        return uri
    }

    private fun insertBytes(bytes: ByteArray): android.net.Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "nayti-test-${UUID.randomUUID()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/NaytiTests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values))
        createdUris += uri
        resolver.openOutputStream(uri, "w").use { output ->
            checkNotNull(output).write(bytes)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        assertEquals(1, resolver.update(uri, values, null, null))
        return uri
    }
}
