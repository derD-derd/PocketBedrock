package com.pocketbedrock.app.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Default backend: the official Bedrock Dedicated Server (x86-64 Linux) running inside
 * a proot ARM64 rootfs, interpreted by box64.
 *
 * Process chain:
 *   libproot.so (nativeLibraryDir, ARM64)
 *     -> libbox64.so (nativeLibraryDir, ARM64, bind-mounted into the guest)
 *        -> bedrock_server (x86-64, LOADED AS DATA by box64 - never exec()'d)
 *
 * On Android 10+ (targetSdk >= 29) the kernel refuses exec() and executable file
 * mappings for anything under app-writable storage (W^X policy). nativeLibraryDir is
 * the only app-controlled executable location, which is why proot and box64 ship as
 * lib*.so files there. Everything inside the rootfs is only ever read as data.
 */
class BdsBox64Backend(
    override val installedVersion: String?,
) : ServerBackend {

    override val id = "bds-box64"
    override val displayName = "BDS (proot + box64)"
    override val capabilities = setOf(
        BackendCapability.STDIN_COMMANDS,
        BackendCapability.LIVE_LOG,
        BackendCapability.PLAYER_EVENTS,
        BackendCapability.LIVE_BACKUP,
        BackendCapability.SERVER_METRICS,
        BackendCapability.GRACEFUL_STOP,
    )

    private var process: Process? = null
    private var stdinStream: java.io.OutputStream? = null
    private val ioLock = Any()

    override fun start(config: StartConfig, logSink: (String) -> Unit) {
        synchronized(ioLock) {
            check(process == null) { "Backend already running" }

            val proot = config.prootHostPath
            val box64 = config.box64HostPath
            val server = File(config.bdsDir, "bedrock_server")
            if (!proot.exists()) {
                throw BackendStartException("proot binary is missing at ${proot.absolutePath}")
            }
            if (!box64.exists()) {
                throw BackendStartException("box64 binary is missing at ${box64.absolutePath}")
            }
            if (!server.exists()) {
                throw BackendStartException("bedrock_server is missing in ${config.bdsDir.absolutePath}")
            }

            val pb = ProcessBuilder(buildCommand(config))
                .directory(config.bdsDir)
                .redirectErrorStream(true)

            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/usr/local/lib"
            env["HOME"] = "/bds"
            env["TMPDIR"] = "/tmp"
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            env["PROOT_NO_SECCOMP"] = "1"
            env["BOX64_LOG"] = "0"
            env["BOX64_PREFER_WRAPPED"] = "1"
            config.extraEnv.forEach { (k, v) -> env[k] = v }

            val proc = try {
                pb.start()
            } catch (e: Exception) {
                throw BackendStartException("Failed to spawn the proot/box64 chain: ${e.message}", e)
            }
            process = proc
            stdinStream = proc.outputStream

            // One blocked reader thread per output stream. readLine() parks the thread
            // in the kernel until a line arrives: event-driven, zero CPU while idle.
            Thread(
                {
                    try {
                        val reader = BufferedReader(
                            InputStreamReader(proc.inputStream, Charsets.UTF_8),
                            64 * 1024,
                        )
                        while (true) {
                            val line = reader.readLine() ?: break
                            logSink(line)
                        }
                    } catch (_: Exception) {
                        // Stream closed by exit/kill; the supervisor handles the exit code.
                    }
                },
                "pb-stdout-reader",
            ).start()
        }
    }

    internal fun buildCommand(config: StartConfig): List<String> = mutableListOf(
        config.prootHostPath.absolutePath,
        "--kill-on-exit",
        "-0",
        "-w", "/bds",
        "-R", config.rootfsDir.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "${config.bdsDir.absolutePath}:/bds",
        "-b", "${config.box64HostPath.absolutePath}:/usr/local/bin/box64",
        "/usr/local/bin/box64",
        "/bds/bedrock_server",
    )

    override fun sendCommand(command: String): Boolean {
        val stream = stdinStream ?: return false
        return try {
            synchronized(ioLock) {
                stream.write((command + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun stop(gracefulTimeoutMs: Long, killTimeoutMs: Long): Int? {
        val proc = process ?: return null
        // 1) stdin "stop" lets BDS flush and save the world.
        sendCommand("stop")
        // 2) Wait up to the graceful timeout.
        if (proc.waitFor(gracefulTimeoutMs, TimeUnit.MILLISECONDS)) return proc.exitValue()
        // 3) SIGTERM.
        proc.destroy()
        if (proc.waitFor(killTimeoutMs, TimeUnit.MILLISECONDS)) return proc.exitValue()
        // 4) SIGKILL.
        proc.destroyForcibly()
        proc.waitFor()
        return proc.exitValue()
    }

    override fun waitForExit(): Int? {
        val proc = process ?: return null
        return try {
            proc.waitFor()
            proc.exitValue()
        } catch (_: Exception) {
            null
        }
    }

    override fun isAlive(): Boolean = process?.isAlive ?: false

    override fun pid(): Int? = process?.takeIf { it.isAlive }?.pid()
}
