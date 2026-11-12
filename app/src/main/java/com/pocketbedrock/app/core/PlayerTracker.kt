package com.pocketbedrock.app.core

import com.pocketbedrock.app.data.db.PlayerDao
import com.pocketbedrock.app.data.db.PlayerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks online players from parsed log events and persists lifetime history
 * (first seen / last seen / total playtime) in Room. Fed entirely by the log
 * pipeline, so tracking and playtime accounting keep running while the UI is closed.
 */
class PlayerTracker(
    private val playerDao: PlayerDao,
    private val scope: CoroutineScope,
) {
    private val _online = MutableStateFlow<List<OnlinePlayer>>(emptyList())
    val online: StateFlow<List<OnlinePlayer>> = _online

    /** Fired once per verified join; consumed by ban enforcement and notifications. */
    private val _playerJoined = MutableSharedFlow<OnlinePlayer>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val playerJoined: SharedFlow<OnlinePlayer> = _playerJoined

    private val mutex = Mutex()
    private val joinedAt = HashMap<String, Long>()

    fun onPlayerEvent(name: String, xuid: String, joined: Boolean) {
        scope.launch {
            val now = System.currentTimeMillis()
            if (joined) {
                val startAt = mutex.withLock {
                    joinedAt[xuid] = now
                    _online.value = (_online.value.filter { it.xuid != xuid }) +
                        OnlinePlayer(name, xuid, now)
                    now
                }
                _playerJoined.tryEmit(OnlinePlayer(name, xuid, startAt))

                val existing = playerDao.getByXuid(xuid)
                playerDao.upsert(
                    if (existing == null) {
                        PlayerEntity(
                            xuid = xuid,
                            lastName = name,
                            firstSeenMs = now,
                            lastSeenMs = now,
                            playtimeSeconds = 0,
                            sessions = 1,
                        )
                    } else {
                        existing.copy(
                            lastName = name,
                            lastSeenMs = now,
                            sessions = existing.sessions + 1,
                        )
                    },
                )
            } else {
                val sessionStart = mutex.withLock {
                    val start = joinedAt.remove(xuid) ?: 0L
                    _online.value = _online.value.filter { it.xuid != xuid }
                    start
                }
                val existing = playerDao.getByXuid(xuid)
                if (existing != null) {
                    val added = if (sessionStart > 0) {
                        ((now - sessionStart) / 1000).coerceAtLeast(0)
                    } else {
                        0
                    }
                    playerDao.upsert(
                        existing.copy(
                            lastName = name,
                            lastSeenMs = now,
                            playtimeSeconds = existing.playtimeSeconds + added,
                        ),
                    )
                }
            }
        }
    }

    /** Server is starting: any leftover online entries are stale (crash or kill). */
    fun clearOnline() {
        scope.launch {
            mutex.withLock {
                joinedAt.clear()
                _online.value = emptyList()
            }
        }
    }
}
