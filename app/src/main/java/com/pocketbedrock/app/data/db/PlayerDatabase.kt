package com.pocketbedrock.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey val xuid: String,
    val lastName: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val playtimeSeconds: Long,
    val sessions: Int,
)

@Entity(tableName = "bans")
data class BanEntity(
    @PrimaryKey val xuid: String,
    val name: String,
    val reason: String,
    val bannedAtMs: Long,
    /** null = permanent ban. */
    val expiresAtMs: Long?,
    val active: Boolean,
)

@Dao
interface PlayerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(player: PlayerEntity)

    @Query("SELECT * FROM players WHERE xuid = :xuid")
    suspend fun getByXuid(xuid: String): PlayerEntity?

    @Query("SELECT * FROM players ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<PlayerEntity>>

    @Query("SELECT COUNT(*) FROM players")
    suspend fun count(): Int

    @Query("DELETE FROM players")
    suspend fun clear()
}

@Dao
interface BanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ban: BanEntity)

    @Query("SELECT * FROM bans WHERE active = 1 ORDER BY bannedAtMs DESC")
    fun observeActive(): Flow<List<BanEntity>>

    @Query("SELECT * FROM bans WHERE xuid = :xuid AND active = 1 LIMIT 1")
    suspend fun getActive(xuid: String): BanEntity?

    @Query("SELECT * FROM bans ORDER BY bannedAtMs DESC")
    suspend fun all(): List<BanEntity>

    @Query("UPDATE bans SET active = 0 WHERE xuid = :xuid")
    suspend fun deactivate(xuid: String)

    @Query("DELETE FROM bans WHERE xuid = :xuid")
    suspend fun delete(xuid: String)

    @Query("DELETE FROM bans")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bans: List<BanEntity>)
}

@Database(
    entities = [PlayerEntity::class, BanEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PocketBedrockDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun banDao(): BanDao

    companion object {
        fun build(context: Context): PocketBedrockDatabase =
            Room.databaseBuilder(context, PocketBedrockDatabase::class.java, "pocketbedrock.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
