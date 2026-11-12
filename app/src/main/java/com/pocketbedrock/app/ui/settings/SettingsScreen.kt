package com.pocketbedrock.app.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.core.PropType
import com.pocketbedrock.app.core.PropertyCatalog
import com.pocketbedrock.app.core.PropertyGroup
import com.pocketbedrock.app.data.AppSettings
import com.pocketbedrock.app.data.ThemeMode
import com.pocketbedrock.app.ui.components.BannerSeverity
import com.pocketbedrock.app.ui.components.SectionCard
import com.pocketbedrock.app.ui.components.WarningBanner
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initial = AppSettings.Settings())

    val props = remember { mutableStateMapOf<String, String>().apply { putAll(graph.propsManager.read()) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestart by rememberSaveable { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    val backupDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            scope.launch { graph.settings.setBackupDirUri(uri.toString()) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Performance presets
        SectionCard(title = "Performance presets (tuned for this phone)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyCatalog.presets.forEach { preset ->
                    OutlinedButton(
                        onClick = {
                            preset.values.forEach { (k, v) -> props[k] = v }
                            saveMessage = "${preset.title} preset loaded - press Save to apply."
                        },
                    ) { Text(preset.title) }
                }
            }
            Text(
                "Battery saver: view-distance 6, tick-distance 4, 2 threads - lowest draw. " +
                    "Balanced: view-distance 8 - recommended with an active cooler. " +
                    "Max performance: view-distance 12 - expect throttling without external cooling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (pendingRestart) {
            WarningBanner(
                text = "Settings changed while the server was running - a restart is required for them to take effect.",
                severity = BannerSeverity.INFO,
            )
            Button(onClick = { scope.launch { graph.supervisor.restart() }; pendingRestart = false }) {
                Text("Restart server now")
            }
        }

        // server.properties form, grouped
        PropertyGroup.entries.forEach { group ->
            SectionCard(title = group.title) {
                Text(
                    "All values below require a server restart to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PropertyCatalog.specs.filter { it.group == group }.forEach { spec ->
                    PropertyEditor(spec, props, validationErrors)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val errors = graph.propsManager.validate(props)
                    validationErrors = errors
                    val bad = errors.values.any { it != null }
                    if (bad) {
                        saveMessage = "Fix the highlighted values before saving."
                    } else {
                        scope.launch {
                            val catalogKeys = PropertyCatalog.specs.map { it.key }.toSet()
                            graph.propsManager.write(props.filterKeys { it in catalogKeys })
                            pendingRestart = graph.supervisor.isRunning
                            saveMessage = if (pendingRestart) {
                                "Saved. Restart the server to apply."
                            } else {
                                "Saved. Values apply on the next server start."
                            }
                        }
                    }
                },
            ) { Text("Save server settings") }
            OutlinedButton(onClick = { navController.navigate("settings/raw") }) {
                Text("Advanced: raw editor")
            }
        }
        saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        // App settings
        SectionCard(title = "App settings") {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.DARK to "Dark",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.SYSTEM to "System",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { scope.launch { graph.settings.setThemeMode(mode) } },
                        label = { Text(label) },
                    )
                }
            }
            SettingSwitch("Keep screen on while the app is open", settings.keepScreenOn) {
                scope.launch { graph.settings.setKeepScreenOn(it) }
            }
            SettingSwitch("Auto-start server on phone boot", settings.autoStartOnBoot) {
                scope.launch { graph.settings.setAutoStartOnBoot(it) }
            }
            SettingSwitch("Auto-restart on crash (exponential backoff, max 5)", settings.autoRestartOnCrash) {
                scope.launch { graph.settings.setAutoRestartOnCrash(it) }
            }
            SettingSwitch("Notify on player join", settings.notifyPlayerJoin) {
                scope.launch { graph.settings.setNotifyPlayerJoin(it) }
            }
            SettingSwitch("Notify on crash", settings.notifyCrash) {
                scope.launch { graph.settings.setNotifyCrash(it) }
            }
            SettingSwitch("Notify on overheat", settings.notifyOverheat) {
                scope.launch { graph.settings.setNotifyOverheat(it) }
            }
            SettingSwitch("Scheduled daily restart", settings.dailyRestartEnabled) {
                scope.launch { graph.settings.setDailyRestart(it, settings.dailyRestartHour) }
            }
            if (settings.dailyRestartEnabled) {
                NumberField("Restart hour (0-23)", settings.dailyRestartHour.toString()) { value ->
                    val hour = value.toIntOrNull() ?: return@NumberField
                    scope.launch { graph.settings.setDailyRestart(true, hour) }
                }
            }
            NumberField("Auto-stop at temperature °C (0 = off)", settings.tempAutoStopC.toString()) { value ->
                val t = value.toIntOrNull() ?: return@NumberField
                scope.launch { graph.settings.setTempAutoStopC(t) }
            }
            NumberField("Log files to keep on disk", settings.logRetentionFiles.toString()) { value ->
                val n = value.toIntOrNull() ?: return@NumberField
                scope.launch { graph.settings.setLogRetentionFiles(n) }
            }
            Text(
                "Log buffer: the last 2,000 lines stay in memory; older lines go to rotating files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Backups") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { backupDirLauncher.launch(null) }) { Text("Choose backup folder") }
            }
            Text(
                if (settings.backupDirUri.isBlank()) {
                    "Default: app external storage (no permission needed). Pick any folder for backups."
                } else {
                    "Backup folder: ${settings.backupDirUri.takeLast(60)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Keep the server alive") {
            Text(
                "Battery optimization and Samsung's app sleeper can kill a 24/7 server. " +
                    "Use the guided helper to fix this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { navController.navigate("settings/battery") }) {
                Text("Battery optimization helper")
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onCommit: (String) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = { onCommit(text) }) { Text("Set") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PropertyEditor(
    spec: com.pocketbedrock.app.core.PropertySpec,
    props: MutableMap<String, String>,
    errors: Map<String, String?>,
) {
    val current = props[spec.key] ?: spec.defaultValue
    val error = errors[spec.key]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (spec.type) {
            PropType.BOOL -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            spec.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = current == "true", onCheckedChange = { props[spec.key] = if (it) "true" else "false" })
                }
            }
            PropType.ENUM -> {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.options.forEach { option ->
                        FilterChip(
                            selected = current == option,
                            onClick = { props[spec.key] = option },
                            label = { Text(option) },
                        )
                    }
                }
                Text(spec.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                var text by rememberSaveable(spec.key, current) { mutableStateOf(current) }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        props[spec.key] = it
                    },
                    label = { Text(spec.label) },
                    isError = error != null,
                    supportingText = {
                        Column {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Text(spec.description)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
