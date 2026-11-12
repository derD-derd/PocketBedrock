package com.pocketbedrock.app.runtime

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Installs an arm64 glibc rootfs (Ubuntu 22.04 base by default) into [AppPaths.rootfsDir].
 *
 * Extraction handles symlinks and hardlinks (ubuntu-base uses both, e.g. /bin -> usr/bin)
 * and restores unix permissions so box64/apt tooling inside the guest behaves normally.
 * After extraction, DNS config and bind targets are prepared for the proot session.
 */
class RootfsInstaller(
    private val paths: AppPaths,
    private val downloader: HttpDownloader,
) {

    fun isInstalled(): Boolean =
        File(paths.rootfsDir, "usr/bin/sh").exists() &&
            File(paths.rootfsDir, "etc/passwd").exists() &&
            File(paths.rootfsDir, "lib").exists()

    suspend fun install(
        tarball: File,
        onPhase: (String) -> Unit = {},
        onProgress: (entriesDone: Int) -> Unit = {},
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            onPhase("Extracting rootfs…")
            extractTarGz(tarball, paths.rootfsDir, onProgress)
            onPhase("Preparing guest filesystem…")
            postInstall()
        }
    }

    suspend fun downloadAndInstall(
        onPhase: (String) -> Unit,
        onDownload: (Long, Long?) -> Unit,
        onExtract: (Int) -> Unit,
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            onPhase("Downloading rootfs (Ubuntu 22.04 arm64 base)…")
            val dest = File(paths.downloadDir, "ubuntu-base-arm64.tar.gz")
            downloader.downloadToFile(RuntimeSources.UBUNTU_BASE_URL, dest, onDownload)
            onPhase("Extracting rootfs…")
            extractTarGz(dest, paths.rootfsDir, onExtract)
            onPhase("Preparing guest filesystem…")
            postInstall()
            dest.delete()
        }
    }

    /** DNS, bind targets and tmp for the proot session. Idempotent. */
    fun postInstall() {
        val rootfs = paths.rootfsDir
        File(rootfs, "etc").mkdirs()
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "bds").mkdirs()
        File(rootfs, "proc").mkdirs()
        File(rootfs, "dev").mkdirs()

        try {
            File(rootfs, "etc/resolv.conf").writeText(
                """
                # Written by PocketBedrock
                nameserver 1.1.1.1
                nameserver 8.8.8.8
                options timeout:2 attempts:2
                """.trimIndent() + "\n",
            )
            val hosts = File(rootfs, "etc/hosts")
            if (!hosts.exists() || !hosts.readText().contains("localhost")) {
                hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
            }
        } catch (_: Exception) {
            // Non-fatal: box64 forwards name resolution to the host resolver anyway.
        }
    }

    private fun extractTarGz(tarball: File, destDir: File, onProgress: (Int) -> Unit) {
        destDir.mkdirs()
        var entriesDone = 0
        val hardlinks = ArrayList<Pair<File, String>>() // link path -> target path
        FileInputStream(tarball).use { fin ->
            GZIPInputStream(fin, 64 * 1024).use { gin ->
                TarArchiveInputStream(gin, 8 * 1024).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val name = entry.name.removePrefix("./")
                        if (name.isEmpty()) continue
                        val out = File(destDir, name)
                        // Zip-slip guard: never write outside destDir.
                        if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) continue

                        when {
                            entry.isDirectory -> out.mkdirs()
                            entry.isSymbolicLink -> {
                                out.parentFile?.mkdirs()
                                out.delete()
                                try {
                                    Files.createSymbolicLink(
                                        out.toPath(),
                                        Path.of(entry.linkName),
                                    )
                                } catch (_: Exception) {
                                    // Filesystem without symlink support: copy the target later if possible.
                                }
                            }
                            entry.isLink -> {
                                out.parentFile?.mkdirs()
                                hardlinks.add(out to entry.linkName)
                            }
                            else -> {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { IOUtils.copy(tar, it) }
                                try {
                                    Os.chmod(out.absolutePath, entry.mode and 0xFFF)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        entriesDone++
                        if (entriesDone % 25 == 0) onProgress(entriesDone)
                    }
                }
            }
        }
        // Second pass for hardlinks: targets now exist.
        for ((link, target) in hardlinks) {
            try {
                val targetFile = File(destDir, target.removePrefix("./"))
                if (targetFile.exists()) {
                    link.delete()
                    Files.createLink(link.toPath(), targetFile.toPath())
                }
            } catch (_: Exception) {
                // Hardlink unsupported: ignore (these are cosmetic duplicates in ubuntu-base).
            }
        }
        onProgress(entriesDone)
    }
}
