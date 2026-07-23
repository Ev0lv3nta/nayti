package app.nayti.indexing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.nayti.MainActivity
import app.nayti.R
import app.nayti.indexer.ModelPackActivationRuntime
import app.nayti.indexer.ModelPackRuntime
import app.nayti.indexer.ModelPackRuntimeStatus
import app.nayti.indexer.OcrIndexingRuntime
import app.nayti.indexer.OcrIndexingState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class IndexingForegroundService : Service() {
    @Inject lateinit var indexing: OcrIndexingRuntime
    @Inject lateinit var packActivation: ModelPackActivationRuntime
    @Inject lateinit var modelPacks: ModelPackRuntime

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: NotificationManager
    private var executionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId,
                getString(R.string.indexing_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        serviceScope.launch {
            combine(indexing.state, packActivation.state, modelPacks.state) { active, candidate, packs ->
                if (packs.candidate == null) active else candidate
            }.collectLatest { state ->
                if (executionJob?.isActive == true) {
                    notifications.notify(NotificationId, notification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action ?: ActionStart) {
            ActionStart -> startExecution(startId)
            ActionPause -> control(startId, indexing::pause)
            ActionStopForNow -> control(startId, indexing::stopForNow)
            ActionCancel -> control(startId, indexing::cancel)
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.launch {
            try {
                if (packActivation.isRunning()) {
                    packActivation.requestStop()
                } else {
                    indexing.stopForSystem()
                }
            } finally {
                executionJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startExecution(startId: Int) {
        if (executionJob?.isActive == true) return
        executionJob =
            serviceScope.launch {
                val packState =
                    modelPacks.state.first { state ->
                        state.status != ModelPackRuntimeStatus.Loading &&
                            state.status != ModelPackRuntimeStatus.Installing
                    }
                val pack = packState.candidate ?: packState.installed
                if (pack == null) {
                    notifications.notify(NotificationId, notification(indexing.state.value, modelMissing = true))
                    delay(ModelMissingNoticeMillis)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                    return@launch
                }
                try {
                    if (packState.candidate != null) {
                        packActivation.runForeground(pack)
                        modelPacks.refresh()
                    } else {
                        indexing.runForeground(pack)
                    }
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
    }

    private fun control(startId: Int, command: suspend () -> Unit) {
        serviceScope.launch {
            if (packActivation.isRunning()) {
                packActivation.requestStop()
            } else {
                command()
            }
            executionJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun startInForeground() {
        val type = IndexingForegroundPolicy.serviceType(Build.VERSION.SDK_INT)
        startForeground(NotificationId, notification(indexing.state.value), type)
    }

    private fun notification(state: OcrIndexingState, modelMissing: Boolean = false): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                RequestOpen,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(this, NotificationChannelId)
                .setSmallIcon(R.drawable.ic_stat_nayti)
                .setContentTitle(getString(R.string.indexing_notification_title))
                .setContentText(
                    if (modelMissing) {
                        getString(R.string.indexing_notification_model_missing)
                    } else {
                        getString(
                            R.string.indexing_notification_progress,
                            state.committed,
                            state.accessible,
                        )
                    },
                )
                .setContentIntent(contentIntent)
                .setOngoing(!modelMissing)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!modelMissing) {
            builder
                .addAction(
                    0,
                    getString(R.string.indexing_notification_pause),
                    serviceAction(ActionPause, RequestPause),
                )
                .addAction(
                    0,
                    getString(R.string.indexing_notification_stop),
                    serviceAction(ActionStopForNow, RequestStop),
                )
            if (state.accessible > 0) {
                builder.setProgress(
                    state.accessible.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    (state.committed + state.permanentGaps).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    false,
                )
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, IndexingForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ActionStart = "app.nayti.action.START_INDEXING"
        const val ActionPause = "app.nayti.action.PAUSE_INDEXING"
        const val ActionStopForNow = "app.nayti.action.STOP_INDEXING"
        const val ActionCancel = "app.nayti.action.CANCEL_INDEXING"

        private const val NotificationChannelId = "indexing"
        private const val NotificationId = 1_001
        private const val RequestOpen = 1
        private const val RequestPause = 2
        private const val RequestStop = 3
        private const val ModelMissingNoticeMillis = 1_000L
    }
}
