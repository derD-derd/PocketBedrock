package com.pocketbedrock.app.core

import java.io.File

/**
 * Reads/writes server.properties preserving key order and unknown keys, with
 * validation metadata for the Settings UI. All writes are atomic (tmp + rename).
 */
class ServerPropertiesManager(private val fileProvider: () -> File) {

    fun file(): File = fileProvider()

    fun exists(): Boolean = file().exists()

    fun read(): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val file = file()
        if (!file.exists()) return result
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    /** Merge [values] into the current file, preserving order and unknown keys. */
    fun write(values: Map<String, String>) {
        val current = read()
        val merged = LinkedHashMap<String, String>(current)
        values.forEach { (k, v) -> merged[k] = v }
        val body = merged.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
        val file = file()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    /**
     * Validate a set of values against [PropertyCatalog.specs].
     * Returns key -> error message; keys without an error map to null.
     */
    fun validate(values: Map<String, String>): Map<String, String?> {
        val errors = LinkedHashMap<String, String?>()
        for (spec in PropertyCatalog.specs) {
            val value = values[spec.key] ?: continue
            errors[spec.key] = spec.validate(value)
        }
        return errors
    }

    /** Current value for a key with fallback to the catalog default. */
    fun valueOf(key: String): String = read()[key] ?: PropertyCatalog.defaultFor(key)
}

enum class PropType { STRING, INT, BOOL, ENUM }

data class PropertySpec(
    val key: String,
    val label: String,
    val group: PropertyGroup,
    val type: PropType,
    val min: Int? = null,
    val max: Int? = null,
    val options: List<String> = emptyList(),
    val defaultValue: String,
    val restartRequired: Boolean = true,
    val description: String,
) {
    fun validate(value: String): String? = when (type) {
        PropType.STRING -> if (value.contains('\n')) "No line breaks allowed" else null
        PropType.INT -> {
            val n = value.toIntOrNull() ?: return "Must be a whole number"
            if (min != null && n < min) return "Minimum is $min"
            if (max != null && n > max) return "Maximum is $max"
            null
        }
        PropType.BOOL -> if (value == "true" || value == "false") null else "Must be true or false"
        PropType.ENUM -> if (options.contains(value)) null else "Must be one of: ${options.joinToString()}"
    }
}

enum class PropertyGroup(val title: String) {
    GENERAL("General"),
    NETWORK("Network"),
    PERFORMANCE("Performance"),
    GAMEPLAY("Gameplay & storage"),
}

/**
 * The server.properties keys exposed in the friendly Settings form. Unknown keys stay
 * editable via the raw editor.
 */
object PropertyCatalog {

    val specs: List<PropertySpec> = listOf(
        // General
        PropertySpec(
            "server-name", "Server name", PropertyGroup.GENERAL, PropType.STRING,
            defaultValue = "Dedicated Server",
            description = "Name shown in the in-game server list.",
        ),
        PropertySpec(
            "level-name", "World name", PropertyGroup.GENERAL, PropType.STRING,
            defaultValue = "world",
            description = "Folder name of the world under worlds/. Changing it selects or creates a different world.",
        ),
        PropertySpec(
            "level-seed", "Level seed", PropertyGroup.GENERAL, PropType.STRING,
            defaultValue = "",
            description = "Seed for world generation. Empty = random. Only applies to newly created worlds.",
        ),
        PropertySpec(
            "gamemode", "Default gamemode", PropertyGroup.GENERAL, PropType.ENUM,
            options = listOf("survival", "creative", "adventure"),
            defaultValue = "survival",
            description = "Gamemode for players joining for the first time.",
        ),
        PropertySpec(
            "difficulty", "Difficulty", PropertyGroup.GENERAL, PropType.ENUM,
            options = listOf("peaceful", "easy", "normal", "hard"),
            defaultValue = "easy",
            description = "World difficulty.",
        ),
        PropertySpec(
            "allow-cheats", "Allow cheats", PropertyGroup.GENERAL, PropType.BOOL,
            defaultValue = "false",
            description = "Enables cheats such as /gamemode for players.",
        ),
        PropertySpec(
            "default-player-permission-level", "Default permission level", PropertyGroup.GENERAL, PropType.ENUM,
            options = listOf("operator", "member", "visitor"),
            defaultValue = "member",
            description = "Permission granted to new players.",
        ),

        // Network
        PropertySpec(
            "server-port", "Server port (IPv4)", PropertyGroup.NETWORK, PropType.INT,
            min = 1, max = 65535, defaultValue = "19132",
            description = "UDP port for IPv4 clients. Forward this on your router.",
        ),
        PropertySpec(
            "server-portv6", "Server port (IPv6)", PropertyGroup.NETWORK, PropType.INT,
            min = 1, max = 65535, defaultValue = "19133",
            description = "UDP port for IPv6 clients.",
        ),
        PropertySpec(
            "max-players", "Max players", PropertyGroup.NETWORK, PropType.INT,
            min = 1, max = 1000, defaultValue = "10",
            description = "Maximum simultaneously connected players.",
        ),
        PropertySpec(
            "online-mode", "Online mode (Xbox auth)", PropertyGroup.NETWORK, PropType.BOOL,
            defaultValue = "true",
            description = "Require signed-in Xbox Live accounts. Turning it off allows cracked clients and is not recommended.",
        ),
        PropertySpec(
            "allow-list", "Whitelist (Allowlist)", PropertyGroup.NETWORK, PropType.BOOL,
            defaultValue = "false",
            description = "Only allowlisted players can join. Newer BDS versions call the whitelist \"allowlist\".",
        ),
        PropertySpec(
            "texturepack-required", "Require resource packs", PropertyGroup.NETWORK, PropType.BOOL,
            defaultValue = "false",
            description = "Clients must accept the server's resource packs.",
        ),

        // Performance
        PropertySpec(
            "view-distance", "View distance", PropertyGroup.PERFORMANCE, PropType.INT,
            min = 4, max = 32, defaultValue = "10",
            description = "Chunks visible around each player. The single biggest CPU lever: 6-8 is the sweet spot on a phone.",
        ),
        PropertySpec(
            "tick-distance", "Tick distance", PropertyGroup.PERFORMANCE, PropType.INT,
            min = 4, max = 12, defaultValue = "4",
            description = "Radius (chunks) around players where the world ticks. 4 saves a lot of CPU.",
        ),
        PropertySpec(
            "max-threads", "Max threads", PropertyGroup.PERFORMANCE, PropType.INT,
            min = 1, max = 32, defaultValue = "8",
            description = "Server worker threads. 2-4 leaves headroom for box64 and Android.",
        ),
        PropertySpec(
            "compression-threshold", "Compression threshold", PropertyGroup.PERFORMANCE, PropType.INT,
            min = 0, max = 65535, defaultValue = "1",
            description = "Minimum packet size (bytes) that gets compressed. 0 disables compression.",
        ),

        // Gameplay & storage
        PropertySpec(
            "player-idle-timeout", "Player idle timeout (min)", PropertyGroup.GAMEPLAY, PropType.INT,
            min = 0, max = 10_000, defaultValue = "30",
            description = "Kick idle players after N minutes. 0 disables.",
        ),
        PropertySpec(
            "content-log-file-enabled", "Content log to file", PropertyGroup.GAMEPLAY, PropType.BOOL,
            defaultValue = "false",
            description = "Write content errors to a log file. Useful when debugging behavior packs.",
        ),
        PropertySpec(
            "level-type", "Level type", PropertyGroup.GAMEPLAY, PropType.ENUM,
            options = listOf("DEFAULT", "FLAT", "LEGACY"),
            defaultValue = "DEFAULT",
            description = "World generator type. Only applies to newly created worlds.",
        ),
    )

    val restartRequiredKeys: Set<String> =
        specs.filter { it.restartRequired }.map { it.key }.toSet()

    fun defaultFor(key: String): String = specs.firstOrNull { it.key == key }?.defaultValue ?: ""

    fun specFor(key: String): PropertySpec? = specs.firstOrNull { it.key == key }

    /** Performance presets tuned for a Galaxy S21-class phone. */
    data class Preset(val id: String, val title: String, val values: Map<String, String>, val impact: String)

    val presets: List<Preset> = listOf(
        Preset(
            "battery", "Battery saver",
            mapOf(
                "view-distance" to "6",
                "tick-distance" to "4",
                "max-threads" to "2",
                "max-players" to "5",
                "compression-threshold" to "1",
            ),
            "Lowest CPU/GPU load. Around half the runtime power draw vs default; visible world detail is reduced.",
        ),
        Preset(
            "balanced", "Balanced",
            mapOf(
                "view-distance" to "8",
                "tick-distance" to "4",
                "max-threads" to "4",
                "max-players" to "10",
                "compression-threshold" to "1",
            ),
            "Recommended for this phone with an active cooler. Good detail with sustainable thermals.",
        ),
        Preset(
            "max", "Max performance",
            mapOf(
                "view-distance" to "12",
                "tick-distance" to "6",
                "max-threads" to "8",
                "max-players" to "10",
                "compression-threshold" to "1",
            ),
            "Highest fidelity. Expect higher temperatures and throttling without an external cooler.",
        ),
    )
}
