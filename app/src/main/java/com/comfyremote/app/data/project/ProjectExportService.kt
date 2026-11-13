package com.comfyremote.app.data.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.comfyremote.app.ComfyRemoteApp
import com.comfyremote.app.MainActivity
import com.comfyremote.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Foreground owner of a local MP4 export. No expensive work starts before FGS. */
class ProjectExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ProjectExportController
    private lateinit var notifications: NotificationManager
    private var observing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = (application as ComfyRemoteApp).container.projectExportController
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Iris Studio export", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            return START_NOT_STICKY
        }
        val projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)
        if (intent?.action != ACTION_START || projectId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Android requires foreground promotion within five seconds.
        val current = controller.state.value
        ServiceCompat.startForeground(
            this, RUNNING_ID,
            notification(current),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (observing == null) {
            observing = scope.launch {
                controller.state.collect { state ->
                    if (state is ProjectExportController.State.Running) {
                        ServiceCompat.startForeground(
                            this@ProjectExportService, RUNNING_ID,
                            notification(state),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    } else {
                        runCatching { notifications.notify(FINISHED_ID, notification(state)) }
                        ServiceCompat.stopForeground(
                            this@ProjectExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        controller.runInService(projectId, scope)
        return START_NOT_STICKY // never auto-repeat an interrupted export
    }

    private fun notification(state: ProjectExportController.State): Notification {
        val running = state as? ProjectExportController.State.Running
        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Iris · Studio MP4")
            .setContentText(when (state) {
                is ProjectExportController.State.Running -> "Rendering video… " +
                    (state.progress * 100).toInt() + "%"
                is ProjectExportController.State.Succeeded -> "Export complete"
                is ProjectExportController.State.Failed -> "Export failed — retry in project"
                ProjectExportController.State.Idle -> "Export cancelled"
            })
            .setContentIntent(activityIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(running != null)
            .setAutoCancel(running == null)
        if (running != null) {
            builder.setProgress(100, (running.progress * 100).toInt().coerceIn(0, 100), false)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, ProjectExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel export", cancel)
        }
        return builder.build()
    }

    override fun onDestroy() {
        observing?.cancel()
        controller.onServiceDestroyed()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.comfyremote.app.EXPORT_START"
        const val ACTION_CANCEL = "com.comfyremote.app.EXPORT_CANCEL"
        const val EXTRA_PROJECT_ID = "projectId"
        private const val CHANNEL_ID = "iris_studio_export"
        private const val RUNNING_ID = 6056
        private const val FINISHED_ID = 6057
    }
}
