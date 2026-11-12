package com.pocketbedrock.app.ui.addons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.core.AddonsManager
import com.pocketbedrock.app.ui.components.BannerSeverity
import com.pocketbedrock.app.ui.components.KeyValueRow
import com.pocketbedrock.app.ui.components.WarningBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddonsScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var packs by remember { mutableStateOf<List<AddonsManager.PackInfo>>(emptyList()) }
    var missingDeps by remember { mutableStateOf<List<String>>(emptyList()) }
    var changedWhileRunning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<AddonsManager.PackInfo?>(null) }
    var showExperimentalInfo by remember { mutableStateOf(false) }

    fun refresh() {
        packs = graph.addonsManager.listPacks()
        missingDeps = graph.addonsManager.missingDependencies(packs)
    }

    LaunchedEffect(Unit) { refresh() }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            installError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    graph.addonsManager.install(uri, context.contentResolver)
                }
                result.onSuccess { installed ->
                    changedWhileRunning = graph.supervisor.isRunning || changedWhileRunning
                    installError = null
                }.onFailure { e ->
                    installError = e.message ?: "Install failed"
                }
                refresh()
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    installLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*"),
                    )
                }, enabled = !busy) { Text(if (busy) "Installing…" else "Install .mcaddon/.mcpack/.zip") }
                OutlinedButton(onClick = { showExperimentalInfo = true }) { Text("Experimental toggles") }
            }
        }
        if (graph.supervisor.isRunning || changedWhileRunning) {
            item {
                WarningBanner(
                    text = if (graph.supervisor.isRunning) {
                        "The server is running: pack changes take effect after a restart."
                    } else {
                        "Pack files changed: restart the server to load them."
                    },
                    severity = BannerSeverity.INFO,
                )
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        scope.launch { graph.supervisor.restart() }
                        changedWhileRunning = false
                    }) { Text("Restart server now") }
                }
            }
        }
        if (missingDeps.isNotEmpty()) {
            item {
                WarningBanner(
                    text = "Missing pack dependencies: ${missingDeps.joinToString()}. " +
                        "Install the resource/behavior packs these UUIDs refer to, or the world may misbehave.",
                    severity = BannerSeverity.WARNING,
                )
            }
        }
        installError?.let { error ->
            item {
                WarningBanner(text = error, severity = BannerSeverity.CRITICAL, onDismiss = { installError = null })
            }
        }
        if (packs.isEmpty()) {
            item {
                Text(
                    "No packs installed. Install a .mcpack (single pack) or .mcaddon (multiple packs) " +
                        "and enable it here to register it in the active world.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(packs, key = { it.dir.absolutePath }) { pack ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                pack.manifest?.name ?: pack.dir.name,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            pack.manifest?.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                        Switch(
                            checked = pack.registered,
                            enabled = pack.manifest != null,
                            onCheckedChange = { enabled ->
                                graph.addonsManager.setEnabled(pack, enabled)
                                changedWhileRunning = graph.supervisor.isRunning
                                refresh()
                            },
                        )
                    }
                    if (pack.parseError != null) {
                        Text(
                            pack.parseError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        pack.manifest?.let { manifest ->
                            KeyValueRow("Type", pack.type.name.lowercase())
                            KeyValueRow("Version", manifest.versionString)
                            KeyValueRow("UUID", manifest.uuid, monospaceValue = true)
                            if (manifest.dependencies.isNotEmpty()) {
                                KeyValueRow(
                                    "Dependencies",
                                    manifest.dependencies.joinToString { it.uuid.take(8) + "…" },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { deleteTarget = pack }, label = { Text("Delete") })
                    }
                }
            }
        }
    }

    deleteTarget?.let { pack ->
        com.pocketbedrock.app.ui.components.ConfirmDialog(
            title = "Delete pack",
            text = "Delete ${pack.manifest?.name ?: pack.dir.name} from disk and unregister it from the world? This cannot be undone.",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                graph.addonsManager.deletePack(pack)
                changedWhileRunning = graph.supervisor.isRunning
                refresh()
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (showExperimentalInfo) {
        AlertDialog(
            onDismissRequest = { showExperimentalInfo = false },
            title = { Text("Experimental toggles") },
            text = {
                Text(
                    "world's level.dat is a little-endian NBT file. PocketBedrock intentionally " +
                        "does not edit it yet, so experiments such as the scripting API / GameTest " +
                        "framework cannot be flipped from the UI in this version. Workaround: set " +
                        "the experiments once with the Windows/Linux Bedrock client, then copy the " +
                        "world into worlds/ on the phone - behavior packs then behave normally.",
                )
            },
            confirmButton = { TextButton(onClick = { showExperimentalInfo = false }) { Text("OK") } },
        )
    }
}
