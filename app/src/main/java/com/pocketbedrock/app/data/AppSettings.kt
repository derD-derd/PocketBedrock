package com.pocketbedrock.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketbedrock")

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/**
 * App-level settings (DataStore). Server-side settings live in server.properties and
 * are managed by ServerPropertiesManager instead.
 */
class AppSettings(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val AUTO_RESTART_ON_CRASH = booleanPreferencesKey("auto_restart_on_crash")
        val TEMP_AUTO_STOP_C = intPreferencesKey("temp_auto_stop_c") // 0 = disabled
        val LOG_RETENTION_FILES = intPreferencesKey("log_retention_files")
        val METRICS_INTERVAL_FG = intPreferencesKey("metrics_interval_fg_ms")
        val METRICS_INTERVAL_BG = intPreferencesKey("metrics_interval_bg_ms")
        val BACKUP_DIR_URI = stringPreferencesKey("backup_dir_uri")
        val BACKUP_SCHEDULE_ENABLED = booleanPreferencesKey("backup_schedule_enabled")
        val DAILY_RESTART_ENABLED = booleanPreferencesKey("daily_restart_enabled")
        val DAILY_RESTART_HOUR = intPreferencesKey("daily_restart_hour")
        val NOTIFY_PLAYER_JOIN = booleanPreferencesKey("notify_player_join")
        val NOTIFY_CRASH = booleanPreferencesKey("notify_crash")
        val NOTIFY_OVERHEAT = booleanPreferencesKey("notify_overheat")
        val EULA_ACCEPTED_AT = longPreferencesKey("eula_accepted_at")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val INSTALLED_BDS_VERSION = stringPreferencesKey("installed_bds_version")
        val ROOTFS_INSTALLED = booleanPreferencesKey("rootfs_installed")
        val LIBS_INSTALLED = booleanPreferencesKey("libs_installed")
        val ACTIVE_BACKEND = stringPreferencesKey("active_backend")
    }

    data class Settings(
        val themeMode: ThemeMode = ThemeMode.DARK,
        val keepScreenOn: Boolean = false,
        val autoStartOnBoot: Boolean = false,
        val autoRestartOnCrash: Boolean = true,
        val tempAutoStopC: Int = 0,
        val logRetentionFiles: Int = 5,
        val metricsIntervalFgMs: Int = 1_500,
        val metricsIntervalBgMs: Int = 10_000,
        val backupDirUri: String = "",
        val backupScheduleEnabled: Boolean = false,
        val dailyRestartEnabled: Boolean = false,
        val dailyRestartHour: Int = 4,
        val notifyPlayerJoin: Boolean = false,
        val notifyCrash: Boolean = true,
        val notifyOverheat: Boolean = true,
        val eulaAcceptedAtMs: Long = 0,
        val setupCompleted: Boolean = false,
        val installedBdsVersion: String = "",
        val rootfsInstalled: Boolean = false,
        val libsInstalled: Boolean = false,
        val activeBackend: String = "bds-box64",
    )

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
            autoStartOnBoot = p[Keys.AUTO_START_ON_BOOT] ?: false,
            autoRestartOnCrash = p[Keys.AUTO_RESTART_ON_CRASH] ?: true,
            tempAutoStopC = p[Keys.TEMP_AUTO_STOP_C] ?: 0,
            logRetentionFiles = p[Keys.LOG_RETENTION_FILES] ?: 5,
            metricsIntervalFgMs = p[Keys.METRICS_INTERVAL_FG] ?: 1_500,
            metricsIntervalBgMs = p[Keys.METRICS_INTERVAL_BG] ?: 10_000,
            backupDirUri = p[Keys.BACKUP_DIR_URI] ?: "",
            backupScheduleEnabled = p[Keys.BACKUP_SCHEDULE_ENABLED] ?: false,
            dailyRestartEnabled = p[Keys.DAILY_RESTART_ENABLED] ?: false,
            dailyRestartHour = p[Keys.DAILY_RESTART_HOUR] ?: 4,
            notifyPlayerJoin = p[Keys.NOTIFY_PLAYER_JOIN] ?: false,
            notifyCrash = p[Keys.NOTIFY_CRASH] ?: true,
            notifyOverheat = p[Keys.NOTIFY_OVERHEAT] ?: true,
            eulaAcceptedAtMs = p[Keys.EULA_ACCEPTED_AT] ?: 0L,
            setupCompleted = p[Keys.SETUP_COMPLETED] ?: false,
            installedBdsVersion = p[Keys.INSTALLED_BDS_VERSION] ?: "",
            rootfsInstalled = p[Keys.ROOTFS_INSTALLED] ?: false,
            libsInstalled = p[Keys.LIBS_INSTALLED] ?: false,
            activeBackend = p[Keys.ACTIVE_BACKEND] ?: "bds-box64",
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setKeepScreenOn(v: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = v }
    suspend fun setAutoStartOnBoot(v: Boolean) = edit { it[Keys.AUTO_START_ON_BOOT] = v }
    suspend fun setAutoRestartOnCrash(v: Boolean) = edit { it[Keys.AUTO_RESTART_ON_CRASH] = v }
    suspend fun setTempAutoStopC(v: Int) = edit { it[Keys.TEMP_AUTO_STOP_C] = v.coerceIn(0, 100) }
    suspend fun setLogRetentionFiles(v: Int) = edit { it[Keys.LOG_RETENTION_FILES] = v.coerceIn(1, 20) }
    suspend fun setMetricsIntervals(fg: Int, bg: Int) = edit {
        it[Keys.METRICS_INTERVAL_FG] = fg.coerceIn(1_000, 5_000)
        it[Keys.METRICS_INTERVAL_BG] = bg.coerceIn(10_000, 60_000)
    }
    suspend fun setBackupDirUri(uri: String) = edit { it[Keys.BACKUP_DIR_URI] = uri }
    suspend fun setBackupScheduleEnabled(v: Boolean) = edit { it[Keys.BACKUP_SCHEDULE_ENABLED] = v }
    suspend fun setDailyRestart(enabled: Boolean, hour: Int) = edit {
        it[Keys.DAILY_RESTART_ENABLED] = enabled
        it[Keys.DAILY_RESTART_HOUR] = hour.coerceIn(0, 23)
    }
    suspend fun setNotifyPlayerJoin(v: Boolean) = edit { it[Keys.NOTIFY_PLAYER_JOIN] = v }
    suspend fun setNotifyCrash(v: Boolean) = edit { it[Keys.NOTIFY_CRASH] = v }
    suspend fun setNotifyOverheat(v: Boolean) = edit { it[Keys.NOTIFY_OVERHEAT] = v }
    suspend fun acceptEula(atMs: Long) = edit { it[Keys.EULA_ACCEPTED_AT] = atMs }
    suspend fun setSetupCompleted(v: Boolean) = edit { it[Keys.SETUP_COMPLETED] = v }
    suspend fun setInstalledBdsVersion(v: String) = edit { it[Keys.INSTALLED_BDS_VERSION] = v }
    suspend fun setRootfsInstalled(v: Boolean) = edit { it[Keys.ROOTFS_INSTALLED] = v }
    suspend fun setLibsInstalled(v: Boolean) = edit { it[Keys.LIBS_INSTALLED] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
