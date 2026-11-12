package com.pocketbedrock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pocketbedrock.app.PocketBedrockApp
import kotlinx.coroutines.launch
import java.io.File

/**
 * Optional auto-start on boot (off by default). Android may refuse to start the
 * foreground service directly from BOOT_COMPLETED on some versions; in that case the
 * user gets a tappable notification instead.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? PocketBedrockApp ?: return
        val graph = app.graph

        goAsync().let { pending ->
            graph.appScope.launch {
                try {
                    val settings = graph.settingsSnapshot()
                    if (!settings.autoStartOnBoot || !settings.setupCompleted) return@launch
                    if (!File(graph.currentBdsDir(), "bedrock_server").exists()) return@launch

                    val startIntent = ServerService.intent(context, ServerService.ACTION_START)
                    try {
                        ContextCompat.startForegroundService(context, startIntent)
                    } catch (_: Exception) {
                        postFallbackNotification(context)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun postFallbackNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(21, Notifications.bootFallback(context))
    }
}
