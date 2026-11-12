package com.pocketbedrock.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.PocketBedrockApp
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Foreground service (type specialUse) that keeps the app process alive 24/7 while the
 * server runs: partial wake lock, persistent notification with Start/Stop/Restart
 * actions, thermal listener, temperature auto-stop and the scheduled daily restart.
 *
 * The supervision logic itself lives in AppGraph's ProcessSupervisor, so even if this
 * service is recreated the server state is untouched.
 */
class ServerService : Service() {

    private val graph: AppGraph by lazy { (application as PocketBedrockApp).graph }
    private val scope by lazy { graph.appScope }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAutoStopAtMs = 0L

    private val thermalExecutor = Executors.newSingleThreadExecutor()

    private val thermalListener =
        PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                scope.launch {
                    val settings = graph.settingsSnapshot()
                    graph.logStore.appendDirect(
                        com.pocketbedrock.app.core.LogLevel.WARN,
                        "Thermal status escalated to ${com.pocketbedrock.app.core.thermalStatusName(status)}.",
                    )
                    if (settings.notifyOverheat) {
                        Notifications.postAlert(
                            this@ServerService,
                            "Device is overheating",
                            "Thermal status: ${com.pocketbedrock.app.core.thermalStatusName(status)}. " +
                                "Consider lowering the view-distance or stopping the server.",
                        )
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        graph.serviceActive = true
        powerManager.addThermalStatusListener(thermalExecutor, thermalListener)
        observeTemperatureAutoStop()
        observePlayerCount()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Notifications.ID_SERVER,
            Notifications.serverNotification(
                this,
                graph.supervisor.state.value,
                graph.playerTracker.online.value.size,
                graph.currentBdsVersion().ifBlank { null },
            ),
        )
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_RESTART -> scope.launch { graph.supervisor.restart() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        graph.serviceActive = false
        releaseWakeLock()
        try {
            powerManager.removeThermalStatusListener(thermalListener)
        } catch (_: Exception) {
        }
        thermalExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        acquireWakeLock()
        scope.launch {
            val settings = graph.settingsSnapshot()
            if (!graph.nativeLibs.allPresent) {
                Notifications.postAlert(
                    this@ServerService,
                    "Setup incomplete",
                    "libproot.so / libbox64.so are missing from the APK. See BUILDING.md in the " +
                        "PocketBedrock project for how to bundle them.",
                )
                stopSelfWhenIdle()
                return@launch
            }
            if (!File(graph.currentBdsDir(), "bedrock_server").exists()) {
                Notifications.postAlert(
                    this@ServerService,
                    "Setup incomplete",
                    "No Bedrock Dedicated Server installation found. Open PocketBedrock and finish the setup wizard.",
                )
                stopSelfWhenIdle()
                return@launch
            }
            graph.supervisor.start().onFailure { e ->
                Notifications.postAlert(
                    this@ServerService,
                    "Server failed to start",
                    e.message ?: "Unknown error",
                )
            }
            scheduleDailyRestartIfEnabled(settings.dailyRestartEnabled, settings.dailyRestartHour)
        }
    }

    private fun stopServer() {
        scope.launch {
            graph.supervisor.stop()
            releaseWakeLock()
            stopSelfWhenIdle()
        }
    }

    private fun stopSelfWhenIdle() {
        if (!graph.supervisor.isRunning) {
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketBedrock:server")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    /** Auto-stop (with a normal graceful save via `stop`) at the user-defined temperature. */
    private fun observeTemperatureAutoStop() {
        scope.launch {
            graph.metricsSampler.device.collect { device ->
                val threshold = graph.tempAutoStopC
                if (threshold <= 0 || !graph.supervisor.isRunning) return@collect
                val temp = device?.batteryTempC ?: return@collect
                if (temp >= threshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastAutoStopAtMs < AUTO_STOP_COOLDOWN_MS) return@collect
                    lastAutoStopAtMs = now
                    graph.logStore.appendDirect(
                        com.pocketbedrock.app.core.LogLevel.WARN,
                        "Temperature ${temp}°C reached the auto-stop threshold ($threshold°C). Stopping server safely.",
                    )
                    graph.supervisor.stop()
                    Notifications.postAlert(
                        this@ServerService,
                        "Server stopped: overheat protection",
                        "Device temperature reached ${temp}°C (threshold $threshold°C). " +
                            "The world was saved with a graceful stop.",
                    )
                    releaseWakeLock()
                    stopSelfWhenIdle()
                }
            }
        }
    }

    private fun observePlayerCount() {
        scope.launch {
            graph.playerTracker.online.collect {
                graph.refreshNotificationPlayers()
            }
        }
    }

    /** Optional scheduled daily restart; drifts are acceptable, no WorkManager needed. */
    private fun scheduleDailyRestartIfEnabled(enabled: Boolean, hour: Int) {
        if (!enabled) return
        scope.launch {
            while (true) {
                val settings = graph.settingsSnapshot()
                if (!settings.dailyRestartEnabled) break
                val delayMs = millisUntilNextHour(settings.dailyRestartHour)
                graph.logStore.appendDirect(
                    com.pocketbedrock.app.core.LogLevel.INFO,
                    "Scheduled daily restart in ${delayMs / 60_000} min.",
                )
                kotlinx.coroutines.delay(delayMs)
                val recheck = graph.settingsSnapshot()
                if (!recheck.dailyRestartEnabled) break
                if (graph.supervisor.isRunning) {
                    graph.logStore.appendDirect(com.pocketbedrock.app.core.LogLevel.INFO, "Scheduled daily restart now.")
                    graph.supervisor.restart()
                }
            }
        }
    }

    private fun millisUntilNextHour(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000)
    }

    companion object {
        const val ACTION_START = "com.pocketbedrock.app.action.START"
        const val ACTION_STOP = "com.pocketbedrock.app.action.STOP"
        const val ACTION_RESTART = "com.pocketbedrock.app.action.RESTART"
        const val AUTO_STOP_COOLDOWN_MS = 5 * 60_000L

        fun intent(context: Context, action: String): Intent =
            Intent(context, ServerService::class.java).setAction(action)
    }
}
