package app.nayti.indexing

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nayti.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexingForegroundPolicyInstrumentedTest {
    @Test
    fun runtimeTypeMatchesApiContractAndMergedManifest() {
        val expected =
            if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        val forbidden =
            if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            }
        val selected = IndexingForegroundPolicy.serviceType(Build.VERSION.SDK_INT)

        assertEquals(expected, selected)
        assertNotEquals(forbidden, selected)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, IndexingForegroundService::class.java)
        val service =
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getServiceInfo(component, 0)
            }
        assertTrue(service.foregroundServiceType and selected != 0)
        assertEquals(false, service.exported)
    }

    @Test
    fun thresholdDoesNotUseDataSyncAsApi35Fallback() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            IndexingForegroundPolicy.serviceType(34),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            IndexingForegroundPolicy.serviceType(35),
        )
    }

    @Test
    fun visibleActivityStartMeetsForegroundNotificationContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val controller = IndexingServiceController(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            controller.start()
            assertTrue(
                "foreground notification was not observed",
                awaitCondition(NotificationDeadlineMillis) {
                    notifications.activeNotifications.any { status ->
                        status.notification.channelId == NotificationChannelId
                    }
                },
            )
            controller.stopForNow()
            assertTrue(
                "foreground notification did not stop",
                awaitCondition(ServiceStopDeadlineMillis) {
                    notifications.activeNotifications.none { status ->
                        status.notification.channelId == NotificationChannelId
                    }
                },
            )
        }
    }

    @Test
    fun backgroundProcessDoesNotDispatchForegroundServiceStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val controller = IndexingServiceController(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue(
                "process did not leave its user-visible state",
                awaitCondition(ProcessBackgroundDeadlineMillis) { !controller.startAllowed },
            )
            assertFalse(controller.start())
            assertTrue(
                notifications.activeNotifications.none { status ->
                    status.notification.channelId == NotificationChannelId
                },
            )
        }
    }

    private fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(PollMillis)
        }
        return condition()
    }

    private companion object {
        const val NotificationChannelId = "indexing"
        const val NotificationDeadlineMillis = 5_000L
        const val ServiceStopDeadlineMillis = 5_000L
        const val ProcessBackgroundDeadlineMillis = 5_000L
        const val PollMillis = 10L
    }
}
