package com.pocketbedrock.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * BDS stores player permission levels in permissions.json next to bedrock_server:
 *   {"1234567890": {"name": "Steve", "permission": "operator"}}
 *
 * BDS reads this file at startup, so edits require a restart (the UI states this).
 */
class PermissionsManager(private val bdsDirProvider: () -> File) {

    data class PermEntry(val xuid: String, val name: String, val permission: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun file(): File = File(bdsDirProvider(), "permissions.json")

    fun read(): List<PermEntry> {
        val file = file()
        if (!file.exists()) return emptyList()
        return try {
            val obj = json.parseToJsonElement(file.readText()).jsonObject
            obj.entries.mapNotNull { (xuid, value) ->
                val entry = value.jsonObject
                val permission = entry["permission"]?.toString()?.trim('"') ?: return@mapNotNull null
                PermEntry(
                    xuid = xuid,
                    name = entry["name"]?.toString()?.trim('"') ?: "",
                    permission = permission,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** permission = "operator" | "member" | "visitor" | null (remove entry). */
    fun set(xuid: String, name: String, permission: String?) {
        val file = file()
        val current: JsonObject = try {
            if (file.exists()) {
                json.parseToJsonElement(file.readText()).jsonObject
            } else {
                JsonObject(emptyMap())
            }
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }

        val builder = current.toMutableMap()
        if (permission == null) {
            builder.remove(xuid)
        } else {
            builder[xuid] = buildJsonObject {
                put("name", name)
                put("permission", permission)
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(builder)))
    }

    fun permissionFor(xuid: String): String? =
        read().firstOrNull { it.xuid == xuid }?.permission
}
