package app.nayti.indexing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IndexingServiceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val notificationsGranted: Boolean
        get() =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun start() = dispatch(IndexingForegroundService.ActionStart)

    fun pause() = dispatch(IndexingForegroundService.ActionPause)

    fun stopForNow() = dispatch(IndexingForegroundService.ActionStopForNow)

    fun cancel() = dispatch(IndexingForegroundService.ActionCancel)

    private fun dispatch(action: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, IndexingForegroundService::class.java).setAction(action),
        )
    }
}
