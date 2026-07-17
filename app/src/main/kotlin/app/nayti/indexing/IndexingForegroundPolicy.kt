package app.nayti.indexing

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

object IndexingForegroundPolicy {
    @SuppressLint("InlinedApi")
    fun serviceType(sdkInt: Int): Int =
        if (sdkInt >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
}
