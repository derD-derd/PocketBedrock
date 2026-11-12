package com.pocketbedrock.app.core

import com.pocketbedrock.app.data.db.BanDao
import com.pocketbedrock.app.data.db.BanEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Vanilla BDS has no ban command, so PocketBedrock implements bans app-side:
 * a persistent ban list in Room plus immediate enforcement when a banned player
 * connects ("kick \"<name>\" <reason>"). Enforcement runs in the log pipeline inside
 * the application scope, so it works whenever the server is running - with or without
 * the UI.
 */
class BanManager(
    private val banDao: BanDao,
    private val commandSender: suspend (String) -> Boolean,
    private val allowlist: AllowlistManager,
    scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _bans = MutableStateFlow<List<BanEntity>>(emptyList())
    val bans: StateFlow<List<BanEntity>> = _bans

    init {
        scope.launch { _bans.value = banDao.all().filter { it.active } }
    }

    suspend fun refresh() {
        _bans.value = banDao.all().filter { it.active }
    }

    /**
     * Called for every parsed player-join event. Returns true when the player was
     * banned and a kick was issued.
     */
    suspend fun enforceOnJoin(name: String, xuid: String): Boolean {
        val ban = banDao.getActive(xuid) ?: return false
        val now = System.currentTimeMillis()
        val expires = ban.expiresAtMs
        if (expires != null && now >= expires) {
            banDao.deactivate(xuid)
            refresh()
            return false
        }
        commandSender("kick \"$name\" You are banned from this server: ${ban.reason}")
        return true
    }

    /**
     * Ban by XUID (names change; XUIDs don't). Banning also removes the player from
     * the allowlist when the allowlist is enabled.
     */
    suspend fun ban(name: String, xuid: String, reason: String, expiresAtMs: Long?): Result<Unit> {
        return runCatching {
            banDao.upsert(
                BanEntity(
                    xuid = xuid,
                    name = name,
                    reason = reason.ifBlank { "Banned by server operator" },
                    bannedAtMs = System.currentTimeMillis(),
                    expiresAtMs = expiresAtMs,
                    active = true,
                ),
            )
            refresh()
            if (allowlist.isEnabled()) {
                allowlist.removeEntry(name, xuid)
            }
        }
    }

    suspend fun unban(xuid: String) {
        banDao.delete(xuid)
        refresh()
    }

    fun exportJson(): String =
        json.encodeToString(ListSerializer(BanDto.serializer()), banDao.all().map { it.toDto() })

    suspend fun importJson(text: String): Result<Int> = runCatching {
        val list = json.decodeFromString(ListSerializer(BanDto.serializer()), text)
        banDao.upsertAll(list.map { it.toEntity() })
        refresh()
        list.size
    }

    private fun BanEntity.toDto() = BanDto(name, xuid, reason, bannedAtMs, expiresAtMs)
    private fun BanDto.toEntity() = BanEntity(xuid, name, reason, bannedAtMs, expiresAtMs, active = true)

    @Serializable
    data class BanDto(
        val name: String,
        val xuid: String,
        val reason: String,
        val bannedAtMs: Long,
        val expiresAtMs: Long? = null,
    )
}
