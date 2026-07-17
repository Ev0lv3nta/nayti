package app.nayti.platform.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicBoolean

class MediaStoreChangeObserver(
    context: Context,
    private val onDirty: () -> Unit,
) : AutoCloseable {
    private val resolver = context.applicationContext.contentResolver
    private val closed = AtomicBoolean(false)
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!closed.get()) onDirty()
            }
        }

    init {
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            resolver.unregisterContentObserver(observer)
        }
    }
}
