package app.nayti.ui

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.platform.media.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailLoaderTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun accessRevisionEvictsPreviouslyDecodedMedia() = runBlocking {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "nayti-thumbnail-test.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        try {
            resolver.openOutputStream(uri).use { output ->
                checkNotNull(output)
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(32, 160, 128))
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                bitmap.recycle()
            }
            val loader = ThumbnailLoader(context)
            val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))

            loader.onAccessRevision(1L)
            val first = loader.load(key, 1L)
            assertNotNull(first)
            assertTrue(checkNotNull(first).width in 1..512)

            loader.onAccessRevision(2L)
            val afterRevisionChange = loader.load(key, 2L)
            assertNotNull(afterRevisionChange)
            assertNotSame(first, afterRevisionChange)
        } finally {
            runCatching { resolver.delete(uri, null, null) }
        }
    }
}
