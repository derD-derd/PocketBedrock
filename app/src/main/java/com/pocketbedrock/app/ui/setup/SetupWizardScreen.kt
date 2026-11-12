package com.pocketbedrock.app.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.core.LogLevel
import com.pocketbedrock.app.core.LogParser
import com.pocketbedrock.app.core.ServerState
import com.pocketbedrock.app.data.AppSettings
import com.pocketbedrock.app.runtime.DebExtractor
import com.pocketbedrock.app.runtime.DebResolver
import com.pocketbedrock.app.runtime.RuntimeSources
import com.pocketbedrock.app.ui.components.SectionCard
import com.pocketbedrock.app.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val STEPS = listOf("EULA", "Binaries", "Rootfs", "BDS", "x86 libs", "Verify")

@Composable
fun SetupWizardScreen(graph: AppGraph) {
    var step by rememberSaveable { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to PocketBedrock", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This wizard installs everything the Minecraft Bedrock Dedicated Server needs to " +
                "run on this phone: a minimal arm64 Linux rootfs, the official BDS (downloaded " +
                "directly from Mojang - never bundled, per their licensing), and the x86-64 " +
                "system libraries BDS links against.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEPS.forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        index == step -> MaterialTheme.colorScheme.primary
                        index < step -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
        when (step) {
            0 -> EulaStep(graph) { step = 1 }
            1 -> BinariesStep(graph) { step = 2 }
            2 -> RootfsStep(graph) { step = 3 }
            3 -> BdsStep(graph) { step = 4 }
            4 -> LibsStep(graph) { step = 5 }
            5 -> VerifyStep(graph)
        }
    }
}

@Composable
private fun StepProgress(message: String?, bytesRead: Long?, total: Long?) {
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (bytesRead != null) {
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (bytesRead.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Text("${formatBytes(bytesRead)} / ${formatBytes(total)}", style = MaterialTheme.typography.labelSmall)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
            Text(formatBytes(bytesRead), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EulaStep(graph: AppGraph, onNext: () -> Unit) {
    val context = LocalContext.current
    var accepted by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "Step 1 - Minecraft EULA & Privacy") {
        Text(
            "Running the Bedrock Dedicated Server requires agreeing to Mojang's Minecraft EULA " +
                "and the Microsoft Privacy Statement. PocketBedrock downloads the server software " +
                "directly from Mojang and does not bundle it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RuntimeSources.MINECRAFT_EULA_URL)))
                } catch (_: Exception) {
                }
            }) { Text("Read EULA") }
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RuntimeSources.MINECRAFT_PRIVACY_URL)))
                } catch (_: Exception) {
                }
            }) { Text("Privacy policy") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = accepted, onCheckedChange = { accepted = it })
            Text("I have read and accept the Minecraft EULA and the Privacy Statement.")
        }
        Button(onClick = {
            graph.appScope.launch { graph.settings.acceptEula(System.currentTimeMillis()) }
            onNext()
        }, enabled = accepted) { Text("Continue") }
    }
}

@Composable
private fun BinariesStep(graph: AppGraph, onNext: () -> Unit) {
    SectionCard(title = "Step 2 - proot & box64 helpers") {
        Text(
            "Android 10+ forbids executing binaries from app storage (W^X). PocketBedrock " +
                "therefore executes proot and box64 from nativeLibraryDir, which is why they are " +
                "packaged as libproot.so / libbox64.so inside the APK at build time. They cannot " +
                "be downloaded on device.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(graph.nativeLibs.describe(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        if (!graph.nativeLibs.allPresent) {
            Text(
                "Missing helper binaries: rebuild the APK with libproot.so and libbox64.so in " +
                    "app/src/main/jniLibs/arm64-v8a (see BUILDING.md). The wizard cannot continue " +
                    "until they are present.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onNext, enabled = graph.nativeLibs.allPresent) { Text("Continue") }
    }
}

@Composable
private fun RootfsStep(graph: AppGraph, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progressBytes by remember { mutableStateOf<Long?>(null) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            running = true
            error = null
            try {
                val dest = File(graph.paths.downloadDir, "imported-rootfs.tar.gz")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("Could not read the selected file")
                }
                graph.rootfsInstaller.install(dest, onPhase = { message = it })
                    .onSuccess {
                        graph.settings.setRootfsInstalled(true)
                        message = "Rootfs installed."
                    }
                    .onFailure { error = it.message }
                dest.delete()
            } finally {
                running = false
                progressBytes = null
            }
        }
    }

    SectionCard(title = "Step 3 - Linux rootfs (Ubuntu 22.04 arm64)") {
        if (graph.rootfsInstaller.isInstalled()) {
            Text("Rootfs is installed.", color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "A minimal glibc rootfs provides the filesystem layout BDS expects. Around 30 MB " +
                "download, ~100 MB extracted.",
            style = MaterialTheme.typography.bodySmall,
        )
        StepProgress(message, progressBytes, progressTotal)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        error = null
                        graph.rootfsInstaller.downloadAndInstall(
                            onPhase = { message = it },
                            onDownload = { read, total ->
                                progressBytes = read
                                progressTotal = total
                            },
                            onExtract = { entries -> message = "Extracted $entries entries…" },
                        ).onSuccess {
                            graph.settings.setRootfsInstalled(true)
                            message = "Rootfs installed."
                        }.onFailure { e ->
                            error = e.message
                        }
                        running = false
                        progressBytes = null
                    }
                },
                enabled = !running,
            ) { Text("Download rootfs") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !running) {
                Text("Import tar.gz")
            }
            if (graph.rootfsInstaller.isInstalled()) {
                Button(onClick = onNext) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun BdsStep(graph: AppGraph, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var version by rememberSaveable { mutableStateOf(graph.currentBdsVersion()) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progressBytes by remember { mutableStateOf<Long?>(null) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            running = true
            error = null
            try {
                val dest = File(graph.paths.downloadDir, "imported-bds.zip")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("Could not read the selected file")
                }
                val v = version.ifBlank { "" }
                if (v.isBlank()) {
                    error = "Type the version of the imported zip first (e.g. 1.21.50.10)."
                } else {
                    graph.bdsInstaller.installFromZip(dest, v)
                        .onSuccess {
                            graph.settings.setInstalledBdsVersion(v)
                            message = "BDS $v installed."
                        }
                        .onFailure { error = it.message }
                }
                dest.delete()
            } finally {
                running = false
                progressBytes = null
            }
        }
    }

    SectionCard(title = "Step 4 - Bedrock Dedicated Server (from Mojang)") {
        if (graph.currentBdsVersion().isNotBlank() && graph.bdsInstaller.isInstalled(graph.currentBdsVersion())) {
            Text("BDS ${graph.currentBdsVersion()} installed.", color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "PocketBedrock does not bundle BDS. The zip is fetched from minecraft.net " +
                "(bedrockdedicatedserver CDN); alternatively import a zip you downloaded yourself.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = version,
            onValueChange = { version = it },
            label = { Text("Version (e.g. 1.21.50.10)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        StepProgress(message, progressBytes, progressTotal)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        message = "Looking up the latest version on minecraft.wiki…"
                        graph.bdsInstaller.resolveLatestVersion().onSuccess {
                            version = it
                            message = "Latest release: $it"
                        }.onFailure { e -> message = null; error = e.message }
                    }
                },
                enabled = !running,
            ) { Text("Find latest") }
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        error = null
                        val v = version.trim()
                        if (v.isBlank()) {
                            error = "Enter a version or press Find latest."
                        } else {
                            graph.bdsInstaller.download(v) { read, total ->
                                progressBytes = read
                                progressTotal = total
                            }.onSuccess { zip ->
                                graph.bdsInstaller.installFromZip(zip, v)
                                    .onSuccess {
                                        graph.settings.setInstalledBdsVersion(v)
                                        message = "BDS $v installed."
                                    }
                                    .onFailure { e -> error = e.message }
                                zip.delete()
                            }.onFailure { e -> error = e.message }
                        }
                        running = false
                        progressBytes = null
                    }
                },
                enabled = !running,
            ) { Text("Download & install") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !running) {
                Text("Import zip")
            }
        }
        if (graph.currentBdsVersion().isNotBlank() && graph.bdsInstaller.isInstalled(graph.currentBdsVersion())) {
            Button(onClick = onNext) { Text("Continue") }
        }
    }
}

@Composable
private fun LibsStep(graph: AppGraph, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initial = AppSettings.Settings())
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progressBytes by remember { mutableStateOf<Long?>(null) }
    var progressTotal by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val libsDir = remember { File(graph.paths.rootfsDir, "usr/lib/x86_64-linux-gnu") }

    SectionCard(title = "Step 5 - x86-64 system libraries") {
        Text(
            "BDS is an x86-64 binary: box64 needs real amd64 copies of libstdc++, libssl3, " +
                "libcurl4 and their dependency tree inside the rootfs. PocketBedrock extracts " +
                "them straight from official Ubuntu jammy .debs - no apt required inside the guest.",
            style = MaterialTheme.typography.bodySmall,
        )
        StepProgress(message, progressBytes, progressTotal)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        error = null
                        val failed = mutableListOf<String>()
                        val installed = mutableListOf<String>()
                        withContext(Dispatchers.IO) {
                            val resolver = DebResolver(graph.downloader)
                            for (pkg in RuntimeSources.AMD64_PACKAGES) {
                                try {
                                    message = "Fetching ${pkg.packageName}…"
                                    progressBytes = null
                                    val deb = resolver.resolve(pkg)
                                    val file = File(graph.paths.debCacheDir, "${pkg.packageName}_amd64.deb")
                                    graph.downloader.downloadToFile(deb.url, file) { read, total ->
                                        progressBytes = read
                                        progressTotal = total
                                    }
                                    val copied = DebExtractor.extractSharedLibs(file, libsDir)
                                    installed += copied
                                    file.delete()
                                } catch (e: Exception) {
                                    failed += "${pkg.packageName}: ${e.message}"
                                }
                            }
                        }
                        progressBytes = null
                        if (failed.isEmpty()) {
                            graph.settings.setLibsInstalled(true)
                            message = "Installed ${installed.size} libraries into the rootfs."
                        } else {
                            error = "Some packages failed (${failed.size}): " + failed.take(5).joinToString()
                            if (installed.isNotEmpty()) {
                                graph.settings.setLibsInstalled(true)
                            }
                        }
                        running = false
                    }
                },
                enabled = !running && graph.rootfsInstaller.isInstalled(),
            ) { Text("Install x86-64 libraries") }
            if (settings.rootfsInstalled && libsDir.exists() && libsDir.listFiles()?.isNotEmpty() == true) {
                OutlinedButton(onClick = onNext) { Text("Continue") }
            }
        }
        if (libsDir.exists() && libsDir.listFiles()?.isNotEmpty() == true) {
            Button(onClick = onNext) { Text("Continue") }
        }
    }
}

@Composable
private fun VerifyStep(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var errorState by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var tail by remember { mutableStateOf<List<String>>(emptyList()) }

    SectionCard(title = "Step 6 - Verification boot") {
        Text(
            "Starts the real server once, waits for \"Server started.\", then stops it safely. " +
                "This proves proot, box64, the rootfs, BDS and the libraries all work together.",
            style = MaterialTheme.typography.bodySmall,
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        errorState?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (tail.isNotEmpty()) {
            Text(
                tail.takeLast(20).joinToString("\n"),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        success = false
                        message = "Starting verification boot…"
                        val previousAutoRestart = graph.supervisor.autoRestartEnabled
                        graph.supervisor.autoRestartEnabled = false
                        try {
                            val startResult = graph.supervisor.start()
                            startResult.onFailure { e ->
                                message = null
                                errorState = e.message ?: "start failed"
                            }
                            if (startResult.isSuccess) {
                                val line = graph.logStore.awaitLine(
                                    { LogParser.isServerStartedLine(it.text) },
                                    180_000,
                                )
                                if (line != null) {
                                    success = true
                                    errorState = null
                                    message = "Verification OK - server booted and is being stopped safely."
                                    graph.supervisor.stop()
                                } else {
                                    message = null
                                    errorState = "Server did not print \"Server started.\" within 3 minutes."
                                    graph.supervisor.stop()
                                }
                            }
                        } finally {
                            graph.supervisor.autoRestartEnabled = previousAutoRestart
                            tail = graph.logStore.snapshot().map { it.text }
                            running = false
                        }
                    }
                },
                enabled = !running && graph.nativeLibs.allPresent && graph.bdsInstaller.isInstalled(graph.currentBdsVersion()),
            ) { Text(if (success) "Run verification again" else "Run verification boot") }
            if (success) {
                Button(onClick = { graph.appScope.launch { graph.settings.setSetupCompleted(true) } }) {
                    Text("Finish setup")
                }
            }
        }
        Text(
            "If verification fails, check the log tail above. Missing libraries usually mean " +
                "step 5 failed partially; 'Exec format error' means libbox64.so is not an " +
                "Android/bionic build; DNS failures suggest a captive portal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
