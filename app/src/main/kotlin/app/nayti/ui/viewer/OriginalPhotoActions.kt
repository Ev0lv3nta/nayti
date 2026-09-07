package app.nayti.ui.viewer

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import app.nayti.indexer.CatalogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the explicitly selected original URI is granted to the chosen external application. */
internal suspend fun openOriginalPhoto(context: Context, item: CatalogItem, share: Boolean) {
    val intent = originalPhotoIntent(item, share)
    val uri = checkNotNull(intent.clipData).getItemAt(0).uri
    withContext(Dispatchers.IO) {
        checkNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")).use { }
    }
    context.startActivity(Intent.createChooser(intent, null))
}

internal fun originalPhotoIntent(item: CatalogItem, share: Boolean): Intent {
    val uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(item.key.volumeName), item.key.mediaStoreId,
    )
    return Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        val mime = item.mimeType.takeIf { it.startsWith("image/") } ?: "image/*"
        if (share) {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            setDataAndType(uri, mime)
        }
        clipData = ClipData.newRawUri("Photo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
