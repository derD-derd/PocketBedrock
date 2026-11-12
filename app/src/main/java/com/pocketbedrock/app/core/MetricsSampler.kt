package com.pocketbedrock.app.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Samples server and device metrics at an adaptive interval:
 *   - foreground, screen on: 1-2 s
 *   - background: 10 s
 *
 * Server CPU is derived from /proc/<pid>/stat tick deltas across the whole process tree
 * (proot + box64 + server); RSS is the summed VmRSS of that tree. All reads are tiny
 * files; there are no busy loops.
 */
class MetricsSampler(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cores = max(1, Runtime.getRuntime().availableProcessors())

    private val _server = MutableStateFlow<ServerMetrics?>(null)
    val server: StateFlow<ServerMetrics?> = _server

    private val _device = MutableStateFlow<DeviceMetrics?>(null)
    val device: StateFlow<DeviceMetrics?> = _device

    @Volatile
    private var intervalMs = 1_500L

    @Volatile
    private var pidProvider: () -> Int? = { null }

    private var job: Job? = null
    private var lastTicks = -1L
    private var lastSampleAt = 0L

    fun configure(intervalMs: Long, pidProvider: () -> Int?) {
        this.intervalMs = intervalMs.coerceIn(500L, 60_000L)
        this.pidProvider = pidProvider
    }

    fun start() {
        if (job?.isActive == true) return
        lastTicks = -1
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sample()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _server.value = null
    }

    fun sampleOnce() {
        scope.launch(Dispatchers.IO) { sample() }
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val pid = pidProvider()
        _server.value = if (pid != null && ProcUtils.readStat(pid) != null) {
            sampleServer(pid, now)
        } else {
            lastTicks = -1
            null
        }
        _device.value = sampleDevice()
        lastSampleAt = now
    }

    private fun sampleServer(pid: Int, now: Long): ServerMetrics? {
        val ticks = treeCpuTicks(pid) ?: return null
        val cpu = if (lastTicks >= 0 && lastSampleAt > 0) {
            ProcUtils.computeCpuPercent(lastTicks, ticks, now - lastSampleAt, cores)
        } else {
            0.0
        }
        lastTicks = ticks
        val rss = treeRssBytes(pid) ?: return null
        return ServerMetrics(pid = pid, cpuPercent = cpu, rssBytes = rss)
    }

    private fun treeCpuTicks(rootPid: Int): Long? {
        var total = ProcUtils.cpuTicks(rootPid) ?: return null
        for (child in ProcUtils.descendantPids(rootPid)) {
            total += ProcUtils.cpuTicks(child) ?: 0
        }
        return total
    }

    private fun treeRssBytes(rootPid: Int): Long? {
        var total = ProcUtils.rssBytes(rootPid) ?: return null
        for (child in ProcUtils.descendantPids(rootPid)) {
            total += ProcUtils.rssBytes(child) ?: 0
        }
        return total
    }

    private fun sampleDevice(): DeviceMetrics {
        val mem = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mem)
        val thermal = powerManager.currentThermalStatus
        val battery = readBatteryState()
        return DeviceMetrics(
            freeRamBytes = mem.availMem,
            totalRamBytes = mem.totalMem,
            batteryTempC = battery.tempC ?: batteryManager.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_TEMPERATURE,
            ).takeIf { it > 0 }?.div(10.0),
            batteryPercent = battery.percent,
            plugged = battery.plugged,
            cpuTempC = ProcUtils.cpuTempC(),
            thermalStatus = thermal,
        )
    }

    private data class BatteryState(
        val tempC: Double?,
        val percent: Int?,
        val plugged: Boolean,
    )

    private fun readBatteryState(): BatteryState = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            BatteryState(null, null, false)
        } else {
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            BatteryState(
                tempC = if (tenths > 0) tenths / 10.0 else null,
                percent = if (level >= 0 && scale > 0) (level * 100) / scale else null,
                plugged = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
    } catch (_: Exception) {
        BatteryState(null, null, false)
    }
}
