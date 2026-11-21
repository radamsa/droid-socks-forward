package dev.dav.socksforward.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dav.socksforward.R
import dev.dav.socksforward.core.model.ProxyStats
import dev.dav.socksforward.ui.MainActivity
import dev.dav.socksforward.util.formatBytes

class ProxyNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildStarting(): Notification = base()
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_starting))
        .build()

    fun buildRunning(port: Int, stats: ProxyStats): Notification = base()
        .setContentTitle(context.getString(R.string.notification_running_title, port))
        .setContentText(
            context.getString(
                R.string.notification_running_text,
                stats.activeConnections,
                formatBytes(stats.bytesDown),
                formatBytes(stats.bytesUp),
            ),
        )
        .build()

    @SuppressLint("MissingPermission")
    fun post(notification: Notification) {
        if (!canPostNotifications()) return
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun base(): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_proxy)
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(
            NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.stop),
                stopIntent(),
            ).build(),
        )

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, pendingIntentFlags())
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        return PendingIntent.getService(context, 1, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "proxy"
    }
}
