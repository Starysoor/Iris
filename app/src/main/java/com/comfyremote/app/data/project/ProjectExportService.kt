package com.comfyremote.app.data.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var sessionId: String? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = (application as ComfyRemoteApp).container.projectExportController
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.proj_export_title), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val requested = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (intent?.action == ACTION_CANCEL) {
            if (requested != null) controller.cancel(requested)
            if (controller.state.value !is ProjectExportController.State.Running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val running = controller.state.value as? ProjectExportController.State.Running
        if (intent?.action != ACTION_START || running == null || running.sessionId != requested) {
            if (sessionId == null && intent?.action == ACTION_START) {
                // The request may have been cancelled while Android was starting the service.
                runCatching {
                    ServiceCompat.startForeground(
                        this, RUNNING_ID, notification(ProjectExportController.State.Idle),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                }
            }
            if (sessionId == null || running == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, RUNNING_ID, notification(running), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: Exception) {
            controller.onServiceDestroyed(requested)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        sessionId = running.sessionId
        observing?.cancel()
        observing = scope.launch {
            controller.state.collect { state ->
                if (state is ProjectExportController.State.Running && state.sessionId == requested) {
                    runCatching { notifications.notify(RUNNING_ID, notification(state)) }
                } else {
                    if (state.projectId == running.projectId && state !is ProjectExportController.State.Running) {
                        runCatching { notifications.notify(FINISHED_ID, notification(state)) }
                    }
                    ServiceCompat.stopForeground(
                        this@ProjectExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStartId)
                }
            }
        }
        controller.runInService(running.sessionId, scope)
        return START_NOT_STICKY // never auto-repeat an interrupted export
    }

    private fun notification(state: ProjectExportController.State): Notification {
        val running = state as? ProjectExportController.State.Running
        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .setIdentifier(state.projectId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PROJECT_ID, state.projectId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.proj_export_title))
            .setContentText(when (state) {
                is ProjectExportController.State.Running ->
                    getString(R.string.proj_export_running, (state.progress * 100).toInt())
                is ProjectExportController.State.Succeeded -> getString(R.string.proj_export_done)
                is ProjectExportController.State.Failed -> state.message
                ProjectExportController.State.Idle -> getString(R.string.proj_export_start)
            })
            .setContentIntent(activityIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(running != null)
            .setAutoCancel(running == null)
        if (running != null) {
            builder.setProgress(100, (running.progress * 100).toInt().coerceIn(0, 100), false)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, ProjectExportService::class.java).setAction(ACTION_CANCEL)
                    .setIdentifier(running.sessionId)
                    .putExtra(EXTRA_SESSION_ID, running.sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_cancel), cancel)
        }
        return builder.build()
    }

    override fun onDestroy() {
        observing?.cancel()
        controller.onServiceDestroyed(sessionId)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.comfyremote.app.EXPORT_START"
        const val ACTION_CANCEL = "com.comfyremote.app.EXPORT_CANCEL"
        const val EXTRA_PROJECT_ID = "com.comfyremote.app.export.PROJECT_ID"
        const val EXTRA_SESSION_ID = "com.comfyremote.app.export.SESSION_ID"
        private const val CHANNEL_ID = "iris_studio_export"
        private const val RUNNING_ID = 6056
        private const val FINISHED_ID = 6057
    }
}
