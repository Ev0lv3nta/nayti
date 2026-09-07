package app.nayti.indexing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class IndexingStartResult {
    Started, PhotoAccessRequired, ModelPackRequired, CatalogBusy, AppNotVisible, SystemRejected,
}

@Singleton
class IndexingServiceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val notificationsGranted: Boolean
        get() =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    val startAllowed: Boolean
        get() = appIsVisible

    fun start(): IndexingStartResult {
        if (!startAllowed) return IndexingStartResult.AppNotVisible
        return try {
            dispatch(IndexingForegroundService.ActionStart)
            IndexingStartResult.Started
        } catch (_: IllegalStateException) {
            IndexingStartResult.SystemRejected
        } catch (_: SecurityException) {
            IndexingStartResult.SystemRejected
        }
    }

    fun pause() = dispatch(IndexingForegroundService.ActionPause)

    fun stopForNow() = dispatch(IndexingForegroundService.ActionStopForNow)

    fun cancel() = dispatch(IndexingForegroundService.ActionCancel)

    private val appIsVisible: Boolean
        get() =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                Lifecycle.State.STARTED,
            )

    private fun dispatch(action: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, IndexingForegroundService::class.java).setAction(action),
        )
    }
}
