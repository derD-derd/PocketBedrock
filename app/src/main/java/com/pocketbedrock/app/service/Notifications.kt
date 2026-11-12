package com.pocketbedrock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pocketbedrock.app.MainActivity
import com.pocketbedrock.app.R
import com.pocketbedrock.app.core.ServerState

object Notifications {

    const val CHANNEL_SERVER = "server_status"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_SERVER = 10

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVER,
                context.getString(R.string.notif_channel_server),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_server_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
            },
        )
    }

    fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun serviceAction(context: Context, action: String, title: String, requestCode: Int): NotificationCompat.Action {
        val intent = Intent(context, ServerService::class.java).setAction(action)
        val pi = PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, title, pi)
    }

    fun serverNotification(
        context: Context,
        state: ServerState,
        playersOnline: Int,
        version: String?,
    ): Notification {
        val title = when (state) {
            ServerState.OFFLINE -> context.getString(R.string.notif_title_offline)
            ServerState.STARTING -> context.getString(R.string.notif_title_starting)
            ServerState.ONLINE -> context.getString(R.string.notif_title_online)
            ServerState.STOPPING -> context.getString(R.string.notif_title_stopping)
            ServerState.CRASHED -> context.getString(R.string.notif_title_crashed)
        }
        val text = buildString {
            if (state == ServerState.ONLINE) {
                append("$playersOnline player(s) connected")
                version?.let { append(" - v$it") }
            } else {
                append("Tap to open PocketBedrock")
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(state != ServerState.OFFLINE && state != ServerState.CRASHED)
            .setContentIntent(contentIntent(context))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            ServerState.OFFLINE, ServerState.CRASHED ->
                builder.addAction(serviceAction(context, ServerService.ACTION_START, context.getString(R.string.notif_action_start), 1))
            ServerState.ONLINE, ServerState.STARTING -> {
                builder.addAction(serviceAction(context, ServerService.ACTION_RESTART, context.getString(R.string.notif_action_restart), 2))
                builder.addAction(serviceAction(context, ServerService.ACTION_STOP, context.getString(R.string.notif_action_stop), 3))
            }
            ServerState.STOPPING -> Unit
        }
        return builder.build()
    }

    fun alert(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

    fun postAlert(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // POST_NOTIFICATIONS may not be granted; alerts are best-effort.
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((System.currentTimeMillis() and 0xFFFF).toInt(), alert(context, title, text))
    }

    fun bootFallback(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.boot_start_failed_title))
            .setContentText(context.getString(R.string.boot_start_failed_text))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .build()
}
