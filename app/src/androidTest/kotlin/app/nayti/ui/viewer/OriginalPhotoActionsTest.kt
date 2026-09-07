package app.nayti.ui.viewer

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nayti.indexer.CatalogItem
import app.nayti.platform.media.MediaKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalPhotoActionsTest {
    @Test
    fun externalActionsGrantOnlyOneOriginalUriForReading() {
        val item = CatalogItem(1, MediaKey("external_primary", 42), null, null, "image/jpeg", 1200, 800, null)
        for (share in listOf(false, true)) {
            val intent = originalPhotoIntent(item, share)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals("image/jpeg", intent.type)
            val clip = checkNotNull(intent.clipData)
            assertEquals(1, clip.itemCount)
            assertEquals("content://media/external_primary/images/media/42", clip.getItemAt(0).uri.toString())
            if (!share) assertEquals(clip.getItemAt(0).uri, intent.data)
            assertEquals(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW, intent.action)
        }
    }
}
