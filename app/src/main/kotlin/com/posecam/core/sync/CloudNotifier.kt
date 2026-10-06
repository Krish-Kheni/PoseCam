package com.posecam.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.posecam.SessionsActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A message for the in-app snackbar. */
data class CloudUiMessage(val text: String, val action: SnackbarAction? = null)

/**
 * All cloud-sync user messaging, on its own low-importance channel ("Cloud sync") so it can be silenced
 * without touching the recording alerts:
 *  * an ongoing, silent progress notification while uploads run (it is the foreground-service
 *    notification Android requires so long uploads are not killed);
 *  * event notifications (all synced / failed / stalled) -- but only when the app is NOT on screen;
 *    when it is, the same event is shown as a snackbar instead.
 */
class CloudNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val _messages = MutableSharedFlow<CloudUiMessage>(extraBufferCapacity = 8)

    /** Snackbar messages for the UI. */
    val messages: SharedFlow<CloudUiMessage> = _messages.asSharedFlow()

    init {
        ensureChannel()
    }

    // ---- progress (ongoing) ----------------------------------------------------------------

    fun foregroundInfo(overview: CloudOverview?): ForegroundInfo {
        val notification = progressNotification(overview)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    /** Refreshes the ongoing notification (same id as the foreground one, so it updates in place). */
    fun updateProgress(overview: CloudOverview) {
        runCatching { manager.notify(PROGRESS_ID, progressNotification(overview)) }
    }

    fun clearProgress() {
        runCatching { manager.cancel(PROGRESS_ID) }
    }

    private fun progressNotification(overview: CloudOverview?): Notification {
        val percent = overview?.progressPercent ?: -1
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading recordings")
            .setContentText(overview?.progressText ?: "Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setContentIntent(openApp(PROGRESS_ID))
            .build()
    }

    // ---- events ----------------------------------------------------------------------------

    /** Routes [event] to a snackbar (app on screen) or a notification (app away). Never both. */
    fun announce(event: CloudSyncEvent, appInForeground: Boolean = AppVisibility.inForeground) {
        when (val route = CloudEventRouter.route(event, appInForeground)) {
            is EventRoute.Snackbar -> _messages.tryEmit(CloudUiMessage(route.message, route.action))
            is EventRoute.Notification -> runCatching {
                manager.notify(
                    route.id,
                    NotificationCompat.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload)
                        .setContentTitle(route.title)
                        .setContentText(route.text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(route.text))
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(true)
                        .setContentIntent(openApp(route.id))
                        .build(),
                )
            }
        }
    }

    /** The user is looking at the app again; drop notifications about things now visible in it. */
    fun dismissEventNotifications() {
        runCatching {
            manager.cancel(CloudEventRouter.NOTIFICATION_ALL_SYNCED)
            manager.cancel(CloudEventRouter.NOTIFICATION_FAILED)
            manager.cancel(CloudEventRouter.NOTIFICATION_STALLED)
        }
    }

    /** In-app only messages for taps the user just made. */
    fun say(message: CloudUiMessage) {
        _messages.tryEmit(message)
    }

    private fun openApp(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, SessionsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(appContext, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Cloud sync", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Upload progress and sync results for recordings"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "posecam_cloud_sync"
        const val PROGRESS_ID = 3001
    }
}
