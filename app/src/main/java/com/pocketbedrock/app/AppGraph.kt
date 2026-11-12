package com.pocketbedrock.app

import android.app.NotificationManager
import android.content.Context
import com.pocketbedrock.app.core.AddonsManager
import com.pocketbedrock.app.core.AllowlistManager
import com.pocketbedrock.app.core.BanManager
import com.pocketbedrock.app.core.BackupManager
import com.pocketbedrock.app.core.BdsBox64Backend
import com.pocketbedrock.app.core.LogLevel
import com.pocketbedrock.app.core.LogParser
import com.pocketbedrock.app.core.LogStore
import com.pocketbedrock.app.core.MetricsSampler
import com.pocketbedrock.app.core.PlayerTracker
import com.pocketbedrock.app.core.ProcessSupervisor
import com.pocketbedrock.app.core.ServerPropertiesManager
import com.pocketbedrock.app.core.ServerState
import com.pocketbedrock.app.core.StartConfig
import com.pocketbedrock.app.data.AppSettings
import com.pocketbedrock.app.data.db.PocketBedrockDatabase
import com.pocketbedrock.app.runtime.AppPaths
import com.pocketbedrock.app.runtime.BdsInstaller
import com.pocketbedrock.app.runtime.HttpDownloader
import com.pocketbedrock.app.runtime.NativeLibs
import com.pocketbedrock.app.runtime.RootfsInstaller
import com.pocketbedrock.app.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-rolled dependency graph (no DI framework: fewer deps, smaller APK, easier to
 * reason about a 24/7 process). Everything lives in [appScope], which outlives both the
 * UI and the foreground service, so log parsing, player tracking, ban enforcement and
 * auto-restart keep working while the app is closed.
 */
class AppGraph(val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(context)
    val paths = AppPaths(context).apply { ensureRootDirs() }
    val nativeLibs = NativeLibs(context)
    val downloader = HttpDownloader()
    val rootfsInstaller = RootfsInstaller(paths, downloader)
    val bdsInstaller = BdsInstaller(paths, downloader)
    val database = PocketBedrockDatabase.build(context)
    val metricsSampler = MetricsSampler(context, appScope)
    val logStore = LogStore(appScope, paths.logsDir)

    val supervisor = ProcessSupervisor(
        scope = appScope,
        logStore = logStore,
        backendFactory = { BdsBox64Backend(currentBdsVersion().ifBlank { null }) },
        configProvider = { buildStartConfig() },
    )

    val propsManager = ServerPropertiesManager { File(currentBdsDir(), "server.properties") }
    val playerTracker = PlayerTracker(database.playerDao(), appScope)

    private val commandSender: suspend (String) -> Boolean = { command ->
        supervisor.sendCommand(command)
    }

    val allowlistManager = AllowlistManager(
        propsManager = propsManager,
        commandSender = commandSender,
        isServerRunning = { supervisor.isRunning },
        fileProvider = { File(currentBdsDir(), "allowlist.json") },
        scope = appScope,
    )
    val banManager = BanManager(database.banDao(), commandSender, allowlistManager, appScope)
    val backupManager = BackupManager(logStore, commandSender) { currentBdsDir() }
    val addonsManager = AddonsManager(propsManager) { currentBdsDir() }

    /** Set by the foreground service; notification updates only make sense then. */
    @Volatile
    var serviceActive: Boolean = false

    @Volatile
    var activeBdsVersion: String = ""

    @Volatile
    var tempAutoStopC: Int = 0

    init {
        logStore.start()

        appScope.launch {
            settings.flow.collect { s ->
                supervisor.autoRestartEnabled = s.autoRestartOnCrash
                tempAutoStopC = s.tempAutoStopC
                if (s.installedBdsVersion.isNotBlank() && activeBdsVersion.isBlank()) {
                    activeBdsVersion = s.installedBdsVersion
                }
            }
        }

        // Single log dispatcher: player events -> tracker + ban enforcement,
        // version lines -> settings. Runs in app scope, independent of the UI.
        appScope.launch {
            logStore.lineFlow.collect { line ->
                val text = line.text
                val event = LogParser.extractPlayerEvent(text)
                if (event != null) {
                    playerTracker.onPlayerEvent(event.name, event.xuid, event.joined)
                    if (event.joined) {
                        appScope.launch {
                            runCatching { banManager.enforceOnJoin(event.name, event.xuid) }
                        }
                    }
                }
                LogParser.extractVersion(text)?.let { version ->
                    if (version != activeBdsVersion) {
                        activeBdsVersion = version
                        appScope.launch { settings.setInstalledBdsVersion(version) }
                    }
                }
            }
        }

        supervisor.onStateChange = { state ->
            if (state == ServerState.STARTING) playerTracker.clearOnline()
            refreshNotification(state)
        }
        supervisor.onCrashGivenUp = {
            appScope.launch {
                if (settingsSnapshot().notifyCrash) {
                    Notifications.postAlert(
                        context,
                        "Server crashed",
                        "Auto-restart gave up after 5 consecutive attempts. " +
                            "Open PocketBedrock to check the console.",
                    )
                }
            }
        }

        // Background sampling interval by default; the UI switches to the fast interval
        // while it is visible and back on stop.
        metricsSampler.configure(10_000) { supervisor.currentPid() }
        metricsSampler.start()
    }

    fun currentBdsDir(): File {
        val v = activeBdsVersion
        if (v.isNotBlank()) {
            val dir = paths.bdsDirFor(v)
            if (File(dir, "bedrock_server").exists()) return dir
        }
        return paths.installedBdsVersions().firstOrNull()
            ?.let { paths.bdsDirFor(it) }
            ?: paths.bdsRootDir
    }

    fun currentBdsVersion(): String =
        activeBdsVersion.ifBlank { paths.installedBdsVersions().firstOrNull() ?: "" }

    fun buildStartConfig(): StartConfig = StartConfig(
        bdsDir = currentBdsDir(),
        rootfsDir = paths.rootfsDir,
        prootHostPath = nativeLibs.proot,
        box64HostPath = nativeLibs.box64,
    )

    fun refreshNotification(state: ServerState) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!serviceActive) {
            manager.cancel(Notifications.ID_SERVER)
            return
        }
        manager.notify(
            Notifications.ID_SERVER,
            Notifications.serverNotification(
                context,
                state,
                playerTracker.online.value.size,
                currentBdsVersion().ifBlank { null },
            ),
        )
    }

    fun refreshNotificationPlayers() {
        if (serviceActive) refreshNotification(supervisor.state.value)
    }

    /** Foreground (screen on): fast sampling per the user's setting. */
    fun configureSamplerForeground() {
        appScope.launch {
            val s = settingsSnapshot()
            metricsSampler.configure(s.metricsIntervalFgMs.toLong()) { supervisor.currentPid() }
        }
    }

    /** Background: slow sampling per the user's setting (default 10 s). */
    fun configureSamplerBackground() {
        appScope.launch {
            val s = settingsSnapshot()
            metricsSampler.configure(s.metricsIntervalBgMs.toLong()) { supervisor.currentPid() }
        }
    }

    suspend fun settingsSnapshot(): AppSettings.Settings = settings.flow.first()
}
