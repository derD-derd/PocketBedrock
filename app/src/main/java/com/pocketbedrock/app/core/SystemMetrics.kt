package com.pocketbedrock.app.core

import java.io.File

data class ServerMetrics(
    val pid: Int,
    /** Percent of the whole device CPU capacity (0-100 across all cores). */
    val cpuPercent: Double,
    val rssBytes: Long,
)

data class DeviceMetrics(
    val freeRamBytes: Long,
    val totalRamBytes: Long,
    val batteryTempC: Double?,
    val batteryPercent: Int?,
    val plugged: Boolean,
    val cpuTempC: Double?,
    val thermalStatus: Int,
) {
    val thermalName: String
        get() = thermalStatusName(thermalStatus)
}

fun thermalStatusName(status: Int): String = when (status) {
    0 -> "None"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unknown($status)"
}

/**
 * /proc readers for the server process tree. All reads are tiny stat files; the sampler
 * calls this at the configured interval (1-2 s foreground, 10 s background) and never
 * busy-loops.
 */
object ProcUtils {

    private const val USER_HZ = 100L

    fun readStat(pid: Int): String? = try {
        File("/proc/$pid/stat").readText()
    } catch (_: Exception) {
        null
    }

    /**
     * utime + stime in clock ticks. /proc/<pid>/stat's second field (comm) can contain
     * spaces and parentheses, so parsing starts after the LAST ')'.
     */
    fun cpuTicks(pid: Int): Long? {
        val stat = readStat(pid) ?: return null
        val after = stat.substringAfterLast(") ", "")
        val fields = after.split(" ")
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return utime + stime
    }

    fun ppid(pid: Int): Int? {
        val stat = readStat(pid) ?: return null
        val after = stat.substringAfterLast(") ", "")
        val fields = after.split(" ")
        return fields.getOrNull(1)?.toIntOrNull()
    }

    fun rssBytes(pid: Int): Long? = try {
        val status = File("/proc/$pid/status").readText()
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        line.substringAfter("VmRSS:").trim().split(" ").first().toLongOrNull()?.times(1024)
    } catch (_: Exception) {
        null
    }

    fun listPids(): List<Int> {
        val dir = File("/proc")
        return dir.listFiles()
            ?.filter { it.isDirectory && it.name.all { c -> c.isDigit() } }
            ?.mapNotNull { it.name.toIntOrNull() }
            ?: emptyList()
    }

    /** All descendants of [rootPid] (BFS over /proc ppid links), excluding the root itself. */
    fun descendantPids(rootPid: Int): List<Int> {
        val parentOf = HashMap<Int, Int>()
        for (pid in listPids()) {
            val p = ppid(pid) ?: continue
            parentOf[pid] = p
        }
        val result = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            for ((pid, parent) in parentOf) {
                if (parent == cur && seen.add(pid)) {
                    result.add(pid)
                    queue.add(pid)
                }
            }
        }
        return result
    }

    /** Best-effort CPU temperature from thermal zones; null when unreadable or implausible. */
    fun cpuTempC(): Double? {
        val zones = File("/sys/class/thermal").listFiles() ?: return null
        var fallback: Double? = null
        for (zone in zones) {
            if (!zone.name.startsWith("thermal_zone")) continue
            val type = try {
                File(zone, "type").readText().trim().lowercase()
            } catch (_: Exception) {
                continue
            }
            val temp = try {
                File(zone, "temp").readText().trim().toDoubleOrNull() ?: continue
            } catch (_: Exception) {
                continue
            }
            val celsius = temp / 1000.0
            if (celsius <= 0.0 || celsius > 120.0) continue
            val preferred = type.contains("cpu") || type.contains("soc") ||
                type.contains("big") || type.contains("x1") || type.contains("a55")
            if (preferred) return celsius
            if (fallback == null) fallback = celsius
        }
        return fallback
    }

    /**
     * Aggregate CPU percent of the process tree between two samples, relative to the
     * whole device capacity (all cores together = 100%).
     */
    fun computeCpuPercent(prevTicks: Long, newTicks: Long, intervalMs: Long, cores: Int): Double {
        if (intervalMs <= 0 || cores <= 0) return 0.0
        val tickDelta = (newTicks - prevTicks).coerceAtLeast(0)
        val capacityTicks = (intervalMs * USER_HZ) / 1000.0 * cores
        return ((tickDelta / capacityTicks) * 100.0).coerceIn(0.0, 100.0)
    }
}
