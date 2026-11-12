package com.pocketbedrock.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Downloads and installs the official Bedrock Dedicated Server (Linux x86-64) zip.
 *
 * The zip is fetched either from minecraft.net/bedrockdedicatedserver (resolved via the
 * minecraft.wiki BDS page, which is server-rendered) or imported manually by the user -
 * Mojang licensing forbids bundling BDS with the app.
 */
class BdsInstaller(
    private val paths: AppPaths,
    private val downloader: HttpDownloader,
) {

    fun isInstalled(version: String): Boolean =
        File(paths.bdsDirFor(version), "bedrock_server").exists()

    /** Resolve the newest release version, verifying the download URL really exists. */
    suspend fun resolveLatestVersion(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val wiki = downloader.fetchText(RuntimeSources.BDS_WIKI_MAIN)
            val candidates = LinkedHashSet<String>()
            wiki?.let {
                Regex("Bedrock_Dedicated_Server_(\\d+\\.\\d+\\.\\d+\\.\\d+)").findAll(it).forEach { m ->
                    candidates.add(m.groupValues[1])
                }
                Regex("bedrock-server-(\\d+\\.\\d+\\.\\d+\\.\\d+)\\.zip").findAll(it).forEach { m ->
                    candidates.add(m.groupValues[1])
                }
            }
            val sorted = candidates.sortedWith(
                compareByDescending { DebResolver.Version(it) },
            )
            for (version in sorted) {
                if (zipUrlFor(version) != null) return@runCatching version
            }
            throw IOException(
                "Could not determine the latest BDS version automatically. " +
                    "Enter a version manually or import a zip you downloaded yourself.",
            )
        }
    }

    fun zipUrlFor(version: String): String? {
        for (template in RuntimeSources.BDS_ZIP_URL_TEMPLATES) {
            val url = String.format(template, version)
            if (downloader.headExists(url)) return url
        }
        return null
    }

    suspend fun download(
        version: String,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = zipUrlFor(version)
                ?: throw IOException("No download URL available for BDS $version. Import the zip manually.")
            val dest = File(paths.downloadDir, "bedrock-server-$version.zip")
            downloader.downloadToFile(url, dest, onProgress)
            dest
        }
    }

    /**
     * Extract a BDS zip (downloaded or user-imported) into
     * bds/bedrock-server-<version>/ and make bedrock_server executable.
     */
    suspend fun installFromZip(zip: File, version: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = paths.bdsDirFor(version)
                if (target.exists()) throw IOException("BDS $version is already installed at ${target.absolutePath}")
                val staging = File(paths.bdsRootDir, ".staging-$version")
                staging.deleteRecursively()
                staging.mkdirs()
                extractZip(zip, staging)
                // Some zips wrap everything in one root folder; strip it.
                val children = staging.listFiles() ?: emptyArray()
                val rootDir = children.singleOrNull { it.isDirectory }?.takeIf { dir ->
                    children.none { it.isFile } && dir.resolve("bedrock_server").exists()
                }
                if (rootDir != null) {
                    File(target.parentFile, target.name).let { t ->
                        t.parentFile?.mkdirs()
                        if (!rootDir.renameTo(t)) throw IOException("Could not move staged files into place")
                    }
                } else {
                    if (!staging.renameTo(target)) throw IOException("Could not move staged files into place")
                }
                staging.deleteRecursively()
                File(target, "bedrock_server").setExecutable(true, false)
                target
            }
        }

    private fun extractZip(zip: File, destDir: File) {
        ZipInputStream(zip.inputStream().buffered(), Charsets.UTF_8).use { zin ->
            while (true) {
                val entry: ZipEntry = zin.nextEntry ?: break
                val name = entry.name
                if (name.isEmpty()) continue
                val out = File(destDir, name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                    if ((entry.unixMode and 0x49) != 0) { // user/group/other exec bits
                        try {
                            android.system.Os.chmod(out.absolutePath, entry.unixMode and 0xFFF)
                        } catch (_: Exception) {
                            out.setExecutable(true, false)
                        }
                    }
                }
                zin.closeEntry()
            }
        }
    }
}
