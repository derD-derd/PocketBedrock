package com.pocketbedrock.app.runtime

import android.system.Os
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Extracts x86-64 shared libraries straight out of Ubuntu amd64 .deb files, without
 * dpkg/apt. This keeps the guest rootfs static: the server runtime never needs to run
 * apt inside proot (which would require executing guest binaries from app storage).
 *
 * Supported data.tar compressions: zstd (jammy default), xz, gzip, bzip2, plain.
 */
object DebExtractor {

    /**
     * Copies every *.so* file that lives in an x86_64-linux-gnu directory of the deb's
     * data.tar into [outDir] (flat, keeping the real soname filename).
     * Returns the list of extracted file names.
     */
    fun extractSharedLibs(deb: File, outDir: File): List<String> {
        outDir.mkdirs()
        val copied = mutableListOf<String>()
        FileInputStream(deb).use { fin ->
            ArArchiveInputStream(fin).use { ar ->
                while (true) {
                    val entry = ar.nextArEntry ?: break
                    if (!entry.name.startsWith("data.tar")) continue
                    TarArchiveInputStream(decompress(entry.name, ar), 8 * 1024).use { tar ->
                        while (true) {
                            val te = tar.nextTarEntry ?: break
                            if (!te.isFile) continue
                            val path = te.name.removePrefix("./")
                            if (!isSharedLibPath(path)) continue
                            val out = File(outDir, File(path).name)
                            FileOutputStream(out).use { IOUtils.copy(tar, it) }
                            try {
                                Os.chmod(out.absolutePath, te.mode and 0xFFF)
                            } catch (_: Exception) {
                            }
                            copied.add(out.name)
                        }
                    }
                    break // only the first data.tar member matters
                }
            }
        }
        return copied
    }

    private fun isSharedLibPath(path: String): Boolean {
        if (!path.contains("x86_64-linux-gnu/")) return false
        val name = File(path).name
        return name.contains(".so") && !name.contains(".so.dbg")
    }

    private fun decompress(memberName: String, input: InputStream): InputStream = when {
        memberName.endsWith(".tar.zst") -> ZstdCompressorInputStream(input)
        memberName.endsWith(".tar.xz") -> XZCompressorInputStream(input)
        memberName.endsWith(".tar.gz") -> GZIPInputStream(input)
        memberName.endsWith(".tar.bz2") -> BZip2CompressorInputStream(input)
        else -> input
    }
}
