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

    @Test
    fun jpegPngWebpAndExtremeAspectRatiosDecodeWithinBudget() {
        val formats = listOf(Bitmap.CompressFormat.JPEG to "image/jpeg", Bitmap.CompressFormat.PNG to "image/png",
            Bitmap.CompressFormat.WEBP_LOSSLESS to "image/webp")
        for ((format, mime) in formats) {
            for ((width, height) in listOf(4096 to 64, 64 to 4096, 3072 to 2048)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val bytes = try {
                    bitmap.eraseColor(android.graphics.Color.CYAN)
                    java.io.ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(format, 90, output))
                        output.toByteArray()
                    }
                } finally { bitmap.recycle() }
                val uri = insertBytes(bytes, mime)
                val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, requireNotNull(uri.lastPathSegment).toLong())
                BoundedMediaDecoder(resolver, AndroidMediaStoreGateway(context)).decode(key, 128).use { image ->
                    assertEquals(width, image.sourceWidth)
                    assertEquals(height, image.sourceHeight)
                    assertTrue(image.decodedWidth in 1..128 && image.decodedHeight in 1..128)
                    assertTrue(image.allocationBytes <= 128 * 128 * 4)
                }
            }
        }
    }

    @Test
    fun exifRotationIsAppliedBeforePresentingTheBitmap() {
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        val original = try {
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        val exif = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("Exif\u0000\u0000".toByteArray()).put('I'.code.toByte()).put('I'.code.toByte())
            .putShort(42).putInt(8).putShort(1).putShort(0x0112).putShort(3).putInt(1)
            .putShort(6).putShort(0).putInt(0).array()
        val rotated = java.io.ByteArrayOutputStream().also { output ->
            java.io.DataOutputStream(output).apply {
                write(original, 0, 2)
                writeShort(0xffe1)
                writeShort(exif.size + 2)
                write(exif)
                write(original, 2, original.size - 2)
            }
        }.toByteArray()
        val uri = insertBytes(rotated)
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, requireNotNull(uri.lastPathSegment).toLong())
        BoundedMediaDecoder(resolver, AndroidMediaStoreGateway(context)).decode(key, 128).use {
            assertTrue("Orientation 6 must turn landscape into portrait", it.decodedHeight > it.decodedWidth)
            assertTrue(it.decodedHeight <= 128)
        }
    }

    @Test
    fun syntheticHeicDecodesOrFailsAsContentWithoutUnboundedRetry() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-document.heic").use { it.readBytes() }
        val uri = insertBytes(bytes, "image/heic")
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, requireNotNull(uri.lastPathSegment).toLong())
        try {
            BoundedMediaDecoder(resolver, AndroidMediaStoreGateway(context)).decode(key, 128).use {
                assertTrue(it.decodedWidth in 1..128 && it.decodedHeight in 1..128)
                assertTrue(it.allocationBytes <= 128 * 128 * 4)
            }
        } catch (_: MediaDecodeContentException) {
            // A missing platform codec is not evidence that HEIC works on this target.
            org.junit.Assume.assumeTrue("Platform HEIC decoder unavailable: bounded content rejection", false)
        }
    }

    @Test
    fun pendingAndTrashedMediaAreAbsentOrExplicitlyFlagged() {
        val uri = insertJpeg(96, 48)
        val gateway = AndroidMediaStoreGateway(context)
        val id = requireNotNull(uri.lastPathSegment).toLong()
        fun observation() = gateway.inventory(gateway.mountedVolumes().single { it.volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY })
            .observations.singleOrNull { it.key.mediaStoreId == id }
        assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null))
        assertTrue(observation()?.isPending != false)
        assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0); put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null))
        assertTrue(observation()?.isTrashed != false)
    }

    private fun insertBytes(bytes: ByteArray, mime: String = "image/jpeg"): android.net.Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "nayti-test-${UUID.randomUUID()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
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
