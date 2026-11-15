package dev.akhil.freebuff.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.akhil.freebuff.R
import dev.akhil.freebuff.ui.MainActivity

object Notifications {

    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_TASKS = "tasks"
    const val ID_DOWNLOAD = 4101

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress while models and voices download in the background."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASKS,
                "Task results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Completed long-running tasks."
            },
        )
    }

    fun downloadProgress(
        context: Context,
        title: String,
        detail: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    fun downloadComplete(context: Context, title: String, detail: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ID_DOWNLOAD + 1,
            NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(detail)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun taskFinished(context: Context, title: String, detail: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ID_DOWNLOAD + 2,
            NotificationCompat.Builder(context, CHANNEL_TASKS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setAutoCancel(true)
                .build(),
        )
    }
}
