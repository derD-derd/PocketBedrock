package com.pocketbedrock.app.ui.console

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.core.LogLevel
import com.pocketbedrock.app.core.LogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Common BDS console commands for autocomplete (name + one-line description). */
private val COMMANDS = listOf(
    "say" to "Broadcast a message to all players",
    "list" to "List players currently online",
    "kick" to "kick <player> <reason> - remove a player",
    "op" to "op <player> - grant operator (via permissions.json)",
    "deop" to "deop <player> - revoke operator",
    "gamemode" to "gamemode <mode> <player> - survival/creative/adventure",
    "difficulty" to "difficulty <peaceful|easy|normal|hard>",
    "time" to "time set <day|night|value>",
    "weather" to "weather <clear|rain|thunder>",
    "give" to "give <player> <item> [amount]",
    "tp" to "tp <player> <target>",
    "allowlist" to "allowlist add|remove|list|reload",
    "save" to "save hold|query|resume",
    "stop" to "Gracefully stop the server (saves the world)",
    "kill" to "kill <entity>",
    "effect" to "effect <player> <effect> [seconds]",
    "testfor" to "testfor <player> - check a player exists",
    "clear" to "clear <player> - empty inventory",
)

@Composable
fun ConsoleScreen(graph: AppGraph) {
    val lines by graph.logStore.lines.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var levelFilter by rememberSaveable { mutableStateOf("ALL") }
    var frozen by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var command by remember { mutableStateOf(TextFieldValue("")) }
    var showHistory by remember { mutableStateOf(false) }
    var sayDialog by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    val historyIndex = remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val displayed = if (paused) frozen else lines
    val filtered = displayed.filter { line ->
        (levelFilter == "ALL" || line.level.name == levelFilter) &&
            (searchQuery.isBlank() || line.text.contains(searchQuery, ignoreCase = true))
    }

    // Auto-scroll only when enabled; stable LazyColumn keys keep this jank-free.
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    fun send(cmdRaw: String) {
        val cmd = cmdRaw.trim().removePrefix("/")
        if (cmd.isEmpty()) return
        graph.logStore.appendDirect(LogLevel.RAW, "> $cmd")
        history.add(cmd)
        if (history.size > 100) history.removeAt(0)
        historyIndex.intValue = history.size
        command = TextFieldValue("")
        launchCommand(graph.appScope, cmd, graph)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Level filters
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = levelFilter == "ALL", onClick = { levelFilter = "ALL" }, label = { Text("All") })
            FilterChip(selected = levelFilter == "INFO", onClick = { levelFilter = "INFO" }, label = { Text("Info") })
            FilterChip(selected = levelFilter == "WARN", onClick = { levelFilter = "WARN" }, label = { Text("Warn") })
            FilterChip(selected = levelFilter == "ERROR", onClick = { levelFilter = "ERROR" }, label = { Text("Error") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            Text(" Auto-scroll", style = MaterialTheme.typography.labelMedium)
            Switch(checked = paused, onCheckedChange = { if (it) frozen = lines; paused = it })
            Text(" Pause", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { clipboard.setText(AnnotatedString(graph.logStore.copyLast(500))) }) { Text("Copy") }
            TextButton(onClick = { graph.logStore.clear() }) { Text("Clear") }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search log") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Terminal
        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(filtered, key = { it.seq }) { line ->
                    Text(
                        text = line.text,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = line.color(),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { clipboard.setText(AnnotatedString(line.text)) },
                            ),
                    )
                }
            }
        }

        // Quick action chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { send("save") }, label = { Text("Save") })
            AssistChip(onClick = { send("time set day") }, label = { Text("Time day") })
            AssistChip(onClick = { send("weather clear") }, label = { Text("Weather clear") })
            AssistChip(onClick = { sayDialog = true }, label = { Text("Say…") })
        }

        // Command input with autocomplete + history
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val currentToken = command.text.trimStart('/').trim().split(" ").firstOrNull() ?: ""
                val matches = if (currentToken.isNotBlank() && !command.text.contains(" ")) {
                    COMMANDS.filter { it.first.startsWith(currentToken) && it.first != currentToken }.take(4)
                } else {
                    emptyList()
                }
                matches.forEach { (name, desc) ->
                    Text(
                        "$name - $desc",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { command = TextFieldValue("$name ") },
                            )
                            .padding(horizontal = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(Icons.Filled.History, contentDescription = "Command history")
                    }
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = {
                            Text("Command (e.g. say Hello)", style = MaterialTheme.typography.bodySmall)
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (history.isNotEmpty()) {
                                            historyIndex.intValue =
                                                (historyIndex.intValue - 1).coerceIn(0, history.size - 1)
                                            command = TextFieldValue(history[historyIndex.intValue])
                                        }
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (history.isNotEmpty()) {
                                            historyIndex.intValue =
                                                (historyIndex.intValue + 1).coerceAtMost(history.size)
                                            command = TextFieldValue(
                                                history.getOrNull(historyIndex.intValue) ?: "",
                                            )
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                        trailingIcon = {
                            IconButton(onClick = { send(command.text) }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        },
                    )
                }
                if (showHistory && history.isNotEmpty()) {
                    DropdownMenu(expanded = true, onDismissRequest = { showHistory = false }) {
                        history.asReversed().take(20).forEach { cmd ->
                            DropdownMenuItem(
                                text = { Text(cmd, fontFamily = FontFamily.Monospace) },
                                onClick = {
                                    command = TextFieldValue(cmd)
                                    showHistory = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (sayDialog) {
        var sayText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sayDialog = false },
            title = { Text("Broadcast a message") },
            text = {
                OutlinedTextField(
                    value = sayText,
                    onValueChange = { sayText = it },
                    label = { Text("Message") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    send("say ${sayText.trim()}")
                    sayDialog = false
                }) { Text("Say") }
            },
            dismissButton = { TextButton(onClick = { sayDialog = false }) { Text("Cancel") } },
        )
    }
}

private fun LogLevel.color(): Color = when (this) {
    LogLevel.INFO -> Color(0xFFD7DEE8)
    LogLevel.WARN -> Color(0xFFFFD98A)
    LogLevel.ERROR -> Color(0xFFFF8A8A)
    LogLevel.RAW -> Color(0xFF8A93A1)
}

private fun launchCommand(scope: CoroutineScope, cmd: String, graph: AppGraph) {
    scope.launch {
        val ok = graph.supervisor.sendCommand(cmd)
        if (!ok) {
            graph.logStore.appendDirect(LogLevel.WARN, "Server is not running; command not delivered.")
        }
    }
}
