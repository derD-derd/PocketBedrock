package com.pocketbedrock.app.core

import java.io.File

/** Lifecycle state of the server process, mirrored into the UI and the notification. */
enum class ServerState {
    OFFLINE,
    STARTING,
    ONLINE,
    STOPPING,
    CRASHED,
}

enum class LogLevel { INFO, WARN, ERROR, RAW }

/** One immutable log line. [seq] is the stable key used by the console LazyColumn. */
data class LogLine(
    val seq: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val text: String,
)

data class OnlinePlayer(
    val name: String,
    val xuid: String,
    val joinedAtMs: Long,
)

/**
 * Everything a backend needs to spawn the server process.
 *
 * [bdsDir] is the working directory that contains bedrock_server and server.properties.
 * [box64HostPath] / [prootHostPath] point inside nativeLibraryDir, the only location from
 * which an app may exec() binaries on Android 10+ (W^X policy).
 */
data class StartConfig(
    val bdsDir: File,
    val rootfsDir: File,
    val prootHostPath: File,
    val box64HostPath: File,
    val extraEnv: Map<String, String> = emptyMap(),
)

enum class BackendCapability {
    STDIN_COMMANDS,
    LIVE_LOG,
    PLAYER_EVENTS,
    LIVE_BACKUP,
    SERVER_METRICS,
    GRACEFUL_STOP,
}

/** Thrown when a backend fails to spawn the server process. */
class BackendStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A pluggable server runtime. The default implementation is the official BDS running
 * through proot + box64; an ARM-native backend (PocketMine / Nukkit / Dragonfly) can be
 * swapped in later behind this same interface.
 */
interface ServerBackend {
    val id: String
    val displayName: String
    val capabilities: Set<BackendCapability>
    val installedVersion: String?

    /** Spawn the process. [logSink] receives every raw output line. Blocks until spawned. */
    fun start(config: StartConfig, logSink: (String) -> Unit)

    /** Send a console command to stdin. Returns false when the process is not alive. */
    fun sendCommand(command: String): Boolean

    /**
     * Graceful stop chain: stdin "stop" -> wait -> SIGTERM -> wait -> SIGKILL.
     * Blocking; returns the process exit code (null when unknown).
     */
    fun stop(gracefulTimeoutMs: Long = 30_000L, killTimeoutMs: Long = 5_000L): Int?

    /** Blocking wait for process exit. Returns the exit code, null if it never ran. */
    fun waitForExit(): Int?

    fun isAlive(): Boolean
    fun pid(): Int?
}
