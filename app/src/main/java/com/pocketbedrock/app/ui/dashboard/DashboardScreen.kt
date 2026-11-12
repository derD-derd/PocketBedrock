package com.pocketbedrock.app.ui.dashboard

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.core.ServerState
import com.pocketbedrock.app.data.AppSettings
import com.pocketbedrock.app.service.ServerService
import com.pocketbedrock.app.ui.components.BannerSeverity
import com.pocketbedrock.app.ui.components.Gauge
import com.pocketbedrock.app.ui.components.KeyValueRow
import com.pocketbedrock.app.ui.components.SectionCard
import com.pocketbedrock.app.ui.components.StatePill
import com.pocketbedrock.app.ui.components.WarningBanner
import com.pocketbedrock.app.ui.components.formatBytes
import com.pocketbedrock.app.ui.components.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun DashboardScreen(graph: AppGraph, navController: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by graph.supervisor.state.collectAsStateWithLifecycle()
    val startedAt by graph.supervisor.startedAtMs.collectAsStateWithLifecycle()
    val attempts by graph.supervisor.restartAttempts.collectAsStateWithLifecycle()
    val serverMetrics by graph.metricsSampler.server.collectAsStateWithLifecycle()
    val deviceMetrics by graph.metricsSampler.device.collectAsStateWithLifecycle()
    val online by graph.playerTracker.online.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initial = AppSettings.Settings())

    val props = remember { graph.propsManager.read() }
    val worldName = props["level-name"] ?: "world"
    val maxPlayers = props["max-players"] ?: "?"
    val portV4 = props["server-port"] ?: "19132"
    val localIp = remember { findLocalIpv4(context) }

    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val uptimeMs = if (startedAt > 0) (now - startedAt).coerceAtLeast(0) else 0L

    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var showBackupConfirm by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupBusy = true
            backupMessage = null
            try {
                val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                val target = dir?.createFile("application/zip", "backup-$worldName.zip")
                if (target == null) {
                    backupMessage = "Could not create the zip in the selected folder."
                } else {
                    val out = openSafOutput(context, target.uri)
                    if (out == null) {
                        backupMessage = "Could not open the target file for writing."
                    } else {
                        out.use { os ->
                            val result = graph.backupManager.backupWorldToStream(os) { msg ->
                                backupMessage = msg
                            }
                            result.onSuccess { files ->
                                backupMessage = "Backup complete: ${files.size} files zipped."
                            }.onFailure { e ->
                                backupMessage = "Backup failed: ${e.message}"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                backupMessage = "Backup failed: ${e.message}"
            } finally {
                backupBusy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- Status card -------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    StatePill(state)
                    Text(
                        text = if (uptimeMs > 0) "Uptime ${formatDuration(uptimeMs)}" else "Uptime -",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = graph.currentBdsVersion().ifBlank { "No server installed" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                KeyValueRow("World", worldName)
                KeyValueRow("Players", "${online.size}/$maxPlayers")
                KeyValueRow("Backend", graph.nativeLibs.let { "BDS via proot + box64" })
                if (attempts > 0) {
                    KeyValueRow("Auto-restart attempts", "$attempts/5")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            ContextCompat.startForegroundService(
                                context,
                                ServerService.intent(context, ServerService.ACTION_START),
                            )
                        },
                        enabled = state == ServerState.OFFLINE || state == ServerState.CRASHED,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                    Button(
                        onClick = {
                            ContextCompat.startForegroundService(
                                context,
                                ServerService.intent(context, ServerService.ACTION_STOP),
                            )
                        },
                        enabled = state == ServerState.ONLINE || state == ServerState.STARTING,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                    Button(
                        onClick = {
                            ContextCompat.startForegroundService(
                                context,
                                ServerService.intent(context, ServerService.ACTION_RESTART),
                            )
                        },
                        enabled = state == ServerState.ONLINE,
                        modifier = Modifier.weight(1f),
                    ) { Text("Restart") }
                }
            }
        }

        // --- Warning banners ---------------------------------------------------
        val thermal = deviceMetrics?.thermalStatus ?: 0
        if (thermal >= 3) {
            WarningBanner(
                text = "Thermal throttling: ${deviceMetrics?.thermalName}. Consider stopping the server or improving cooling.",
                severity = if (thermal >= 4) BannerSeverity.CRITICAL else BannerSeverity.WARNING,
            )
        }
        val battery = deviceMetrics?.batteryPercent
        if (battery != null && battery < 15 && deviceMetrics?.plugged == false) {
            WarningBanner(
                text = "Battery at $battery% and unplugged. A 24/7 server should stay on charger power.",
                severity = BannerSeverity.WARNING,
            )
        }
        val freeRam = deviceMetrics?.freeRamBytes
        if (freeRam != null && freeRam < 1_200_000_000L) {
            WarningBanner(
                text = "Device free RAM is low (${formatBytes(freeRam)}). Close background apps to avoid OOM kills.",
                severity = BannerSeverity.WARNING,
            )
        }

        // --- Gauges ------------------------------------------------------------
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Gauge(
                    title = "Server CPU",
                    value = serverMetrics?.cpuPercent,
                    maxValue = 100.0,
                    unit = "% of device",
                    decimals = 1,
                )
            }
            item {
                Gauge(
                    title = "Server RAM",
                    value = serverMetrics?.rssBytes?.let { it / 1024.0 / 1024.0 },
                    maxValue = 4096.0,
                    unit = "MB (RSS)",
                    decimals = 0,
                )
            }
            item {
                Gauge(
                    title = "Device RAM free",
                    value = deviceMetrics?.freeRamBytes?.let {
                        it / 1024.0 / 1024.0 / 1024.0
                    },
                    maxValue = (deviceMetrics?.totalRamBytes ?: 8_000_000_000L) / 1024.0 / 1024.0 / 1024.0,
                    unit = "GB free",
                    decimals = 1,
                )
            }
            item {
                Gauge(
                    title = "Battery temp",
                    value = deviceMetrics?.batteryTempC,
                    maxValue = 60.0,
                    unit = "°C",
                    decimals = 1,
                )
            }
            if (deviceMetrics?.cpuTempC != null) {
                item {
                    Gauge(
                        title = "CPU temp",
                        value = deviceMetrics?.cpuTempC,
                        maxValue = 110.0,
                        unit = "°C (est.)",
                        decimals = 0,
                    )
                }
            }
        }

        // --- Backup ------------------------------------------------------------
        SectionCard(title = "World backup") {
            Text(
                "Backups use the safe save protocol (save hold → save query → save resume), so the world stays consistent while the server runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            backupMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showBackupConfirm = true },
                    enabled = state == ServerState.ONLINE && !backupBusy && graph.backupManager.canBackup(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (backupBusy) "Backing up…" else "Backup world now") }
            }
        }

        // --- Connection info ---------------------------------------------------
        SectionCard(title = "How players connect") {
            KeyValueRow("Address (LAN)", "$localIp:$portV4 (UDP)", monospaceValue = true)
            Text(
                "Bedrock clients on the same Wi-Fi can join with the address above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpandableGuide()
        }
    }

    if (showBackupConfirm) {
        com.pocketbedrock.app.ui.components.ConfirmDialog(
            title = "Backup world now",
            text = "The world will be frozen for a few seconds while the files are zipped. Continue?",
            confirmLabel = "Backup",
            onConfirm = {
                if (settings.backupDirUri.isNotBlank()) {
                    backupLauncher.launch(null)
                } else {
                    scope.launch {
                        backupBusy = true
                        backupMessage = null
                        try {
                            val dir = graph.paths.defaultBackupDir ?: File(context.cacheDir, "backups")
                            dir.mkdirs()
                            val target = File(dir, "backup-$worldName-${System.currentTimeMillis()}.zip")
                            val result = graph.backupManager.backupWorld(target) { msg -> backupMessage = msg }
                            result.onSuccess {
                                backupMessage = "Backup saved to ${target.name}"
                            }.onFailure {
                                backupMessage = "Backup failed: ${it.message}"
                            }
                        } catch (e: Exception) {
                            backupMessage = "Backup failed: ${e.message}"
                        } finally {
                            backupBusy = false
                        }
                    }
                }
            },
            onDismiss = { showBackupConfirm = false },
        )
    }
}

@Composable
private fun ExpandableGuide() {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide network guide" else "Friends can't join? Open the network guide")
        }
        if (expanded) {
            Text(
                "1. Port forwarding (home Wi-Fi)\n" +
                    "In your router settings, forward UDP ports 19132 (and 19133 for IPv6) to this " +
                    "phone's LAN IP. Bedrock uses UDP, not TCP.\n\n" +
                    "2. Mobile data / CGNAT\n" +
                    "Mobile networks are usually behind CGNAT: forwarding is impossible. Use a " +
                    "tunnel instead:\n" +
                    "- playit.gg: free tunnel with a Minecraft Bedrock agent; run the agent on the " +
                    "phone (or any PC in the same network) and map the tunnel to <phone-ip>:19132.\n" +
                    "- Tailscale: install on the phone and on each friend's device; friends join via " +
                    "the Tailscale IP with port 19132.\n\n" +
                    "3. UPnP\n" +
                    "Automatic UPnP port-forwarding is not performed by PocketBedrock (it is a " +
                    "security risk on shared networks). Configure the router manually instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun findLocalIpv4(context: Context): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var wifiCandidate: String? = null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopback) {
                    if (addr.isSiteLocalAddress && wifiCandidate == null) wifiCandidate = addr.hostAddress
                }
            }
        }
        wifiCandidate ?: "0.0.0.0"
    } catch (_: Exception) {
        "0.0.0.0"
    }
}

private fun openSafOutput(context: Context, uri: android.net.Uri) =
    try {
        context.contentResolver.openOutputStream(uri)
    } catch (_: Exception) {
        null
    }
