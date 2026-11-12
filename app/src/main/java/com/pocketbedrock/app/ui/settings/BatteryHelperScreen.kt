package com.pocketbedrock.app.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pocketbedrock.app.ui.components.SectionCard

@Composable
fun BatteryHelperScreen(navController: NavHostController) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back to settings") }

        SectionCard(title = "1. Ignore battery optimizations") {
            Text(
                "Ask Android to never doze PocketBedrock. Optional, but strongly recommended for " +
                    "a 24/7 server. (Play Store review may ask about this permission; the app also " +
                    "works without it, the server just risks being frozen.)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ignoring) {
                Text("Already ignoring battery optimizations.", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }) { Text("Request ignore-battery-optimizations") }
            }
            OutlinedButton(onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}")),
                    )
                } catch (_: Exception) {
                }
            }) { Text("Open app settings") }
        }

        SectionCard(title = "2. Samsung: Never sleeping apps") {
            Text(
                "One UI deep-sleeps background apps. Add PocketBedrock to \"Never sleeping apps\" " +
                    "in Samsung Device care → Battery → Background usage limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                val candidates = listOf(
                    ComponentName("com.samsung.android.sm.ui", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    ComponentName("com.samsung.android.sm.ui", "com.samsung.android.sm.ui.DevicesActivity"),
                    ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )
                var opened = false
                for (cn in candidates) {
                    try {
                        context.startActivity(
                            Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        opened = true
                        break
                    } catch (_: Exception) {
                    }
                }
                if (!opened) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:com.samsung.android.sm.ui")),
                        )
                    } catch (_: Exception) {
                    }
                }
            }) { Text("Open Samsung device care (battery)") }
        }

        SectionCard(title = "3. Android 12+ phantom process killer") {
            Text(
                "Android 12+ kills background child processes after ~10 minutes - that includes " +
                    "the proot/box64 tree. Disable it with adb from a computer (requires USB " +
                    "debugging). Optional but strongly recommended for long sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val cmd1 = "adb shell device_config put activity_manager max_phantom_processes 2147483647"
            val cmd2 = "adb shell settings put global settings_enable_monitor_phantom_procs false"
            Text(cmd1, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clipboard.setText(AnnotatedString(cmd1)) }) { Text("Copy command 1") }
            Text(cmd2, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clipboard.setText(AnnotatedString(cmd2)) }) { Text("Copy command 2") }
            Text(
                "These settings reset on reboot. Re-run after each reboot, or disable the monitor " +
                    "permanently via a Magisk module if you are rooted (not required).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Status summary - Android ${Build.VERSION.RELEASE}: " +
                (if (ignoring) "battery optimization exempt" else "battery optimization NOT exempt") +
                ". Check Samsung's \"Never sleeping apps\" manually on One UI devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
