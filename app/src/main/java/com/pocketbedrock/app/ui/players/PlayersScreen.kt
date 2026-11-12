package com.pocketbedrock.app.ui.players

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.core.AllowlistEntry
import com.pocketbedrock.app.core.PermissionsManager
import com.pocketbedrock.app.ui.components.ConfirmDialog
import com.pocketbedrock.app.ui.components.KeyValueRow
import com.pocketbedrock.app.ui.components.formatPlaytime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlayersScreen(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf("Online") }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        listOf("Online", "History", "Whitelist", "Bans").forEach { name ->
            FilterChip(
                selected = tab == name,
                onClick = { tab = name },
                label = { Text(name) },
            )
        }
    }
    when (tab) {
        "History" -> HistoryTab(graph)
        "Whitelist" -> AllowlistTab(graph)
        "Bans" -> BansTab(graph)
        else -> OnlineTab(graph)
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@Composable
private fun OnlineTab(graph: AppGraph) {
    val online by graph.playerTracker.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val permissions = remember { PermissionsManager { graph.currentBdsDir() } }

    var kickTarget by remember { mutableStateOf<String?>(null) }
    var banTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // name to xuid

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (online.isEmpty()) {
            Text(
                "No players online. Players appear here within a second of connecting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        online.forEach { player ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(player.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "session ${formatPlaytime((now - player.joinedAtMs) / 1000)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    KeyValueRow("XUID", player.xuid, monospaceValue = true)
                    KeyValueRow(
                        "Permission",
                        permissions.permissionFor(player.xuid) ?: "default (from settings)",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { kickTarget = player.name }) { Text("Kick") }
                        TextButton(onClick = { banTarget = player.name to player.xuid }) { Text("Ban") }
                        TextButton(onClick = {
                            scope.launch {
                                graph.allowlistManager.addEntry(AllowlistEntry(player.name, player.xuid))
                            }
                        }) { Text("Allowlist") }
                        TextButton(onClick = {
                            permissions.set(player.xuid, player.name, "operator")
                            scope.launch {
                                graph.logStore.appendDirect(
                                    com.pocketbedrock.app.core.LogLevel.INFO,
                                    "${player.name} set to operator in permissions.json (restart required).",
                                )
                            }
                        }) { Text("OP") }
                        TextButton(onClick = {
                            permissions.set(player.xuid, player.name, "member")
                        }) { Text("Deop") }
                    }
                }
            }
        }
    }

    kickTarget?.let { name ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            title = { Text("Kick $name") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        graph.supervisor.sendCommand(
                            "kick \"$name\" ${reason.ifBlank { "Kicked by operator" }}",
                        )
                    }
                    kickTarget = null
                }) { Text("Kick") }
            },
            dismissButton = { TextButton(onClick = { kickTarget = null }) { Text("Cancel") } },
        )
    }

    banTarget?.let { (name, xuid) ->
        var reason by remember { mutableStateOf("") }
        var days by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { banTarget = null },
            title = { Text("Ban $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it },
                        label = { Text("Days (empty = permanent)") },
                        singleLine = true,
                    )
                    Text(
                        "Bans are enforced by PocketBedrock: when a banned player connects, " +
                            "they are kicked immediately. Enforcement also works while the UI is closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val expires = days.trim().toLongOrNull()?.let { d ->
                        System.currentTimeMillis() + d * 24 * 3600 * 1000
                    }
                    scope.launch {
                        graph.banManager.ban(name, xuid, reason, expires)
                    }
                    banTarget = null
                }) { Text("Ban") }
            },
            dismissButton = { TextButton(onClick = { banTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryTab(graph: AppGraph) {
    val players by graph.database.playerDao().observeAll()
        .collectAsStateWithLifecycle(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (players.isEmpty()) {
            Text(
                "No players seen yet. Every player ever connected is remembered here with total playtime.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        players.forEach { player ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(player.lastName, style = MaterialTheme.typography.titleMedium)
                    KeyValueRow("XUID", player.xuid, monospaceValue = true)
                    KeyValueRow("First seen", dateFormat.format(Date(player.firstSeenMs)))
                    KeyValueRow("Last seen", dateFormat.format(Date(player.lastSeenMs)))
                    KeyValueRow("Total playtime", formatPlaytime(player.playtimeSeconds))
                    KeyValueRow("Sessions", "${player.sessions}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                graph.banManager.ban(player.lastName, player.xuid, "Banned by operator", null)
                            }
                        }) { Text("Ban") }
                        TextButton(onClick = {
                            scope.launch {
                                graph.allowlistManager.addEntry(AllowlistEntry(player.lastName, player.xuid))
                            }
                        }) { Text("Allowlist") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllowlistTab(graph: AppGraph) {
    val enabled by graph.allowlistManager.enabled.collectAsStateWithLifecycle()
    val entries by graph.allowlistManager.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val running = graph.supervisor.isRunning
    var addDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<AllowlistEntry?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Whitelist (Allowlist)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Only allowlisted players can join. Toggling edits server.properties " +
                            "and requires a restart. Entries are synced via allowlist commands " +
                            "while the server runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { checked ->
                    scope.launch { graph.allowlistManager.setEnabled(checked) }
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addDialog = true }) { Text("Add player") }
        }
        entries.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "xuid ${entry.xuid}" + if (entry.ignoresPlayerLimit) " (ignores player limit)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { removeTarget = entry }) { Text("Remove") }
                }
            }
        }
    }

    if (addDialog) {
        var name by remember { mutableStateOf("") }
        var xuid by remember { mutableStateOf("") }
        var ignoresLimit by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add to allowlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Player name") }, singleLine = true)
                    OutlinedTextField(value = xuid, onValueChange = { xuid = it }, label = { Text("XUID (optional)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ignoresLimit, onCheckedChange = { ignoresLimit = it })
                        Text("Ignores player limit")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        scope.launch {
                            graph.allowlistManager.addEntry(
                                AllowlistEntry(name.trim(), xuid.trim(), ignoresLimit),
                            )
                        }
                    }
                    addDialog = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } },
        )
    }

    removeTarget?.let { entry ->
        ConfirmDialog(
            title = "Remove from allowlist",
            text = "Remove ${entry.name} from the whitelist (allowlist)?",
            confirmLabel = "Remove",
            onConfirm = {
                scope.launch { graph.allowlistManager.removeEntry(entry.name, entry.xuid) }
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun BansTab(graph: AppGraph) {
    val bans by graph.banManager.bans.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(graph.banManager.exportJson().toByteArray(Charsets.UTF_8))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            if (text != null) {
                graph.banManager.importJson(text).onSuccess { count ->
                    graph.logStore.appendDirect(
                        com.pocketbedrock.app.core.LogLevel.INFO,
                        "Imported $count bans.",
                    )
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Vanilla BDS has no ban command, so bans are enforced by PocketBedrock: a banned " +
                "XUID gets kicked the moment it connects - even with the app closed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addDialog = true }) { Text("Add ban") }
            OutlinedButton(onClick = { exportLauncher.launch("pocketbedrock-bans.json") }) { Text("Export") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("Import") }
        }
        if (bans.isEmpty()) {
            Text("No active bans.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bans.forEach { ban ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ban.name, style = MaterialTheme.typography.titleSmall)
                    KeyValueRow("XUID", ban.xuid, monospaceValue = true)
                    KeyValueRow("Reason", ban.reason)
                    KeyValueRow("Banned", dateFormat.format(Date(ban.bannedAtMs)))
                    KeyValueRow(
                        "Expires",
                        ban.expiresAtMs?.let { dateFormat.format(Date(it)) } ?: "never",
                    )
                    TextButton(onClick = { scope.launch { graph.banManager.unban(ban.xuid) } }) {
                        Text("Unban")
                    }
                }
            }
        }
    }

    if (addDialog) {
        var name by remember { mutableStateOf("") }
        var xuid by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add ban") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Player name") }, singleLine = true)
                    OutlinedTextField(value = xuid, onValueChange = { xuid = it }, label = { Text("XUID (recommended)") }, singleLine = true)
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (xuid.isNotBlank()) {
                        scope.launch {
                            graph.banManager.ban(name.trim(), xuid.trim(), reason, null)
                        }
                    }
                    addDialog = false
                }) { Text("Ban") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } },
        )
    }
}
