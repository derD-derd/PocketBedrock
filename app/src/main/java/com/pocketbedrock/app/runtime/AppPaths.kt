package com.pocketbedrock.app.runtime

import android.content.Context
import java.io.File

/**
 * Single source of truth for on-device layout:
 *
 *   files/pocketbedrock/
 *     rootfs/           - Ubuntu 22.04 arm64 rootfs (proot guest /)
 *     bds/bedrock-server-<ver>/ - extracted official BDS zip (cwd of the server)
 *     deb-cache/        - downloaded amd64 .deb staging
 *     logs/             - rotating server log files
 *   cache/downloads/    - in-flight downloads
 */
class AppPaths(context: Context) {

    private val root: File = File(context.filesDir, "pocketbedrock")

    val rootfsDir: File = File(root, "rootfs")
    val bdsRootDir: File = File(root, "bds")
    val debCacheDir: File = File(root, "deb-cache")
    val logsDir: File = File(root, "logs")
    val downloadDir: File = File(context.cacheDir, "downloads")
    val addonStagingDir: File = File(context.cacheDir, "addons")

    /** Default backup target; the user can pick any SAF folder in Settings. */
    val defaultBackupDir: File? = context.getExternalFilesDir(null)?.let { File(it, "backups") }

    fun bdsDirFor(version: String): File = File(bdsRootDir, "bedrock-server-$version")

    fun installedBdsVersions(): List<String> =
        bdsRootDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("bedrock-server-") }
            ?.mapNotNull { it.name.removePrefix("bedrock-server-") }
            ?.sortedDescending()
            ?: emptyList()

    fun ensureRootDirs() {
        root.mkdirs()
        rootfsDir.mkdirs()
        bdsRootDir.mkdirs()
        debCacheDir.mkdirs()
        logsDir.mkdirs()
        downloadDir.mkdirs()
    }
}
