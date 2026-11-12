package com.pocketbedrock.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the server process lifecycle: start, graceful stop, restart, crash
 * classification and exponential-backoff auto-restart. Lives in the Application scope
 * (not the UI), so supervision and ban enforcement keep working while the app is
 * closed - the foreground service merely keeps the whole process alive.
 */
class ProcessSupervisor(
    private val scope: CoroutineScope,
    private val logStore: LogStore,
    private val backendFactory: () -> ServerBackend,
    private val configProvider: () -> StartConfig,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(ServerState.OFFLINE)
    val state: StateFlow<ServerState> = _state

    val startedAtMs = MutableStateFlow(0L)
    val lastExitCode = MutableStateFlow<Int?>(null)
    val restartAttempts = MutableStateFlow(0)

    @Volatile
    var autoRestartEnabled: Boolean = true

    @Volatile
    private var backend: ServerBackend? = null

    private val policy = AutoRestartPolicy()
    private var pendingRestartJob: Job? = null

    var onStateChange: ((ServerState) -> Unit)? = null
    var onCrashGivenUp: (() -> Unit)? = null

    val isRunning: Boolean
        get() = _state.value == ServerState.STARTING ||
            _state.value == ServerState.ONLINE ||
            _state.value == ServerState.STOPPING

    fun currentPid(): Int? = backend?.pid()

    suspend fun start(): Result<Unit> = mutex.withLock {
        if (_state.value != ServerState.OFFLINE && _state.value != ServerState.CRASHED) {
            return Result.success(Unit)
        }
        pendingRestartJob?.cancel()
        pendingRestartJob = null

        _state.value = ServerState.STARTING
        onStateChange?.invoke(_state.value)

        val backendInstance = backendFactory()
        try {
            backendInstance.start(configProvider()) { raw -> logStore.onRawLine(raw) }
        } catch (e: Exception) {
            _state.value = ServerState.CRASHED
            onStateChange?.invoke(_state.value)
            logStore.appendDirect(LogLevel.ERROR, "Failed to start server: ${e.message}")
            return Result.failure(e)
        }

        backend = backendInstance
        startedAtMs.value = System.currentTimeMillis()

        // STARTING -> ONLINE on the "Server started." log line (event-driven, no poll).
        scope.launch(Dispatchers.IO) {
            val boot = logStore.awaitLine(
                predicate = { LogParser.isServerStartedLine(it.text) },
                timeoutMs = BOOT_TIMEOUT_MS,
            )
            if (boot != null && backend === backendInstance) {
                _state.value = ServerState.ONLINE
                onStateChange?.invoke(_state.value)
                policy.reset()
                restartAttempts.value = 0
            }
        }

        // Exit watchdog: one blocked waitFor() per run, no polling.
        scope.launch(Dispatchers.IO) {
            val code = backendInstance.waitForExit()
            handleExit(backendInstance, code)
        }

        Result.success(Unit)
    }

    private suspend fun handleExit(finished: ServerBackend, code: Int?) {
        mutex.withLock {
            if (backend !== finished) return // stale watcher: a newer run or explicit stop owns the state
            backend = null
            lastExitCode.value = code

            when (_state.value) {
                ServerState.STOPPING -> {
                    _state.value = ServerState.OFFLINE
                    onStateChange?.invoke(_state.value)
                }
                ServerState.STARTING, ServerState.ONLINE -> {
                    _state.value = ServerState.CRASHED
                    onStateChange?.invoke(_state.value)
                    scheduleAutoRestart(code)
                }
                else -> {
                    _state.value = ServerState.OFFLINE
                    onStateChange?.invoke(_state.value)
                }
            }
        }
    }

    private fun scheduleAutoRestart(exitCode: Int?) {
        if (!autoRestartEnabled) return
        val uptime = System.currentTimeMillis() - startedAtMs.value
        if (policy.isStable(uptime)) policy.reset()

        val delayMs = policy.onCrash()
        restartAttempts.value = policy.attemptsUsed()
        if (delayMs == null) {
            logStore.appendDirect(
                LogLevel.ERROR,
                "Auto-restart gave up after ${policy.attemptsUsed()} consecutive attempts " +
                    "(last exit code: ${exitCode ?: "unknown"}). Manual start required.",
            )
            onCrashGivenUp?.invoke()
            return
        }
        logStore.appendDirect(
            LogLevel.WARN,
            "Server exited unexpectedly (exit code ${exitCode ?: "unknown"}). " +
                "Restarting in ${delayMs / 1000}s (attempt ${policy.attemptsUsed()}/5).",
        )
        pendingRestartJob = scope.launch {
            delay(delayMs)
            start()
        }
    }

    /** Graceful stop: stdin "stop" (BDS saves the world) -> 30 s -> SIGTERM -> 5 s -> SIGKILL. */
    suspend fun stop(requestedByUser: Boolean = true): Int? = mutex.withLock {
        pendingRestartJob?.cancel()
        pendingRestartJob = null
        val backendInstance = backend
        if (backendInstance == null) {
            if (_state.value != ServerState.OFFLINE) {
                _state.value = ServerState.OFFLINE
                onStateChange?.invoke(_state.value)
            }
            return null
        }
        _state.value = ServerState.STOPPING
        onStateChange?.invoke(_state.value)
        val code = withContext(Dispatchers.IO) {
            backendInstance.stop(GRACEFUL_TIMEOUT_MS, KILL_TIMEOUT_MS)
        }
        backend = null
        lastExitCode.value = code
        policy.reset()
        restartAttempts.value = 0
        _state.value = ServerState.OFFLINE
        onStateChange?.invoke(_state.value)
        code
    }

    suspend fun restart() {
        stop()
        start()
    }

    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        backend?.sendCommand(command) ?: false
    }

    companion object {
        const val GRACEFUL_TIMEOUT_MS = 30_000L
        const val KILL_TIMEOUT_MS = 5_000L
        const val BOOT_TIMEOUT_MS = 180_000L
    }
}
