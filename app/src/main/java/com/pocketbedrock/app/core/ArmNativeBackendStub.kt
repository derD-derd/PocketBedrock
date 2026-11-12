package com.pocketbedrock.app.core

/**
 * Placeholder for a future ARM-native backend (PocketMine-MP, Nukkit or Dragonfly run
 * as real ARM64 JVM/PHP processes - no emulation needed). It registers in the backend
 * registry so the UI can present it, but refuses to start with a clear explanation.
 *
 * Swapping it in later requires zero UI changes: everything talks to [ServerBackend].
 */
class ArmNativeBackendStub : ServerBackend {

    override val id = "arm-native-stub"
    override val displayName = "ARM-native (PocketMine / Nukkit / Dragonfly) - planned"
    override val capabilities: Set<BackendCapability> = emptySet()
    override val installedVersion: String? = null

    override fun start(config: StartConfig, logSink: (String) -> Unit) {
        throw BackendStartException(
            "The ARM-native backend is not implemented yet. PocketBedrock currently runs " +
                "the official BDS through proot + box64. The ServerBackend interface is " +
                "already in place for PocketMine-MP / Nukkit / Dragonfly integration.",
        )
    }

    override fun sendCommand(command: String): Boolean = false
    override fun stop(gracefulTimeoutMs: Long, killTimeoutMs: Long): Int? = null
    override fun waitForExit(): Int? = null
    override fun isAlive(): Boolean = false
    override fun pid(): Int? = null
}
