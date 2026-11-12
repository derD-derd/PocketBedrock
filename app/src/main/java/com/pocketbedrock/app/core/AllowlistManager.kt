package com.pocketbedrock.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Allowlist ("Whitelist (Allowlist)" in the UI) management.
 *
 * - The toggle lives in server.properties (`allow-list=true|false`) and needs a restart.
 * - Entries live in allowlist.json next to bedrock_server:
 *   [{"name": "Steve", "xuid": "1234...", "ignoresPlayerLimit": false}]
 * - While the server is running, entries are changed through the `allowlist add/remove`
 *   console commands (BDS rewrites the file itself and stays consistent); while it is
 *   stopped, the file is edited directly.
 */
class AllowlistManager(
    private val propsManager: ServerPropertiesManager,
    private val commandSender: suspend (String) -> Boolean,
    private val isServerRunning: () -> Boolean,
    private val fileProvider: () -> File,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _entries = MutableStateFlow<List<AllowlistEntry>>(emptyList())
    val entries: StateFlow<List<AllowlistEntry>> = _entries

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _entries.value = runCatching { readEntries() }.getOrDefault(emptyList())
            _enabled.value = propsManager.read()["allow-list"] == "true"
        }
    }

    fun readEntries(): List<AllowlistEntry> {
        val file = fileProvider()
        if (!file.exists()) return emptyList()
        return json.decodeFromString(
            ListSerializer(AllowlistEntry.serializer()),
            file.readText(),
        )
    }

    private fun writeEntries(list: List<AllowlistEntry>) {
        val file = fileProvider()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ListSerializer(AllowlistEntry.serializer()), list))
    }

    /** Toggle `allow-list` in server.properties. Returns true (restart required). */
    suspend fun setEnabled(enabled: Boolean) {
        propsManager.write(mapOf("allow-list" to if (enabled) "true" else "false"))
        _enabled.value = enabled
    }

    suspend fun addEntry(entry: AllowlistEntry): Result<Unit> = runCatching {
        if (isServerRunning()) {
            commandSender("allowlist add \"${entry.name}\"")
            delay(300) // BDS rewrites allowlist.json asynchronously after the command
        } else {
            val list = readEntries().filterNot { it.xuid == entry.xuid } + entry
            writeEntries(list)
        }
        _entries.value = runCatching { readEntries() }.getOrDefault(_entries.value)
    }

    suspend fun removeEntry(name: String, xuid: String): Result<Unit> = runCatching {
        if (isServerRunning()) {
            commandSender("allowlist remove \"$name\"")
            delay(300)
        } else {
            val list = readEntries().filterNot { it.xuid == xuid || (xuid.isBlank() && it.name == name) }
            writeEntries(list)
        }
        _entries.value = runCatching { readEntries() }.getOrDefault(_entries.value)
    }
}

@Serializable
data class AllowlistEntry(
    val name: String,
    val xuid: String,
    val ignoresPlayerLimit: Boolean = false,
)
